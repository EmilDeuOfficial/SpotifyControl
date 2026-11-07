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
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
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
    private static final int FADE_IN_TICKS = 5;
    private static final int FADE_OUT_TICKS = 20;
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
     * One piece in the world: the display that faces the player, its twin that faces the other way
     * so the words can be read from behind as well, and the box the text covers.
     */
    private static final class Placed {
        final Display.TextDisplay front;
        final Display.TextDisplay back;
        final BlockPos pos;
        final Vec3 center;
        final double halfWidth;
        final double halfHeight;
        final long endMs;
        int age;
        /** Ticks into the fade out, or -1 while the piece is still on. */
        int fading = -1;

        Placed(Display.TextDisplay front, Display.TextDisplay back, BlockPos pos, Box box, long endMs) {
            this.front = front;
            this.back = back;
            this.pos = pos;
            this.center = box.center();
            this.halfWidth = box.halfWidth();
            this.halfHeight = box.halfHeight();
            this.endMs = endMs;
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
            double yaw = Math.toRadians(player.getYRot());
            double pitch = Math.toRadians(player.getXRot());
            Vec3 forward = new Vec3(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
            Vec3 right = new Vec3(-Math.cos(yaw), 0.0, -Math.sin(yaw));
            return new View(player.getEyePosition(), forward, right, right.cross(forward));
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

        PlaybackState playback = ClientState.session().playback();
        Track track = playback.track();
        long progress = playback.progressMs();
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
        level.removeEntity(piece.front.getId(), Entity.RemovalReason.DISCARDED);
        level.removeEntity(piece.back.getId(), Entity.RemovalReason.DISCARDED);
        occupied.remove(piece.pos);
    }

    /**
     * Keeps pieces from covering each other as seen from where the player is now. New pieces are
     * placed so that they cover nothing, but walking around changes which ones line up, so every
     * tick the older one of any two that touch is faded out quickly.
     */
    private static void separate(View view) {
        for (int i = 0; i < placed.size(); i++) {
            Placed older = placed.get(i);
            if (older.fading >= FADE_OUT_TICKS - QUICK_FADE_TICKS) {
                continue;
            }
            for (int j = i + 1; j < placed.size(); j++) {
                Placed newer = placed.get(j);
                if (overlaps(view, older.center, older.halfWidth, older.halfHeight,
                        newer.center, newer.halfWidth, newer.halfHeight)) {
                    older.fading = FADE_OUT_TICKS - QUICK_FADE_TICKS;
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

    private static void animate(View view, long progress) {
        separate(view);
        expire(ConfigManager.client(), progress);
        Iterator<Placed> it = placed.iterator();
        while (it.hasNext()) {
            Placed piece = it.next();
            piece.age++;
            int alpha;
            if (piece.fading >= 0) {
                piece.fading++;
                if (piece.fading >= FADE_OUT_TICKS) {
                    discard(piece);
                    it.remove();
                    continue;
                }
                alpha = lerpAlpha(1.0f - (float) piece.fading / FADE_OUT_TICKS);
            } else if (piece.age <= FADE_IN_TICKS) {
                alpha = lerpAlpha((float) piece.age / FADE_IN_TICKS);
            } else {
                alpha = 255;
            }
            ((TextDisplayAccess) piece.front).spotifycontrol$setTextOpacity((byte) alpha);
            ((TextDisplayAccess) piece.back).spotifycontrol$setTextOpacity((byte) alpha);
        }
    }

    private static int lerpAlpha(float t) {
        float eased = t * t * (3.0f - 2.0f * t);
        return Math.round(MIN_ALPHA + (255 - MIN_ALPHA) * Math.max(0.0f, Math.min(1.0f, eased)));
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
            long span = Math.max(0L, Math.min(end - line.startMs(), words.length * 450L));
            for (int w = 0; w < words.length; w += wordsPerPiece) {
                int last = Math.min(words.length, w + wordsPerPiece);
                String text = String.join(" ", Arrays.copyOfRange(words, w, last));
                long time = line.startMs() + span * w / words.length;
                result.add(new Piece(time, end, text));
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

        int accent = Theme.accent();
        int color = spawnCount++ % 2 == 0 ? accent : Theme.mix(accent, 0xFFFFFFFF, 0.45f);
        Component text = Component.literal(piece.text())
                .withStyle(Style.EMPTY.withColor(color & 0xFFFFFF).withBold(true));
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
        Display.TextDisplay front = display(spot, yaw, pitch, spot.roll(), text);
        // Text is only drawn on its front side. A twin turned the other way makes the words
        // visible from behind as well. Its tilt is mirrored so both lie in the same plane.
        Display.TextDisplay back = display(spot, yaw + 180.0f, -pitch, -spot.roll(), text);
        level.addEntity(front);
        level.addEntity(back);

        placed.add(new Placed(front, back, pos, spot.box(), piece.endMs()));
        occupied.add(pos);
        // The kept count fades pieces out gently in expire(). This is only the hard ceiling for
        // the seconds mode with a long time and a fast song.
        while (placed.size() > HARD_LIMIT) {
            discard(placed.remove(0));
        }
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
        display.getEntityData().set(TextDisplayAccess.spotifycontrol$styleFlagsId(), FLAG_SHADOW);

        DisplayAccess access = (DisplayAccess) display;
        access.spotifycontrol$setBillboard(Display.BillboardConstraints.FIXED);
        access.spotifycontrol$setBrightness(new Brightness(15, 15));
        access.spotifycontrol$setViewRange(4.0f);
        float scale = spot.scale();
        // The text is tipped up or down and tilted a little to the side, both around its middle.
        // A text display turns around its position, which is the bottom of the text, so the shift
        // moves the middle back to where it was and the piece stays where the overlap check put it.
        float half = (float) spot.textHalfHeight();
        Quaternionf turn = new Quaternionf().rotationX((float) Math.toRadians(pitch)).rotateZ(roll);
        Vector3f middle = new Vector3f(0.0f, half, 0.0f);
        Vector3f shift = new Vector3f(middle).sub(turn.transform(new Vector3f(middle)));
        access.spotifycontrol$setTransformation(Platform.transformation(shift, turn,
                new Vector3f(scale, scale, scale)));
        return display;
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
}
