package de.emilo.spotifycontrol.lyrics;

import de.emilo.spotifycontrol.SpotifyControl;
import de.emilo.spotifycontrol.client.ClientState;
import de.emilo.spotifycontrol.config.ClientConfig;
import de.emilo.spotifycontrol.config.ConfigManager;
import de.emilo.spotifycontrol.gui.Theme;
import de.emilo.spotifycontrol.mixin.DisplayAccess;
import de.emilo.spotifycontrol.mixin.TextDisplayAccess;
import de.emilo.spotifycontrol.platform.Platform;
import de.emilo.spotifycontrol.spotify.model.PlaybackState;
import de.emilo.spotifycontrol.spotify.model.Track;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Places the lyrics of the playing track into the world as floating text.
 *
 * <p>Every few words become one text display that appears in front of the player at the moment
 * they are sung. Each piece gets its own block at a slightly random distance, height and side
 * offset, is turned towards the player once when it appears and then keeps its position and
 * rotation. When its line is over it fades out and is removed. Pieces never cover each other as
 * seen from the player.</p>
 *
 * <p>The displays are entities that exist only on this client: no packet is sent, the server and
 * other players never see them, and they vanish with the world. Their ids are negative so they can
 * never collide with an entity the server sends.</p>
 */
public final class WorldLyrics {

    private static final float MIN_SCALE = 2.3f;
    private static final float MAX_SCALE = 3.1f;
    /** Distance at which a piece is drawn at its plain scale. */
    private static final double REFERENCE_DISTANCE = 6.0;
    /** Most pieces that may be in the world at once, whatever the settings say. */
    private static final int HARD_LIMIT = 160;
    /** Most display entities in the world at once, counting both sides of every letter and word. */
    private static final int MAX_ENTITIES = 700;
    /** Largest sideways tilt of a piece, in degrees either way. Small enough to read easily. */
    private static final double MAX_ROLL_DEGREES = 10.0;
    /** Largest lean of a piece towards or away from the player, in degrees either way. */
    private static final double MAX_LEAN_DEGREES = 18.0;
    /** Closest a piece may sit to the player when something blocks the view, in blocks. */
    private static final double MIN_CLEARANCE = 3.0;
    /** How far a piece may be lifted to clear the ground below it, in blocks. */
    private static final double MAX_LIFT = 6.0;
    /** Bit of the text display style flags that draws a drop shadow. */
    private static final byte FLAG_SHADOW = 1;
    /** Ticks left in the fade when a piece has to make room because another one now covers it. */
    private static final int QUICK_FADE_TICKS = 4;
    /** Free space kept around every piece as seen from the player, as a share of the distance. */
    private static final double MARGIN = 0.025;
    /** Size of one font pixel of a text display at scale 1, in blocks. */
    private static final double PIXEL = 0.025;
    /** Height of one line of text including its shadow, in font pixels. */
    private static final double LINE_PIXELS = 10.0;
    /**
     * Below this the font renderer treats the alpha as unset and draws the text fully opaque, so a
     * fade has to stop here and remove the display instead.
     */
    private static final int MIN_ALPHA = 26;
    /** How long a piece stays fully visible after its line has ended. */
    private static final long HOLD_MS = 600L;
    /** A line before a long instrumental break does not linger for the whole break. */
    private static final long MAX_LINE_MS = 8000L;
    /** Spawning more than this many pieces in one tick means the game lagged; older ones are skipped. */
    private static final int MAX_SPAWNS_PER_TICK = 2;
    /** How far back a seek has to jump before the lyrics rewind with it. */
    private static final long SEEK_BACK_MS = 1500L;

    private static final ExecutorService FETCHER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SpotifyControl lyrics");
        thread.setDaemon(true);
        return thread;
    });

    private record Piece(long timeMs, long endMs, String text) {
    }

    /**
     * One part of a piece: the display that faces the player and its twin that faces the other way,
     * so the words can be read from behind as well. A whole word is one part, a word whose letters
     * move on their own is one part per letter, {@code offset} blocks from the middle of the word.
     * A piece that is drawn over the picture has no displays, only its text and where that sits.
     */
    private static final class Part {
        final Display.TextDisplay front;
        final Display.TextDisplay back;
        final double offset;
        final Component text;
        /** Width of the text in font pixels, and how far its middle is from the middle of the word. */
        final int width;
        final double offsetPixels;
        /** The pose last applied, so a transformation is only sent when the part has moved. */
        LyricsAnimation.Pose lastPose = LyricsAnimation.Pose.REST;
        int lastAlpha = -1;

        Part(Display.TextDisplay front, Display.TextDisplay back, double offset, Component text, int width,
             double offsetPixels) {
            this.front = front;
            this.back = back;
            this.offset = offset;
            this.text = text;
            this.width = width;
            this.offsetPixels = offsetPixels;
        }
    }

    /** A bit of light flying off a word that is drawn over the picture. Places in font pixels, speeds per second. */
    private static final class Fleck {
        final float x;
        final float y;
        final float vx;
        final float vy;
        float age;
        final float life;

        Fleck(float x, float y, float vx, float vy, float life) {
            this.x = x;
            this.y = y;
            this.vx = vx;
            this.vy = vy;
            this.life = life;
        }
    }

    /** One piece: its parts, and the box the text covers. */
    private static final class Placed {
        final List<Part> parts;
        final BlockPos pos;
        final Vec3 center;
        final double halfWidth;
        final double halfHeight;
        /** The direction the word runs in, flat on the ground, for the sparks. */
        final double axisX;
        final double axisZ;
        final long endMs;
        final float scale;
        final float pitch;
        final float roll;
        final double textHalf;
        /** Drawn over the picture instead of as displays, so nothing in the world can be in front of it. */
        final boolean overlay;
        /** Width of the whole word in font pixels. */
        final double widthPixels;
        final List<Fleck> flecks = new ArrayList<>();
        int age;
        /** Ticks into the fade out, or -1 while the piece is still on. */
        int fading = -1;

        Placed(List<Part> parts, BlockPos pos, Spot spot, float pitch, Vec3 axis, long endMs, boolean overlay,
               double widthPixels) {
            Box box = spot.box();
            this.parts = parts;
            this.scale = spot.scale();
            this.pitch = pitch;
            this.roll = spot.roll();
            this.textHalf = spot.textHalfHeight();
            this.pos = pos;
            this.center = box.center();
            this.halfWidth = box.halfWidth();
            this.halfHeight = box.halfHeight();
            this.axisX = axis.x;
            this.axisZ = axis.z;
            this.endMs = endMs;
            this.overlay = overlay;
            this.widthPixels = widthPixels;
        }

        int entities() {
            int total = 0;
            for (Part part : parts) {
                if (part.front != null) {
                    total += 2;
                }
            }
            return total;
        }
    }

    /** The space a piece of text covers: its middle and half its size, in blocks. */
    private record Box(Vec3 center, double halfWidth, double halfHeight) {
    }

    /** A spot that was found for a new piece, with the size the text gets there. */
    private record Spot(Vec3 pos, float scale, float roll, double textHalfHeight, Box box) {
    }

    /**
     * Where the player is and which way they look: the eye and the forward, right and up axes of
     * the view. Overlaps are measured on the screen these axes span.
     */
    private record View(Vec3 eye, Vec3 forward, Vec3 right, Vec3 up) {

        static View of(LocalPlayer player) {
            return of(player.getEyePosition(), player.getYRot(), player.getXRot());
        }

        static View of(Vec3 eye, float yawDegrees, float pitchDegrees) {
            double yaw = Math.toRadians(yawDegrees);
            double pitch = Math.toRadians(pitchDegrees);
            Vec3 forward = new Vec3(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
            Vec3 right = new Vec3(-Math.cos(yaw), 0.0, -Math.sin(yaw));
            return new View(eye, forward, right, right.cross(forward));
        }
    }

    private static final List<Placed> placed = new ArrayList<>();
    private static final Set<BlockPos> occupied = new HashSet<>();
    private static final Random random = new Random();
    private static int nextEntityId = -2_000_000;
    private static int spawnCount;

    private static String trackUri = "";
    private static volatile String loadedUri = "";
    private static volatile List<Piece> pieces = List.of();
    private static int loadedWordsPerPiece;
    private static int cursor = -1;
    private static long lastProgress;
    private static ClientLevel level;
    private static long lastTickNanos;
    private static long lastFrameNanos;
    /** How the pieces are shown: 0 as displays, 1 drawn over the picture. */
    private static int shownMode;
    /** What the game rendered the world with last frame, so the words drawn over it sit where the world is. */
    private static Matrix4f capturedClip;
    private static Vec3 capturedPosition = Vec3.ZERO;
    private static float capturedProjection;
    private static long capturedNanos;
    /** The field of view change of sprinting and the like, for the versions that do not hand over the matrices. */
    private static float fovTarget = 1.0f;
    private static float fovModifier = 1.0f;

    private WorldLyrics() {
    }

    public static void tick(Minecraft client) {
        ClientConfig config = ConfigManager.client();
        if (client.level != level) {
            // A new world or dimension: the old displays were dropped together with the old level.
            placed.clear();
            occupied.clear();
            level = client.level;
        }
        if (!config.worldLyrics) {
            if (!placed.isEmpty()) {
                clear();
            }
            trackUri = "";
            return;
        }
        LocalPlayer player = client.player;
        if (level == null || player == null) {
            return;
        }
        lastTickNanos = System.nanoTime();
        int mode = config.lyricsSeeThrough ? 1 : 0;
        if (mode != shownMode) {
            // What is there goes the way it came, what comes next is shown the new way.
            shownMode = mode;
            fadeAll();
        }
        fovTarget = fovTargetOf(client, player);

        PlaybackState playback = ClientState.session().playback();
        Track track = playback.track();
        long progress = playback.progressMs() + config.lyricsOffsetMillis + trackOffset(config, track.uri());
        if (!track.uri().equals(trackUri) || loadedWordsPerPiece != config.lyricsWordsPerPiece) {
            trackUri = track.uri();
            loadedWordsPerPiece = config.lyricsWordsPerPiece;
            pieces = List.of();
            loadedUri = "";
            cursor = -1;
            fadeAll();
            if (!track.uri().isEmpty() && !track.title().isEmpty()) {
                request(track, Math.max(1, config.lyricsWordsPerPiece));
            }
        }

        if (loadedUri.equals(trackUri) && !pieces.isEmpty() && playback.playing()) {
            List<Piece> current = pieces;
            if (cursor < 0 || progress < lastProgress - SEEK_BACK_MS) {
                // Lyrics that are already past when the track starts or is rewound stay unsung.
                if (cursor >= 0) {
                    fadeAll();
                }
                cursor = firstAtOrAfter(current, progress - 250L);
            }
            lastProgress = progress;

            int due = cursor;
            while (due < current.size() && current.get(due).timeMs() <= progress) {
                due++;
            }
            int from = Math.max(cursor, due - MAX_SPAWNS_PER_TICK);
            for (int i = from; i < due; i++) {
                spawn(player, current.get(i), config);
            }
            cursor = due;
        }

        animate(View.of(player), progress);
    }

    /** The sync offset of one song, set with the lyrics keys. */
    public static int trackOffset(ClientConfig config, String uri) {
        Integer offset = config.lyricsTrackOffsets == null ? null : config.lyricsTrackOffsets.get(uri);
        return offset == null ? 0 : offset;
    }

    /** Moves the lyrics of the playing song by {@code delta} milliseconds, earlier when positive, and remembers it. */
    public static int shiftTrackOffset(String uri, int delta) {
        ClientConfig config = ConfigManager.client();
        if (uri == null || uri.isEmpty()) {
            return 0;
        }
        if (config.lyricsTrackOffsets == null) {
            config.lyricsTrackOffsets = new java.util.LinkedHashMap<>();
        }
        int value = trackOffset(config, uri) + delta;
        config.lyricsTrackOffsets.remove(uri);
        if (value != 0) {
            config.lyricsTrackOffsets.put(uri, value);
        }
        // Songs that have not been played for a long time make room, the file must not grow for ever.
        while (config.lyricsTrackOffsets.size() > 300) {
            config.lyricsTrackOffsets.remove(config.lyricsTrackOffsets.keySet().iterator().next());
        }
        ConfigManager.saveClient();
        return value;
    }

    /** Removes every lyric this client placed, without a fade. */
    public static void clear() {
        if (level != null) {
            for (Placed piece : placed) {
                discard(piece);
            }
        }
        placed.clear();
        occupied.clear();
    }

    private static void fadeAll() {
        for (Placed piece : placed) {
            if (piece.fading < 0) {
                piece.fading = 0;
            }
        }
    }

    private static void discard(Placed piece) {
        for (Part part : piece.parts) {
            if (part.front != null) {
                level.removeEntity(part.front.getId(), Entity.RemovalReason.DISCARDED);
                level.removeEntity(part.back.getId(), Entity.RemovalReason.DISCARDED);
            }
        }
        occupied.remove(piece.pos);
    }

    private static int entityCount() {
        int total = 0;
        for (Placed piece : placed) {
            total += piece.entities();
        }
        return total;
    }

    /** A burst of sparks over the word: end rod particles at random places in its box, drifting a little. */
    private static void sparks(Placed piece, int count) {
        if (piece.overlay) {
            // Drawn over the picture there is no world to put particles in: little lights fly off the word.
            for (int i = 0; i < count; i++) {
                piece.flecks.add(new Fleck((float) ((random.nextDouble() * 2.0 - 1.0) * piece.widthPixels / 2.0),
                        (float) ((random.nextDouble() * 2.0 - 1.0) * LINE_PIXELS / 2.0),
                        (float) ((random.nextDouble() - 0.5) * 24.0), (float) ((random.nextDouble() - 0.6) * 24.0),
                        0.6f + random.nextFloat() * 0.5f));
            }
            return;
        }
        for (int i = 0; i < count; i++) {
            double along = (random.nextDouble() * 2.0 - 1.0) * piece.halfWidth;
            double up = (random.nextDouble() * 2.0 - 1.0) * piece.halfHeight;
            level.addParticle(ParticleTypes.END_ROD, piece.center.x + piece.axisX * along, piece.center.y + up,
                    piece.center.z + piece.axisZ * along, (random.nextDouble() - 0.5) * 0.05,
                    (random.nextDouble() - 0.4) * 0.05, (random.nextDouble() - 0.5) * 0.05);
        }
    }

    /**
     * Keeps pieces from covering each other as seen from where the player is now. New pieces are
     * placed so that they cover nothing, but walking around changes which ones line up, so every
     * tick the older one of any two that touch is faded out quickly.
     */
    private static void separate(View view) {
        int quick = Math.max(0, outTicks(ConfigManager.client()) - QUICK_FADE_TICKS);
        for (int i = 0; i < placed.size(); i++) {
            Placed older = placed.get(i);
            if (older.fading >= quick) {
                continue;
            }
            for (int j = i + 1; j < placed.size(); j++) {
                Placed newer = placed.get(j);
                if (overlaps(view, older.center, older.halfWidth, older.halfHeight,
                        newer.center, newer.halfWidth, newer.halfHeight)) {
                    older.fading = quick;
                    break;
                }
            }
        }
    }

    /**
     * Whether two pieces of text cover each other on the player's screen. Both are projected onto
     * the plane the player looks at, so pieces at different distances that line up count as
     * overlapping too, and the test works the same looking straight ahead, up or down. A piece
     * behind the player covers nothing.
     */
    private static boolean overlaps(View view, Vec3 centerA, double halfWidthA, double halfHeightA,
                                    Vec3 centerB, double halfWidthB, double halfHeightB) {
        Vec3 a = centerA.subtract(view.eye());
        Vec3 b = centerB.subtract(view.eye());
        double depthA = a.dot(view.forward());
        double depthB = b.dot(view.forward());
        if (depthA < 0.5 || depthB < 0.5) {
            return false;
        }
        double xA = a.dot(view.right()) / depthA;
        double yA = a.dot(view.up()) / depthA;
        double xB = b.dot(view.right()) / depthB;
        double yB = b.dot(view.up()) / depthB;
        double spanX = halfWidthA / depthA + halfWidthB / depthB + MARGIN;
        double spanY = halfHeightA / depthA + halfHeightB / depthB + MARGIN;
        return Math.abs(xA - xB) < spanX && Math.abs(yA - yB) < spanY;
    }

    /**
     * Starts the fade of the pieces whose time is up. Which ones that are is chosen in the
     * settings: the ones whose line has been sung, the oldest ones over the kept count, or the
     * ones that have been in the world for the set number of seconds.
     */
    private static void expire(ClientConfig config, long progress) {
        String mode = config.lyricsKeepMode;
        if (!"SECONDS".equals(mode)) {
            int kept = 0;
            int limit = Math.max(1, config.lyricsKept);
            for (int i = placed.size() - 1; i >= 0; i--) {
                Placed piece = placed.get(i);
                if (piece.fading < 0 && ++kept > limit) {
                    piece.fading = 0;
                }
            }
        }
        long lifetimeTicks = Math.max(1, config.lyricsSeconds) * 20L;
        for (Placed piece : placed) {
            if (piece.fading >= 0) {
                continue;
            }
            boolean due = switch (mode) {
                case "SECONDS" -> piece.age >= lifetimeTicks;
                case "COUNT" -> false;
                default -> progress >= piece.endMs + HOLD_MS;
            };
            if (due) {
                piece.fading = 0;
            }
        }
    }

    private static int inTicks(ClientConfig config) {
        int ticks = Math.max(0, config.lyricsFadeInMillis / 50);
        return ticks == 0 ? 0 : Math.max(ticks, LyricsAnimation.of(config.lyricsInStyle).minMillis() / 50);
    }

    private static int outTicks(ClientConfig config) {
        int ticks = Math.max(0, config.lyricsFadeOutMillis / 50);
        return ticks == 0 ? 0 : Math.max(ticks, LyricsAnimation.of(config.lyricsOutStyle).minMillis() / 50);
    }

    /**
     * Runs once per game tick: decides which words are due to fade, counts every word one tick
     * further along and removes the ones that are gone.
     */
    private static void animate(View view, long progress) {
        ClientConfig config = ConfigManager.client();
        separate(view);
        expire(config, progress);
        int out = outTicks(config);
        int in = inTicks(config);
        boolean sparksIn = LyricsAnimation.of(config.lyricsInStyle).sparks();
        boolean sparksOut = LyricsAnimation.of(config.lyricsOutStyle).sparks();
        Iterator<Placed> it = placed.iterator();
        while (it.hasNext()) {
            Placed piece = it.next();
            piece.age++;
            if (piece.fading >= 0) {
                piece.fading++;
                if (piece.fading >= out) {
                    discard(piece);
                    it.remove();
                    continue;
                }
                if (sparksOut) {
                    sparks(piece, piece.fading == 1 ? 18 : 3);
                }
            } else if (sparksIn && piece.age <= Math.max(1, in)) {
                sparks(piece, piece.age == 1 ? 14 : 3);
            }
        }
        // The pose of one tick ahead, which the client reaches by moving there smoothly during the
        // tick: see applyTransform. Setting it only at the tick is why this is smooth at any frame
        // rate, a display entity reads what it was set to once per tick and no more often.
        updateVisuals(1.0f);
    }

    /** How far along a piece is in its appear or disappear animation. */
    private record Phase(LyricsAnimation style, float raw, boolean leaving, float hidden,
                         LyricsAnimation.Pose wordPose) {
    }

    /** Where a piece is in its animation, {@code partial} ticks past its last tick included. */
    private static Phase phase(Placed piece, float partial, ClientConfig config) {
        float in = Math.max(1, inTicks(config));
        float out = Math.max(1, outTicks(config));
        boolean fadesIn = inTicks(config) > 0;
        float raw;
        boolean leaving;
        LyricsAnimation style;
        if (piece.fading >= 0) {
            raw = Math.min(1.0f, (piece.fading + partial) / out);
            leaving = true;
            style = LyricsAnimation.of(config.lyricsOutStyle);
        } else if (fadesIn && piece.age + partial < in) {
            raw = 1.0f - (piece.age + partial) / in;
            leaving = false;
            style = LyricsAnimation.of(config.lyricsInStyle);
        } else {
            raw = 0.0f;
            leaving = false;
            style = null;
        }
        float hidden = LyricsAnimation.ease(raw);
        LyricsAnimation.Pose wordPose = style == null ? LyricsAnimation.Pose.REST : style.pose(leaving, hidden);
        return new Phase(style, raw, leaving, hidden, wordPose);
    }

    /** The state of one part: its own letter when the letters move on their own, the whole word otherwise. */
    private static LyricsAnimation.LetterState partState(Phase phase, int index, int count) {
        if (phase.style() != null && phase.style().perLetter() && count > 1) {
            return LyricsAnimation.letter(phase.leaving(), phase.raw(), index, count);
        }
        return new LyricsAnimation.LetterState(phase.wordPose(), phase.hidden());
    }

    /** Sets opacity and pose of every part of every word in the world from how far along it is. */
    private static void updateVisuals(float partial) {
        ClientConfig config = ConfigManager.client();
        for (Placed piece : placed) {
            if (piece.overlay) {
                continue;
            }
            Phase phase = phase(piece, partial, config);
            int count = piece.parts.size();
            for (int i = 0; i < count; i++) {
                Part part = piece.parts.get(i);
                LyricsAnimation.LetterState state = partState(phase, i, count);
                LyricsAnimation.Pose pose = state.pose();
                int alpha = alphaOf(1.0f - state.hidden());
                if (alpha != part.lastAlpha) {
                    part.lastAlpha = alpha;
                    ((TextDisplayAccess) part.front).spotifycontrol$setTextOpacity((byte) alpha);
                    ((TextDisplayAccess) part.back).spotifycontrol$setTextOpacity((byte) alpha);
                }
                if (!pose.equals(part.lastPose)) {
                    part.lastPose = pose;
                    applyTransform(part.front, piece.scale, piece.textHalf, piece.pitch, piece.roll, pose, false,
                            part.offset);
                    applyTransform(part.back, piece.scale, piece.textHalf, -piece.pitch, -piece.roll, pose, true,
                            -part.offset);
                }
            }
        }
    }

    private static int alphaOf(float t) {
        return Math.round(MIN_ALPHA + (255 - MIN_ALPHA) * Math.max(0.0f, Math.min(1.0f, t)));
    }

    private static void request(Track track, int wordsPerPiece) {
        String uri = track.uri();
        FETCHER.execute(() -> {
            List<LyricsFetcher.Line> lines = LyricsFetcher.fetch(track);
            List<Piece> result = lines == null ? List.of() : split(lines, wordsPerPiece);
            Minecraft.getInstance().execute(() -> {
                if (uri.equals(trackUri)) {
                    pieces = result;
                    loadedUri = uri;
                    if (result.isEmpty()) {
                        SpotifyControl.LOGGER.info("No synced lyrics found for '{}'", track.title());
                    }
                }
            });
        });
    }

    /**
     * Cuts each line into groups of words. A line's groups are spread over the time until the next
     * line, but never slower than a comfortable singing pace, so a line before a long instrumental
     * break does not crawl out one word every few seconds. Every group of a line ends with the line.
     */
    private static List<Piece> split(List<LyricsFetcher.Line> lines, int wordsPerPiece) {
        List<Piece> result = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            LyricsFetcher.Line line = lines.get(i);
            if (line.text().isBlank()) {
                continue;
            }
            String[] words = line.text().trim().split("\\s+");
            long next = i + 1 < lines.size() ? lines.get(i + 1).startMs() : line.startMs() + 4000L;
            long end = Math.min(next, line.startMs() + MAX_LINE_MS);
            long gap = Math.max(0L, end - line.startMs());
            // A line is sung over nearly all of the time until the next one starts. Only a line before
            // a long break is sung at an ordinary pace and not stretched over the whole of it.
            long span = gap <= 7000L ? Math.round(gap * 0.9) : Math.min(gap, words.length * 600L);
            // Long words take longer to sing than short ones, so the words are spread by their length.
            double total = 0.0;
            for (String word : words) {
                total += word.length() + 2;
            }
            double before = 0.0;
            for (int w = 0; w < words.length; w += wordsPerPiece) {
                int last = Math.min(words.length, w + wordsPerPiece);
                String text = String.join(" ", Arrays.copyOfRange(words, w, last));
                long time = line.startMs() + Math.round(span * before / total);
                result.add(new Piece(time, end, text));
                for (int k = w; k < last; k++) {
                    before += words[k].length() + 2;
                }
            }
        }
        return List.copyOf(result);
    }

    private static int firstAtOrAfter(List<Piece> list, long time) {
        int i = 0;
        while (i < list.size() && list.get(i).timeMs() < time) {
            i++;
        }
        return i;
    }

    private static void spawn(LocalPlayer player, Piece piece, ClientConfig config) {
        // Drawn over the picture, the words come after everything else, so that no cloud, creature,
        // water or particle is in front of them. They keep the place in the world they were given.
        boolean overlay = config.lyricsSeeThrough;
        Vec3 eye = player.getEyePosition();
        // Sprinting, on foot or in flight, pushes the lyrics farther ahead so they can be read
        // before the player has run past them.
        int extra = player.isSprinting() ? Math.max(0, config.lyricsSprintExtraDistance) : 0;
        int near = Math.max(2, Math.min(config.lyricsMinDistance, config.lyricsMaxDistance)) + extra;
        int far = Math.max(near, config.lyricsMaxDistance + extra);
        // In a forest, a cave or between houses the lyrics come closer and get smaller, so they
        // fit between the blocks instead of disappearing behind them.
        double crowd = crowding(eye);
        double shrink = 1.0 - 0.6 * crowd;
        near = (int) Math.max(MIN_CLEARANCE, Math.round(near * shrink));
        far = (int) Math.max(near + 2, Math.round(far * shrink));

        // The theme colour alternates between the accent and a lighter shade from word to word,
        // the other colours are what they are, a gradient running along the letters of a word.
        float lighter = spawnCount++ % 2 == 0 ? 0.0f : 0.45f;
        int[] stops = LyricsColor.of(config.lyricsColor).stops(config, lighter);
        Component text = LyricsColor.build(piece.text(), stops,
                LyricsFont.of(config.lyricsFont).apply(Style.EMPTY.withBold(config.lyricsBold)));
        int textPixels = Minecraft.getInstance().font.width(text) + 1;

        float size = (float) (Math.max(0.1f, config.lyricsTextSize / 100.0f) * (1.0 - 0.45 * crowd));
        Spot spot = findSpot(player, eye, near, far, textPixels, size);
        BlockPos pos = BlockPos.containing(spot.pos());

        // Turned towards the player once, then left alone: the text does not follow the camera.
        // A fixed text display with rotation 0 faces south, the opposite of a player with rotation
        // 0, so the angle that makes it face back at the player is the player's angle plus 180.
        // Every piece turns to the player's own heading rather than to the line from the eye, so
        // the words stay level on screen even when the player looks steeply up or down.
        Vec3 middle = spot.box().center();
        float yaw = player.getYRot() + 180.0f;
        // Tipped to face the player up or down as well, so text below or above the eyes is read
        // head on, plus a random lean so not every piece stands exactly upright.
        double across = Math.sqrt((middle.x - eye.x) * (middle.x - eye.x) + (middle.z - eye.z) * (middle.z - eye.z));
        double facing = Math.toDegrees(Math.atan2(middle.y - eye.y, across));
        float pitch = (float) Math.max(-89.0, Math.min(89.0,
                facing + (config.lyricsTilt ? (random.nextDouble() * 2.0 - 1.0) * MAX_LEAN_DEGREES : 0.0)));
        // A word whose letters move on their own is made of one display per letter. Each sits at its
        // own place along the word, worked out from how wide the letters before it are.
        Style base = LyricsFont.of(config.lyricsFont).apply(Style.EMPTY.withBold(config.lyricsBold));
        boolean letters = (LyricsAnimation.of(config.lyricsInStyle).perLetter()
                || LyricsAnimation.of(config.lyricsOutStyle).perLetter())
                && piece.text().codePointCount(0, piece.text().length()) > 1;
        List<Part> parts = new ArrayList<>();
        int totalPixels = textPixels - 1;
        if (letters) {
            int[] points = piece.text().codePoints().toArray();
            Component[] pieces = new Component[points.length];
            int[] widths = new int[points.length];
            int total = 0;
            for (int i = 0; i < points.length; i++) {
                float at = (float) i / (points.length - 1);
                pieces[i] = LyricsColor.build(new String(Character.toChars(points[i])),
                        new int[] {LyricsColor.sample(stops, at)}, base);
                widths[i] = Minecraft.getInstance().font.width(pieces[i]);
                total += widths[i];
            }
            int before = 0;
            totalPixels = total;
            for (int i = 0; i < points.length; i++) {
                double offsetPixels = before + widths[i] / 2.0 - total / 2.0;
                parts.add(makePart(spot, yaw, pitch, pieces[i], offsetPixels * PIXEL * spot.scale(), overlay,
                        widths[i], offsetPixels));
                before += widths[i];
            }
        } else {
            parts.add(makePart(spot, yaw, pitch, text, 0.0, overlay, textPixels - 1, 0.0));
        }

        placed.add(new Placed(parts, pos, spot, pitch, View.of(player).right(), piece.endMs(), overlay,
                totalPixels));
        occupied.add(pos);
        // The kept count fades pieces out gently in expire(). This is only the hard ceiling for
        // the seconds mode with a long time and a fast song, and for words made of many letters.
        while (placed.size() > HARD_LIMIT || (entityCount() > MAX_ENTITIES && placed.size() > 1)) {
            discard(placed.remove(0));
        }
    }

    /** Both displays of one part, with their first pose, added to the world. */
    private static Part makePart(Spot spot, float yaw, float pitch, Component text, double offset, boolean overlay,
                                 int width, double offsetPixels) {
        if (overlay) {
            return new Part(null, null, offset, text, width, offsetPixels);
        }
        // Text is only drawn on its front side. A twin turned the other way makes the words
        // visible from behind as well. Its tilt is mirrored so both lie in the same plane.
        Display.TextDisplay front = display(spot, yaw, pitch, spot.roll(), text);
        applyTransform(front, spot.scale(), spot.textHalfHeight(), pitch, spot.roll(), LyricsAnimation.Pose.REST,
                false, offset);
        Display.TextDisplay back = display(spot, yaw + 180.0f, -pitch, -spot.roll(), text);
        applyTransform(back, spot.scale(), spot.textHalfHeight(), -pitch, -spot.roll(), LyricsAnimation.Pose.REST,
                true, -offset);
        level.addEntity(front);
        level.addEntity(back);
        return new Part(front, back, offset, text, width, offsetPixels);
    }

    private static Display.TextDisplay display(Spot spot, float yaw, float pitch, float roll, Component text) {
        Display.TextDisplay display = new Display.TextDisplay(EntityType.TEXT_DISPLAY, level);
        display.setId(nextEntityId--);
        display.setPos(spot.pos().x, spot.pos().y, spot.pos().z);
        display.setYRot(yaw);
        display.yRotO = yaw;
        display.setXRot(0.0f);
        display.xRotO = 0.0f;

        TextDisplayAccess textAccess = (TextDisplayAccess) display;
        textAccess.spotifycontrol$setText(text);
        textAccess.spotifycontrol$setTextOpacity((byte) MIN_ALPHA);
        display.getEntityData().set(TextDisplayAccess.spotifycontrol$backgroundColorId(), 0);
        display.getEntityData().set(TextDisplayAccess.spotifycontrol$styleFlagsId(),
                FLAG_SHADOW);

        DisplayAccess access = (DisplayAccess) display;
        access.spotifycontrol$setBillboard(Display.BillboardConstraints.FIXED);
        access.spotifycontrol$setBrightness(new Brightness(15, 15));
        access.spotifycontrol$setViewRange(4.0f);
        return display;
    }

    /**
     * Sets the size, the tilt and the position of a piece's text. The text is tipped up or down
     * and tilted a little to the side, both around its middle. A text display turns around its
     * position, which is the bottom of the text, so the shift moves the middle back to where it
     * was and the piece stays where the overlap check put it. The pose adds the movement of the
     * appear and disappear animation on top: it scales and turns the text around its middle and
     * moves it up and to the side, in heights of the text.
     *
     * <p>The twin that faces the other way has its own left and its own front, so the sideways
     * move and the spin run the other way there to cover the same space. Turning around the
     * vertical axis is the same for both.</p>
     */
    private static void applyTransform(Display.TextDisplay display, float scale, double textHalf, float pitchDegrees,
                                       float roll, LyricsAnimation.Pose pose, boolean back, double offset) {
        float half = (float) textHalf;
        float line = half * 2.0f;
        float spin = back ? -pose.spin() : pose.spin();
        float slide = (back ? -pose.slide() : pose.slide()) * line;
        // How the whole word stands: tipped and tilted. A letter's place along the word follows it.
        Quaternionf stand = new Quaternionf().rotationX((float) Math.toRadians(pitchDegrees)).rotateZ(roll);
        Quaternionf turn = new Quaternionf(stand).rotateZ(spin).rotateY(pose.flip());
        Vector3f scaledMiddle = new Vector3f(0.0f, half * pose.scale(), 0.0f);
        Vector3f along = stand.transform(new Vector3f((float) offset, 0.0f, 0.0f));
        Vector3f shift = new Vector3f(slide, half + pose.lift() * line, 0.0f).add(along)
                .sub(turn.transform(scaledMiddle));
        float size = scale * pose.scale();
        DisplayAccess access = (DisplayAccess) display;
        access.spotifycontrol$setTransformation(Platform.transformation(shift, turn, new Vector3f(size, size, size)));
        // Moves to the new pose over the one tick until the next update, starting now. The client
        // shows the stretch in between at the frame rate. The delay has to be set every time: that
        // setter always counts as a change, which is what restarts the movement.
        access.spotifycontrol$setInterpolationDuration(1);
        access.spotifycontrol$setInterpolationDelay(0);
    }

    /**
     * A random spot ahead of the player, spread in distance, height and to the sides. It has to be
     * in a block that is air and holds no other lyric, and the text there must not cover any other
     * piece as seen from the player. When the view is too full for that, the pieces in the way are
     * removed at once, so two pieces never overlap.
     */
    private static Spot findSpot(LocalPlayer player, Vec3 eye, int near, int far, int textPixels,
                                 float size) {
        View view = View.of(player);
        double yawRad = Math.toRadians(player.getYRot());
        // Aimed along the line of sight, so the text appears where the player is looking rather
        // than at a fixed height. Steep looks are flattened, or every piece would end up in the
        // ground or the sky.
        double pitchRad = Math.toRadians(Math.max(-75.0f, Math.min(75.0f, player.getXRot())));
        double flat = Math.cos(pitchRad);
        double forwardX = -Math.sin(yawRad) * flat;
        double forwardY = -Math.sin(pitchRad);
        double forwardZ = Math.cos(yawRad) * flat;
        double rightX = -Math.cos(yawRad);
        double rightZ = -Math.sin(yawRad);
        Spot first = null;
        for (int attempt = 0; attempt < 80; attempt++) {
            // The spread widens a little with every failed attempt so a crowded view still finds room.
            double widen = 1.0 + attempt * 0.03;
            double ahead = near + random.nextDouble() * (far - near);
            // The sideways and vertical spread grow with the distance, so far pieces fill the view
            // as much as near ones instead of lining up in a narrow column.
            double spread = Math.max(1.0, ahead / REFERENCE_DISTANCE) * widen;
            double side = (random.nextDouble() * 2.0 - 1.0) * 3.5 * spread;
            // Spread evenly above and below the line of sight, so the middle of the lyrics sits where
            // the player looks and nobody has to look up to read them.
            double up = (random.nextDouble() * 2.0 - 1.0) * 1.4 * spread;
            Vec3 wanted = new Vec3(eye.x + forwardX * ahead + rightX * side,
                    eye.y + forwardY * ahead + up,
                    eye.z + forwardZ * ahead + rightZ * side);
            Vec3 center = clearView(eye, wanted);
            if (center == null) {
                continue;
            }
            Spot spot = measure(eye, center, textPixels, size);
            spot = lifted(spot, rightX, rightZ);
            if (spot == null) {
                continue;
            }
            if (first == null) {
                first = spot;
            }
            BlockPos block = BlockPos.containing(spot.box().center());
            if (!occupied.contains(block) && conflicts(view, spot).isEmpty()) {
                return spot;
            }
        }
        if (first == null) {
            // Walled in on every side: the piece goes straight ahead at the closest distance.
            first = measure(eye, eye.add(forwardX * MIN_CLEARANCE, forwardY * MIN_CLEARANCE,
                    forwardZ * MIN_CLEARANCE), textPixels, size);
        }
        for (Placed blocking : conflicts(view, first)) {
            discard(blocking);
            placed.remove(blocking);
        }
        return first;
    }

    private static boolean solid(Vec3 point) {
        return !level.getBlockState(BlockPos.containing(point)).isAir();
    }

    /**
     * Walks from the eye towards the wanted point. When a block is in the way, the piece moves to
     * just in front of it, as long as that is not right in the player's face.
     */
    private static Vec3 clearView(Vec3 eye, Vec3 wanted) {
        Vec3 path = wanted.subtract(eye);
        double length = path.length();
        if (length < 1.0E-3) {
            return null;
        }
        Vec3 step = path.scale(1.0 / length);
        for (double t = 0.5; t <= length; t += 0.4) {
            if (solid(eye.add(step.scale(t)))) {
                double back = t - 0.8;
                return back < MIN_CLEARANCE ? null : eye.add(step.scale(back));
            }
        }
        return wanted;
    }

    /**
     * In open air the text stays where it was aimed. When its lower edge or its middle would sit in
     * the ground or in a block, it is lifted in half block steps until it is clear.
     */
    private static Spot lifted(Spot spot, double rightX, double rightZ) {
        Box box = spot.box();
        for (double lift = 0.0; lift <= MAX_LIFT; lift += 0.5) {
            Vec3 center = box.center().add(0.0, lift, 0.0);
            if (clear(center, box.halfWidth(), box.halfHeight(), rightX, rightZ)) {
                if (lift == 0.0) {
                    return spot;
                }
                Vec3 up = new Vec3(0.0, lift, 0.0);
                return new Spot(spot.pos().add(up), spot.scale(), spot.roll(), spot.textHalfHeight(),
                        new Box(center, box.halfWidth(), box.halfHeight()));
            }
        }
        return null;
    }

    private static boolean clear(Vec3 center, double halfWidth, double halfHeight, double rightX, double rightZ) {
        for (int i = -2; i <= 2; i++) {
            double across = halfWidth * i / 2.0;
            double x = center.x + rightX * across;
            double z = center.z + rightZ * across;
            if (solid(new Vec3(x, center.y, z)) || solid(new Vec3(x, center.y - halfHeight + 0.1, z))) {
                return false;
            }
        }
        return true;
    }

    /**
     * How closely the space around the player is filled with blocks above the ground, from 0 in
     * the open to 1 in a dense forest or a cave.
     */
    private static double crowding(Vec3 eye) {
        int samples = 48;
        int filled = 0;
        for (int i = 0; i < samples; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double radius = 1.5 + random.nextDouble() * 6.5;
            double height = -0.5 + random.nextDouble() * 5.0;
            if (solid(eye.add(Math.cos(angle) * radius, height, Math.sin(angle) * radius))) {
                filled++;
            }
        }
        return Math.min(1.0, filled / (samples * 0.35));
    }

    /** Sizes a piece whose text is to be centred on {@code center}. */
    private static Spot measure(Vec3 eye, Vec3 center, int textPixels, float size) {
        // Farther pieces are drawn larger, so text 15 blocks away stays as readable as text at 6.
        float reach = (float) Math.max(1.0, eye.distanceTo(center) / REFERENCE_DISTANCE);
        float scale = (MIN_SCALE + random.nextFloat() * (MAX_SCALE - MIN_SCALE)) * reach * size;
        double halfWidth = textPixels * PIXEL * scale / 2.0;
        double halfHeight = LINE_PIXELS * PIXEL * scale / 2.0;
        // A text display draws its text above its position, centred to the sides, so the entity
        // goes half a line below the point the text should be centred on.
        Vec3 pos = center.add(0.0, -halfHeight, 0.0);
        // A slight sideways tilt, so the words do not all sit level, but never so much that they
        // get hard to read. The box grows to cover the tilted text.
        float roll = ConfigManager.client().lyricsTilt
                ? (float) Math.toRadians((random.nextDouble() * 2.0 - 1.0) * MAX_ROLL_DEGREES)
                : 0.0f;
        double cos = Math.abs(Math.cos(roll));
        double sin = Math.abs(Math.sin(roll));
        Box box = new Box(center, cos * halfWidth + sin * halfHeight, sin * halfWidth + cos * halfHeight);
        return new Spot(pos, scale, roll, halfHeight, box);
    }

    private static List<Placed> conflicts(View view, Spot spot) {
        List<Placed> found = new ArrayList<>();
        Box box = spot.box();
        for (Placed piece : placed) {
            if (overlaps(view, piece.center, piece.halfWidth, piece.halfHeight,
                    box.center(), box.halfWidth(), box.halfHeight())) {
                found.add(piece);
            }
        }
        return found;
    }

    // ------------------------------------------------------------- drawn over the picture

    /** Called by the game version's world render with what it rendered the world with this frame. */
    public static void captureWorldView(Matrix4f clip, Vec3 cameraPosition, float projection) {
        capturedClip = clip;
        capturedPosition = cameraPosition;
        capturedProjection = projection;
        capturedNanos = System.nanoTime();
    }

    /**
     * What the field of view does on top of the setting while the player sprints, flies or is
     * hasted: the same sum the game does. Only used where the game's own matrices are not handed over.
     */
    private static float fovTargetOf(Minecraft client, LocalPlayer player) {
        float modifier = 1.0f;
        if (player.getAbilities().flying) {
            modifier *= 1.1f;
        }
        double walking = player.getAbilities().getWalkingSpeed();
        modifier *= (float) ((player.getAttributeValue(Attributes.MOVEMENT_SPEED) / walking + 1.0) / 2.0);
        if (walking == 0.0 || Float.isNaN(modifier) || Float.isInfinite(modifier)) {
            modifier = 1.0f;
        }
        double effect = client.options.fovEffectScale().get();
        return (float) (1.0 + (modifier - 1.0) * effect);
    }

    /**
     * Draws the pieces that are shown over the picture, after the world and everything in it. Runs
     * every frame, not every tick, so they move as smoothly as the game does, and projects them with
     * the very matrices the world was drawn with, so they stay where they are in the world.
     */
    public static void renderOverlay(GuiGraphics graphics) {
        Minecraft client = Minecraft.getInstance();
        if (placed.isEmpty() || client.level == null || client.level != level || client.player == null
                || client.options.hideGui) {
            return;
        }
        boolean any = false;
        for (Placed piece : placed) {
            if (piece.overlay) {
                any = true;
                break;
            }
        }
        if (!any) {
            return;
        }
        ClientConfig config = ConfigManager.client();
        Font font = client.font;
        int guiWidth = Math.max(1, client.getWindow().getGuiScaledWidth());
        int guiHeight = Math.max(1, client.getWindow().getGuiScaledHeight());
        long now = System.nanoTime();
        float partial = Math.max(0.0f, Math.min(1.0f, (now - lastTickNanos) / 50_000_000.0f));
        float frame = lastFrameNanos == 0L ? 0.0f : Math.min(0.1f, (now - lastFrameNanos) / 1_000_000_000.0f);
        lastFrameNanos = now;

        Matrix4f clip;
        Vec3 camera;
        float projection;
        if (capturedClip != null && now - capturedNanos < 200_000_000L) {
            clip = capturedClip;
            camera = capturedPosition;
            projection = capturedProjection;
        } else {
            // No matrices from the game: rebuilt from where the player looks and the field of view, which
            // gets close but cannot know about the sway of walking.
            fovModifier += (fovTarget - fovModifier) * (1.0f - (float) Math.pow(0.5, frame * 20.0));
            LocalPlayer player = client.player;
            double fov = Math.toRadians(client.options.fov().get() * fovModifier);
            double aspect = (double) client.getWindow().getWidth() / Math.max(1, client.getWindow().getHeight());
            projection = (float) (1.0 / Math.tan(fov / 2.0));
            clip = new Matrix4f().perspective((float) fov, (float) aspect, 0.05f, 1000.0f)
                    .rotateX((float) Math.toRadians(player.getViewXRot(partial)))
                    .rotateY((float) Math.toRadians(player.getViewYRot(partial) + 180.0f));
            camera = player.getEyePosition(partial);
        }

        Vector4f point = new Vector4f();
        for (Placed piece : placed) {
            if (!piece.overlay) {
                continue;
            }
            Vec3 rel = piece.center.subtract(camera);
            point.set((float) rel.x, (float) rel.y, (float) rel.z, 1.0f);
            clip.transform(point);
            if (point.w < 0.4f) {
                continue;
            }
            double centerX = (point.x / point.w + 1.0) / 2.0 * guiWidth;
            double centerY = (1.0 - point.y / point.w) / 2.0 * guiHeight;
            double pixel = PIXEL * piece.scale * (guiHeight / 2.0) * projection / point.w;
            if (centerX < -piece.widthPixels * pixel || centerX > guiWidth + piece.widthPixels * pixel
                    || centerY < -guiHeight * 0.3 || centerY > guiHeight * 1.3) {
                continue;
            }
            drawPiece(graphics, font, piece, centerX, centerY, (float) pixel, phase(piece, partial, config));
            drawFlecks(graphics, piece, centerX, centerY, (float) pixel, frame);
        }
    }

    /** One piece drawn the way its displays would be: each part in its own place of its animation. */
    private static void drawPiece(GuiGraphics graphics, Font font, Placed piece, double centerX, double centerY,
                                  float pixel, Phase phase) {
        float lineHeight = (float) LINE_PIXELS * pixel;
        double cos = Math.cos(piece.roll);
        double sin = Math.sin(piece.roll);
        int count = piece.parts.size();
        for (int i = 0; i < count; i++) {
            Part part = piece.parts.get(i);
            LyricsAnimation.LetterState state = partState(phase, i, count);
            float alpha = 1.0f - state.hidden();
            if (alpha < 0.06f) {
                continue;
            }
            LyricsAnimation.Pose pose = state.pose();
            double along = part.offsetPixels * pixel + pose.slide() * lineHeight;
            double lift = -pose.lift() * lineHeight;
            float flip = Math.max(0.03f, (float) Math.abs(Math.cos(pose.flip())));
            Platform.pushTransform(graphics, (float) (centerX + along * cos - lift * sin),
                    (float) (centerY + along * sin + lift * cos), piece.roll + pose.spin(),
                    pixel * pose.scale() * flip, pixel * pose.scale());
            Platform.drawText(graphics, font, part.text, -part.width / 2, -4, Theme.alpha(0xFFFFFFFF, alpha), true);
            Platform.popMatrix(graphics);
        }
    }

    /** The little lights of the sparks animation: they drift, fade and are gone. */
    private static void drawFlecks(GuiGraphics graphics, Placed piece, double centerX, double centerY, float pixel,
                                   float frame) {
        Iterator<Fleck> it = piece.flecks.iterator();
        while (it.hasNext()) {
            Fleck fleck = it.next();
            fleck.age += frame;
            if (fleck.age >= fleck.life) {
                it.remove();
                continue;
            }
            int x = (int) Math.round(centerX + (fleck.x + fleck.vx * fleck.age) * pixel);
            int y = (int) Math.round(centerY + (fleck.y + fleck.vy * fleck.age) * pixel);
            float fade = 1.0f - fleck.age / fleck.life;
            graphics.fill(x, y, x + 2, y + 2, Theme.alpha(0xFFFFFFFF, Math.max(0.06f, fade)));
        }
    }
}
