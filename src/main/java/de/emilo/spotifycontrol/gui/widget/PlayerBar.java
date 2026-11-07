package de.emilo.spotifycontrol.gui.widget;

import de.emilo.spotifycontrol.client.ClientState;
import de.emilo.spotifycontrol.config.ClientConfig;
import de.emilo.spotifycontrol.config.ConfigManager;
import de.emilo.spotifycontrol.config.Lang;
import de.emilo.spotifycontrol.gui.Anim;
import de.emilo.spotifycontrol.gui.Icons;
import de.emilo.spotifycontrol.platform.Platform;
import de.emilo.spotifycontrol.gui.Theme;
import de.emilo.spotifycontrol.spotify.SpotifySession;
import de.emilo.spotifycontrol.spotify.model.Json;
import de.emilo.spotifycontrol.spotify.model.PlaybackState;
import de.emilo.spotifycontrol.spotify.model.Track;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.Objects;

public class PlayerBar {

    public static final int HEIGHT = 58;

    private int barX;
    private int barY;
    private int barWidth;

    private int progressX;
    private int progressY;
    private int progressWidth;

    private int volumeX;
    private int volumeY;
    private int volumeWidth;

    private int centerX;
    private int controlsY;
    private int heartY;

    /** How long a value the user just set keeps winning over the polled state. */
    private static final long PENDING_HOLD_MILLIS = 4000L;

    /** Hit area height, chosen so the knob is never outside the area that keeps it visible. */
    private static final int SLIDER_HIT_HEIGHT = 13;

    /** Volume is sent at most this often while dragging; seeking is sent on release only. */
    private static final long VOLUME_SEND_INTERVAL_MILLIS = 150L;

    /** Placed far off screen so a hidden control can never be hit. */
    private static final int HIDDEN = Integer.MIN_VALUE / 4;

    /** Radius a slider knob reaches once it has fully grown in. */
    private static final float KNOB_RADIUS = 5.0f;

    /** How long a cover that has just loaded takes to fade out of its placeholder. */
    private static final long ART_FADE_MILLIS = 250L;

    // Where the artist line came out, so a click can be measured against what was drawn.
    private int artistX = HIDDEN;
    private int artistY;
    private int artistWidth;

    private int deviceX = HIDDEN;
    private int queueX = HIDDEN;
    private int lyricsX = HIDDEN;
    private int heartX = HIDDEN;
    private int speakerX = HIDDEN;

    /** Volume to come back to when the speaker is clicked a second time. */
    private int mutedVolume = -1;

    /** The cover the bar drew last, so the frame a new one turns up can be recognised. */
    private String lastArtworkUrl;

    /** When that cover turned up, which is where its fade counts from. */
    private long artworkArrivedAt;

    /** X of the device button, or -1 while it is switched off. */
    public int deviceButtonX() {
        return deviceX == HIDDEN ? -1 : deviceX;
    }

    private boolean draggingProgress;
    private boolean draggingVolume;

    private int pendingVolume = -1;
    private long pendingVolumeUntil;
    private long lastVolumeSentMillis;

    private float pendingProgress = -1.0f;
    private long pendingProgressUntil;

    /** Last volume actually sent, so releasing after a drag does not repeat it. */
    private int sentVolume = -1;

    public void render(GuiGraphics graphics, int x, int y, int width, int mouseX, int mouseY) {
        Font font = Minecraft.getInstance().font;
        SpotifySession session = ClientState.session();
        PlaybackState state = session.playback();
        Track track = state.track();

        barX = x;
        barY = y;
        barWidth = width;

        Theme.rounded(graphics, x, y, width, HEIGHT, Theme.PANEL_RADIUS, Theme.SURFACE,
                Theme.CORNERS_BOTTOM);
        graphics.fill(x, y, x + width, y + 1, Theme.BORDER);

        int artSize = 42;
        int artX = x + 10;
        int artY = y + (HEIGHT - artSize) / 2;
        String artworkUrl = track.artworkUrl();
        Object art = ClientState.artwork().get(artworkUrl, artSize);
        // A cover arrives several frames after the track it belongs to. Counting from the frame it
        // turned up, rather than from the track change, is what lets the picture fade out of the
        // placeholder instead of replacing it between two frames.
        if (art != null && !Objects.equals(artworkUrl, lastArtworkUrl)) {
            lastArtworkUrl = artworkUrl;
            artworkArrivedAt = System.currentTimeMillis();
        }
        if (art != null) {
            float arrived = artworkArrival();
            boolean arriving = arrived < 1.0f;
            // The cover no longer grows in on top of the fade. It is one downloaded picture, scaled
            // to this size once and cached under it, so drawing it at any other size is that picture
            // resampled, and asking the cache for another size starts a download per frame.
            Platform.drawTexture(graphics, art, artX, artY, artSize, artSize);
            if (arriving) {
                // A texture carries no alpha of its own, so the placeholder grey is painted over the
                // cover and drained away instead. It goes on before the mask, which then cuts the
                // corners out of picture and grey together.
                graphics.fill(artX, artY, artX + artSize, artY + artSize,
                        Theme.alpha(Theme.ELEVATED, 1.0f - arrived));
            }
            Theme.roundedMask(graphics, artX, artY, artSize, artSize, 5, Theme.SURFACE);
        } else {
            Theme.rounded(graphics, artX, artY, artSize, artSize, 5, Theme.ELEVATED);
            Icons.note(graphics, artX + artSize / 2, artY + artSize / 2, 18, Theme.TEXT_DIM);
        }

        int infoX = artX + artSize + 10;
        int infoWidth = Math.max(40, width / 4 - 60);
        String title = track.isEmpty() ? Lang.plain("gui.no_device") : track.title();
        Theme.scrollingText(graphics, font, title, infoX, y + 18, infoWidth, Theme.TEXT, "bar.title");
        // The same line the listings draw, from the same layout, so a name sits in exactly one
        // place whichever of the two is showing it.
        artistX = infoX;
        artistY = y + 31;
        artistWidth = infoWidth;
        ArtistLine.render(graphics, font, track, "bar.artist:", artistX, artistY, artistWidth,
                Theme.TEXT_MUTED, mouseX, mouseY, artistY, true);

        ClientConfig config = ConfigManager.client();
        heartY = y + HEIGHT / 2;
        if (config.showLikeButton) {
            heartX = infoX + infoWidth + 12;
            boolean heartHovered = Theme.hovered(mouseX, mouseY, heartX - 8, heartY - 8, 16, 16);
            boolean liked = session.isLiked(track.id());
            // Being liked has to be reachable from either of the two hover colours, so the green is
            // mixed in on top of them rather than replacing them.
            float likedAmount = Anim.to("bar.likeOn", liked ? 1.0f : 0.0f, Anim.FAST);
            // Both colours end up inside Icons.heart, which is rasterized once per colour and kept
            // under it, so the fade is snapped to steps before it is allowed that far.
            iconButton("bar.like", heartHovered,
                    Anim.mix(Theme.TEXT_MUTED, Theme.GREEN, likedAmount),
                    Anim.mix(Theme.GREEN_BRIGHT, Theme.GREEN, likedAmount), 10,
                    (color, size) -> Icons.heart(graphics, heartX, heartY, size, liked, color));
        } else {
            heartX = HIDDEN;
        }

        centerX = x + width / 2;
        controlsY = y + 22;

        if (config.showShuffleRepeat) {
            // The green of a toggle that has just been switched on is faded in, so the eye can carry
            // it back to the button that was pressed instead of finding it already there.
            float shuffleOn = Anim.to("bar.shuffleOn", state.shuffle() ? 1.0f : 0.0f, Anim.FAST);
            int shuffleIdle = Anim.mix(Theme.TEXT_MUTED, Theme.GREEN, shuffleOn);
            iconButton("bar.shuffle", within(mouseX, mouseY, centerX - 58, controlsY, 12), shuffleIdle,
                    whenMoving(shuffleIdle, Anim.mix(Theme.TEXT, Theme.GREEN_BRIGHT, shuffleOn)), 12,
                    (color, size) -> Icons.shuffle(graphics, centerX - 58, controlsY, size, color));
        }
        iconButton("bar.prev", within(mouseX, mouseY, centerX - 32, controlsY, 12),
                whenMoving(Theme.TEXT, Theme.TEXT_MUTED), Theme.TEXT, 12,
                (color, size) -> Icons.previous(graphics, centerX - 32, controlsY, size, color));

        boolean playHovered = within(mouseX, mouseY, centerX, controlsY, 15);
        float playHover = Anim.hover("bar.play", playHovered);
        // The circle steps from 13 to 14 under the cursor, the way it always has, and the glyph on
        // it steps with it. One number decides for both, multiplied by whatever each of them counts
        // in - a radius is rasterized at twice itself, an icon is rounded to an even size - because
        // asking the two separately let them cross their thresholds a frame or two apart and the
        // button came apart while it moved.
        boolean animated = Anim.enabled();
        int playStep = animated ? Anim.growSteps(playHover, 1) - Anim.pressStep("bar.play") : 0;
        int playRadius = (animated || !playHovered ? 13 : 14) + playStep;
        int pauseSize = 12 + playStep * Anim.ICON_STEP;
        int playSize = 12 + playStep * Anim.ICON_STEP;
        Theme.circle(graphics, centerX, controlsY, playRadius, Theme.TEXT);
        // Play and pause trade places by alpha, so the button does not blink when playback is
        // toggled. Whichever of the two has faded out is skipped, which is also what keeps them off
        // each other once the crossfade is over.
        float playingAmount = Anim.to("bar.playing", state.playing() ? 1.0f : 0.0f, Anim.FAST);
        if (playingAmount > 0.0f) {
            Icons.pause(graphics, centerX, controlsY, pauseSize, Anim.fade(Theme.BLACK, playingAmount));
        }
        if (playingAmount < 1.0f) {
            Icons.play(graphics, centerX + 1, controlsY, playSize, Anim.fade(Theme.BLACK, 1.0f - playingAmount));
        }

        iconButton("bar.next", within(mouseX, mouseY, centerX + 32, controlsY, 12),
                whenMoving(Theme.TEXT, Theme.TEXT_MUTED), Theme.TEXT, 12,
                (color, size) -> Icons.next(graphics, centerX + 32, controlsY, size, color));
        if (config.showShuffleRepeat) {
            float repeatOn = Anim.to("bar.repeatOn", "off".equals(state.repeatMode()) ? 0.0f : 1.0f, Anim.FAST);
            int repeatIdle = Anim.mix(Theme.TEXT_MUTED, Theme.GREEN, repeatOn);
            iconButton("bar.repeat", within(mouseX, mouseY, centerX + 58, controlsY, 12), repeatIdle,
                    whenMoving(repeatIdle, Anim.mix(Theme.TEXT, Theme.GREEN_BRIGHT, repeatOn)), 12,
                    (color, size) -> Icons.repeat(graphics, centerX + 58, controlsY, size, color));
        }

        progressWidth = Math.min(300, width / 3);
        progressX = centerX - progressWidth / 2;
        progressY = y + HEIGHT - 12;
        float fraction = displayProgress(state, track);
        boolean progressHovered = overProgress(mouseX, mouseY);
        int trackTop = progressY;
        int knobY = trackTop + Theme.SLIDER_HEIGHT / 2;
        boolean progressActive = progressHovered || draggingProgress;
        // The knob used to be there or not be there at all. Growing it out of the bar ties it to the
        // cursor that called it up, and the fill turns green over the same short moment.
        float progressKnob = Anim.hover("bar.progressKnob", progressActive);
        int filled = Theme.slider(graphics, progressX, trackTop, progressWidth, Theme.SLIDER_HEIGHT, fraction,
                Theme.TRACK, Anim.mix(Theme.TEXT, Theme.GREEN_BRIGHT, progressKnob));
        int progressKnobRadius = Math.round(KNOB_RADIUS * progressKnob);
        if (progressKnobRadius > 0) {
            Theme.circle(graphics, progressX + filled, knobY, progressKnobRadius, Theme.TEXT);
        }
        if (config.showTrackTime) {
            String elapsed = pendingProgress >= 0.0f
                    ? Json.formatMillis((long) (track.durationMs() * fraction))
                    : state.formattedProgress();
            Platform.drawString(graphics, font, elapsed, progressX - font.width(elapsed) - 6,
                    knobY - 4, Theme.TEXT_MUTED, false);
            Platform.drawString(graphics, font, track.formattedDuration(), progressX + progressWidth + 6, knobY - 4,
                    Theme.TEXT_MUTED, false);
        }

        // The right hand group is laid out from the edge inwards, so switching one part off closes
        // the gap instead of leaving a hole and moving nothing else.
        int rightEdge = x + width - 12;
        volumeY = y + HEIGHT / 2;
        int cursor;
        if (config.showVolumeSlider) {
            volumeWidth = 60;
            volumeX = rightEdge - volumeWidth;
            int volumePercent = displayVolume(state);
            int level = volumePercent == 0 ? 0 : volumePercent < 34 ? 1 : volumePercent < 67 ? 2 : 3;
            speakerX = volumeX - 18;
            iconButton("bar.speaker", within(mouseX, mouseY, speakerX, volumeY, 10),
                    Theme.TEXT_MUTED, Theme.TEXT, 12,
                    (color, size) -> Icons.speaker(graphics, speakerX, volumeY, size, level, color));
            boolean volumeActive = overVolume(mouseX, mouseY) || draggingVolume;
            float volumeKnob = Anim.hover("bar.volumeKnob", volumeActive);
            int volumeFilled = Theme.slider(graphics, volumeX, volumeY - Theme.SLIDER_HEIGHT / 2, volumeWidth,
                    Theme.SLIDER_HEIGHT, volumePercent / 100.0f, Theme.TRACK,
                    Anim.mix(Theme.TEXT, Theme.GREEN_BRIGHT, volumeKnob));
            int volumeKnobRadius = Math.round(KNOB_RADIUS * volumeKnob);
            if (volumeKnobRadius > 0) {
                Theme.circle(graphics, volumeX + volumeFilled, volumeY, volumeKnobRadius, Theme.TEXT);
            }
            cursor = speakerX;
        } else {
            volumeWidth = 0;
            volumeX = HIDDEN;
            speakerX = HIDDEN;
            cursor = rightEdge + 4;
        }
        // Even 22 pixel steps, so the row keeps its rhythm whichever parts are switched on.
        if (config.showDeviceButton) {
            deviceX = cursor - 22;
            iconButton("bar.devices", within(mouseX, mouseY, deviceX, volumeY, 12),
                    Theme.TEXT_MUTED, Theme.TEXT, 12,
                    (color, size) -> Icons.devices(graphics, deviceX, volumeY, size, color));
            cursor = deviceX;
        } else {
            deviceX = HIDDEN;
        }
        if (config.showQueueButton) {
            queueX = cursor - 22;
            iconButton("bar.queue", within(mouseX, mouseY, queueX, volumeY, 12),
                    Theme.TEXT_MUTED, Theme.TEXT, 12,
                    (color, size) -> Icons.queue(graphics, queueX, volumeY, size, color));
            cursor = queueX;
        } else {
            queueX = HIDDEN;
        }
        if (config.showLyricsButton) {
            lyricsX = cursor - 22;
            // Lit like shuffle while the world lyrics are on.
            float lyricsOn = Anim.to("bar.lyricsOn", config.worldLyrics ? 1.0f : 0.0f, Anim.FAST);
            int lyricsIdle = Anim.mix(Theme.TEXT_MUTED, Theme.GREEN, lyricsOn);
            iconButton("bar.lyrics", within(mouseX, mouseY, lyricsX, volumeY, 12), lyricsIdle,
                    whenMoving(lyricsIdle, Anim.mix(Theme.TEXT, Theme.GREEN_BRIGHT, lyricsOn)), 12,
                    (color, size) -> Icons.lyrics(graphics, lyricsX, volumeY, size, color));
        } else {
            lyricsX = HIDDEN;
        }

    }

    /** What an icon button paints, at the colour and the size it is handed. */
    private interface Glyph {
        void draw(int color, int size);
    }

    /**
      * Draws one of the small icon buttons: the colour follows the cursor, the glyph gains a pixel or
      * two under it and loses one while it is pressed. The hover value is read once and used for
      * both, since asking for the same name twice in a frame would step its easing twice and run the
      * fade at double speed. Only the drawn size changes, never the area the mouse is tested against,
      * or the button would flicker on its own edge.
      */
    private static void iconButton(String id, boolean hovered, int idle, int active, int size,
                                   Glyph glyph) {
        float amount = Anim.hover(id, hovered);
        int color = Anim.mix(idle, active, amount);
        glyph.draw(color, Math.max(2, Anim.grownSize(size, amount, Anim.ICON_STEP) - Anim.iconPress(id)));
    }

    /**
      * Picks between the colour the bar has always drawn and one that only exists because there is
      * now a fade to carry it. Brightening a control that never brightened before is not something
      * to leave standing once the user has switched the animations off.
      */
    private static int whenMoving(int still, int moving) {
        return Anim.enabled() ? moving : still;
    }

    /** How far a cover that has just loaded has come out of its placeholder. 1 once it is there. */
    private float artworkArrival() {
        if (!Anim.enabled() || artworkArrivedAt == 0L) {
            return 1.0f;
        }
        long since = System.currentTimeMillis() - artworkArrivedAt;
        if (since >= ART_FADE_MILLIS) {
            return 1.0f;
        }
        return Anim.outCubic(since / (float) ART_FADE_MILLIS);
    }

    public boolean mouseClicked(double mouseX, double mouseY, Runnable openDevices, Runnable openQueue,
                                java.util.function.BiConsumer<String, String> openArtist) {
        SpotifySession session = ClientState.session();
        PlaybackState state = session.playback();
        int mx = (int) mouseX;
        int my = (int) mouseY;

        if (!Theme.hovered(mx, my, barX, barY, barWidth, HEIGHT)) {
            return false;
        }
        // First, because it is the leftmost thing in the bar and overlaps nothing else. A name leads
        // to that artist rather than to whatever the bar would otherwise do with the click.
        ArtistLine.Span artist = artistX == HIDDEN ? null
                : ArtistLine.hit(Minecraft.getInstance().font, state.track(), artistX, artistWidth,
                        mouseX, mouseY, artistY);
        if (artist != null) {
            Anim.click("bar.artist:" + artist.credit());
            openArtist.accept(artist.id(), artist.name());
            return true;
        }
        if (overProgress(mx, my)) {
            // Pressing only picks the value up. The request goes out on release, so a plain click
            // and a drag both cost exactly one request.
            draggingProgress = true;
            setProgressFromMouse(mouseX);
            return true;
        }
        if (within(mx, my, speakerX, volumeY, 10)) {
            Anim.click("bar.speaker");
            int current = displayVolume(state);
            if (current > 0) {
                mutedVolume = current;
                pendingVolume = 0;
            } else {
                pendingVolume = mutedVolume > 0 ? mutedVolume : 50;
            }
            sentVolume = -1;
            commitVolume();
            return true;
        }
        if (overVolume(mx, my)) {
            draggingVolume = true;
            sentVolume = -1;
            setVolumeFromMouse(mouseX);
            return true;
        }
        if (within(mx, my, centerX, controlsY, 15)) {
            Anim.click("bar.play");
            session.togglePlayback();
            return true;
        }
        if (within(mx, my, centerX - 32, controlsY, 12)) {
            Anim.click("bar.prev");
            session.previousTrack();
            return true;
        }
        if (within(mx, my, centerX + 32, controlsY, 12)) {
            Anim.click("bar.next");
            session.nextTrack();
            return true;
        }
        if (Theme.hovered(mx, my, heartX - 8, heartY - 8, 16, 16) && !state.track().isEmpty()) {
            Anim.click("bar.like");
            session.toggleLiked(state.track());
            return true;
        }
        if (ConfigManager.client().showShuffleRepeat && within(mx, my, centerX - 58, controlsY, 12)) {
            Anim.click("bar.shuffle");
            boolean next = !state.shuffle();
            session.runThenPoll(() -> session.api().shuffle(next));
            return true;
        }
        if (ConfigManager.client().showShuffleRepeat && within(mx, my, centerX + 58, controlsY, 12)) {
            Anim.click("bar.repeat");
            String next = switch (state.repeatMode()) {
                case "off" -> "context";
                case "context" -> "track";
                default -> "off";
            };
            session.runThenPoll(() -> session.api().repeat(next));
            return true;
        }
        if (within(mx, my, deviceX, volumeY, 12)) {
            Anim.click("bar.devices");
            openDevices.run();
            return true;
        }
        if (within(mx, my, queueX, volumeY, 12)) {
            Anim.click("bar.queue");
            openQueue.run();
            return true;
        }
        if (within(mx, my, lyricsX, volumeY, 12)) {
            Anim.click("bar.lyrics");
            ConfigManager.client().worldLyrics = !ConfigManager.client().worldLyrics;
            ConfigManager.saveClient();
            return true;
        }
        return true;
    }

    public boolean mouseDragged(double mouseX) {
        if (draggingProgress) {
            // Scrubbing only moves the knob. Seeking on every mouse move would queue dozens of
            // requests on the single API thread and the knob would lag behind the cursor.
            setProgressFromMouse(mouseX);
            return true;
        }
        if (draggingVolume) {
            setVolumeFromMouse(mouseX);
            if (System.currentTimeMillis() - lastVolumeSentMillis >= VOLUME_SEND_INTERVAL_MILLIS) {
                commitVolume();
            }
            return true;
        }
        return false;
    }

    public void mouseReleased() {
        if (draggingProgress) {
            draggingProgress = false;
            commitSeek();
        }
        if (draggingVolume) {
            draggingVolume = false;
            commitVolume();
        }
    }

    private boolean overProgress(double mouseX, double mouseY) {
        return Theme.hovered((int) mouseX, (int) mouseY, progressX, progressY - 4, progressWidth,
                SLIDER_HIT_HEIGHT);
    }

    private boolean overVolume(double mouseX, double mouseY) {
        return volumeWidth > 0 && Theme.hovered((int) mouseX, (int) mouseY, volumeX, volumeY - 6, volumeWidth,
                SLIDER_HIT_HEIGHT);
    }

    private void setProgressFromMouse(double mouseX) {
        // Without a duration there is nothing to seek to, so the bar must not follow the cursor.
        if (progressWidth <= 0 || ClientState.session().playback().track().durationMs() <= 0) {
            return;
        }
        float fraction = (float) ((mouseX - progressX) / progressWidth);
        pendingProgress = Math.max(0.0f, Math.min(1.0f, fraction));
    }

    private void setVolumeFromMouse(double mouseX) {
        if (volumeWidth <= 0) {
            return;
        }
        // Dividing by the width would leave the last pixel short of 100 percent.
        int span = Math.max(1, volumeWidth - 1);
        int percent = (int) Math.round((mouseX - volumeX) / span * 100.0D);
        pendingVolume = Math.max(0, Math.min(100, percent));
    }

    private void commitSeek() {
        SpotifySession session = ClientState.session();
        long duration = session.playback().track().durationMs();
        if (duration <= 0 || pendingProgress < 0.0f) {
            return;
        }
        pendingProgressUntil = System.currentTimeMillis() + PENDING_HOLD_MILLIS;
        long target = (long) (duration * pendingProgress);
        session.runThenPoll(() -> session.api().seek(target));
    }

    private void commitVolume() {
        if (pendingVolume < 0 || pendingVolume == sentVolume) {
            return;
        }
        int value = pendingVolume;
        sentVolume = value;
        long now = System.currentTimeMillis();
        lastVolumeSentMillis = now;
        pendingVolumeUntil = now + PENDING_HOLD_MILLIS;
        SpotifySession session = ClientState.session();
        session.runThenPoll(() -> session.api().volume(value));
    }

    /** The value the user is setting wins over the polled one until the poll has caught up. */
    private int displayVolume(PlaybackState state) {
        if (pendingVolume >= 0 && !draggingVolume && state.volumePercent() == pendingVolume) {
            pendingVolume = -1;
            pendingVolumeUntil = 0L;
        }
        if (pendingVolume >= 0 && (draggingVolume || System.currentTimeMillis() < pendingVolumeUntil)) {
            return pendingVolume;
        }
        pendingVolume = -1;
        return Math.max(0, Math.min(100, state.volumePercent()));
    }

    private float displayProgress(PlaybackState state, Track track) {
        if (pendingProgress >= 0.0f && (draggingProgress
                || System.currentTimeMillis() < pendingProgressUntil)) {
            return pendingProgress;
        }
        pendingProgress = -1.0f;
        return track.durationMs() > 0 ? (float) state.progressMs() / track.durationMs() : 0.0f;
    }

    public String tooltip(int mouseX, int mouseY) {
        if (overProgress(mouseX, mouseY) && progressWidth > 0) {
            SpotifySession session = ClientState.session();
            long duration = session.playback().track().durationMs();
            float fraction = (float) ((mouseX - progressX) / (double) progressWidth);
            return Json.formatMillis((long) (duration * Math.max(0f, Math.min(1f, fraction))));
        }
        return "";
    }

    private static boolean within(int mouseX, int mouseY, int cx, int cy, int radius) {
        return Math.abs(mouseX - cx) <= radius && Math.abs(mouseY - cy) <= radius;
    }
}
