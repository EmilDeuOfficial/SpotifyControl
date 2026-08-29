package de.emilo.spotifycontrol.gui;

import de.emilo.spotifycontrol.client.ClientState;
import de.emilo.spotifycontrol.config.ClientConfig;
import de.emilo.spotifycontrol.config.ConfigManager;
import de.emilo.spotifycontrol.net.payload.PartyStatePayload;
import de.emilo.spotifycontrol.spotify.model.PlaybackState;
import de.emilo.spotifycontrol.spotify.model.Track;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import de.emilo.spotifycontrol.gui.raster.RasterCache;
import de.emilo.spotifycontrol.platform.Platform;
import de.emilo.spotifycontrol.platform.ScreenHost;

public final class NowPlayingHud {

    private static final int WIDTH = 158;
    private static final int HEIGHT = 42;

    /** How far outside its corner the box waits before it has arrived, in screen pixels. */
    private static final int SLIDE = 10;

    /** The much shorter hop the box makes when the track changes under it. */
    private static final int BUMP = 4;

    /**
      * How far the box has come in. It has to be remembered out here because it outlives its own
      * reason to exist: once playback stops there is no track left to read anything from, but the
      * box is still on screen for the length of a fade.
      */
    private static float shown;

    /**
      * The last track that was actually drawn. Spotify takes the track away the moment it stops,
      * so without a copy the box would spend its fade-out showing an empty line and a blank cover.
      * The position rides along for the same reason, to keep the bar from snapping back to zero.
      */
    private static String lastTitle = "";
    private static String lastArtist = "";
    private static String lastArtwork = "";
    private static long lastDuration;
    private static long lastProgress;

    /** When the title last changed while the box was already up, for the small hop that follows. */
    private static long changedAtMillis;

    private NowPlayingHud() {
    }

    public static void render(GuiGraphics graphics) {
        Minecraft client = Minecraft.getInstance();
        // Before anything else, and before the clock is opened. The game keeps calling this while a
        // screen is up, and the app window is drawn later in the same frame and opens the frame it
        // animates on. Taking that frame here first would leave the app a delta of almost nothing
        // and every fade, every slide and every scroll in the window would crawl.
        if (client.screen instanceof ScreenHost) {
            shown = Anim.to("hud.shown", 0.0f, Anim.SLOW);
            return;
        }
        Anim.beginFrame();
        ClientConfig config = ConfigManager.client();
        // The hard reasons to be gone: the setting is off, there is no world, or the player hid the
        // interface. None of them leaves anything on screen to fade out of, so the box drops out at
        // once. The value keeps draining while we are away, which is what buys it a proper arrival
        // when the game hands the screen back.
        if (!config.showNowPlayingHud || client.player == null || client.options.hideGui) {
            shown = Anim.to("hud.shown", 0.0f, Anim.SLOW);
            return;
        }
        PlaybackState state = ClientState.session().playback();
        Track track = state.track();
        String title = track.title();
        String artist = track.artist();
        String artworkUrl = track.artworkUrl();
        long duration = track.durationMs();
        long progress = state.progressMs();

        PartyStatePayload party = ClientState.party();
        if (title.isEmpty() && party.inParty() && !ClientState.remoteTrack().title().isEmpty()) {
            title = ClientState.remoteTrack().title();
            artist = ClientState.remoteTrack().artist();
            artworkUrl = ClientState.remoteTrack().artworkUrl();
            duration = ClientState.remoteTrack().durationMs();
            progress = ClientState.remoteTrack().positionMs();
        }
        // The soft reasons, by contrast, happen while the box is right there in the corner, so
        // they are worth a fade: an empty track means playback ended, and the setting below hides
        // it again the moment the user pauses.
        boolean playing = ClientState.session().playback().playing();
        boolean shouldShow = !title.isEmpty() && !(config.hudOnlyWhilePlaying && !playing);
        if (shouldShow) {
            // shown still holds the previous frame at this point, which is exactly what tells a
            // track change apart from a first arrival: only a box that is already up gets the hop.
            if (!title.equals(lastTitle) && !lastTitle.isEmpty() && shown > 0.05f) {
                changedAtMillis = System.currentTimeMillis();
            }
            lastTitle = title;
            lastArtist = artist;
            lastArtwork = artworkUrl;
            lastDuration = duration;
            lastProgress = progress;
        } else {
            title = lastTitle;
            artist = lastArtist;
            artworkUrl = lastArtwork;
            duration = lastDuration;
            progress = lastProgress;
        }
        shown = Anim.to("hud.shown", shouldShow ? 1.0f : 0.0f, Anim.SLOW);
        if (shown <= 0.002f || title.isEmpty()) {
            return;
        }

        Font font = client.font;
        float scale = Theme.computeHudScale();

        // The scale is applied around the anchor, so the box covers WIDTH * scale from there.
        // Anchoring on the unscaled size would push a scaled up HUD off the right or bottom edge.
        int screenWidth = graphics.guiWidth();
        int screenHeight = graphics.guiHeight();
        // Without the progress bar the box is shorter, without the cover it is narrower.
        int hudHeight = config.hudShowProgress ? HEIGHT : HEIGHT - 8;
        int artSize = hudHeight - 12;
        int hudWidth = config.hudShowAlbumArt ? WIDTH : WIDTH - artSize - 6;
        int hudMargin = Math.max(0, Math.min(80, config.hudMargin));
        int scaledWidth = Math.round(hudWidth * scale);
        int scaledHeight = Math.round(hudHeight * scale);
        boolean anchoredLeft = switch (config.hudCorner) {
            case "TOP_LEFT", "BOTTOM_LEFT" -> true;
            default -> false;
        };
        int x = anchoredLeft ? hudMargin : Math.max(0, screenWidth - scaledWidth - config.hudMargin);
        int y = switch (config.hudCorner) {
            case "BOTTOM_LEFT", "BOTTOM_RIGHT" -> Math.max(0, screenHeight - scaledHeight - hudMargin - 40);
            default -> hudMargin;
        };

        // Both movements travel along the edge the box is anchored to, so it reads as sliding out
        // from behind the screen border rather than growing out of the middle of the game. They
        // are folded into the translate the scale is pushed with; a second matrix on top would
        // put the marquee scissor somewhere other than the title it is meant to clip. The offset
        // is whole pixels because half a pixel of travel does not move the box, it samples the
        // panel, the cover and the icons off their own grid and the whole thing arrives soft.
        float nudge = Anim.entry(changedAtMillis, 0);
        int travel = Math.round((1.0f - shown) * SLIDE + (1.0f - nudge) * BUMP);
        Platform.pushScale(graphics, anchoredLeft ? x - travel : x + travel, y, scale);
        RasterCache.setExtraScale(scale);

        ClientState.session().markVisible();
        Theme.syncFromConfig();
        // Everything tinted by shown goes through Anim from here on: the rounded shapes and the
        // icons are rasterized once per colour, so a fade handed to them raw would build a new
        // texture every frame. The two lines of text below are not cached and keep the smooth value.
        Theme.rounded(graphics, 0, 0, hudWidth, hudHeight, 6, Anim.fade(Theme.SURFACE, shown));
        int textX = 8;
        if (config.hudShowAlbumArt) {
            Object art = ClientState.artwork().get(artworkUrl, artSize);
            if (art != null) {
                Platform.drawTexture(graphics, art, 6, 6, artSize, artSize);
                // A texture carries no alpha of its own, so anything the cover has to fade through
                // is painted over it instead: the placeholder grey a new cover rises out of after a
                // track change, and the panel colour while the box itself comes and goes.
                if (nudge < 1.0f) {
                    Theme.rounded(graphics, 6, 6, artSize, artSize, 4,
                            Anim.fade(Theme.ELEVATED, 1.0f - nudge));
                }
                if (shown < 1.0f) {
                    Theme.rounded(graphics, 6, 6, artSize, artSize, 4,
                            Anim.fade(Theme.SURFACE, 1.0f - shown));
                }
                Theme.roundedMask(graphics, 6, 6, artSize, artSize, 4, Theme.SURFACE);
            } else {
                Theme.rounded(graphics, 6, 6, artSize, artSize, 4, Anim.fade(Theme.ELEVATED, shown));
                Icons.note(graphics, 6 + artSize / 2, 6 + artSize / 2, 14, Anim.fade(Theme.TEXT_DIM, shown));
            }
            textX = artSize + 12;
        }
        int textWidth = hudWidth - textX - (party.inParty() ? 22 : 8);
        Theme.scrollingText(graphics, font, title, textX, 8, textWidth, Anim.textFade(Theme.TEXT, shown), "hud.title",
                Platform.scissorFollowsPose());
        if (config.hudShowArtist) {
            Platform.drawString(graphics, font, Theme.fit(font, artist, textWidth), textX, 19,
                    Anim.textFade(Theme.TEXT_MUTED, shown), false);
        }

        if (config.hudShowProgress) {
            float fraction = duration > 0 ? (float) progress / duration : 0f;
            // The fill breathes only while something is actually playing, and barely: this box sits
            // on top of the game, so the movement is there to be noticed in passing, never watched.
            int fill = Anim.mix(Theme.GREEN, Theme.GREEN_BRIGHT, playing ? 0.25f * Anim.pulse(2.8f) : 0.0f);
            Theme.slider(graphics, textX, hudHeight - 12, textWidth, Theme.SLIDER_HEIGHT, fraction,
                    Anim.fade(Theme.TRACK, shown), Anim.fade(fill, shown));
        }

        if (party.inParty()) {
            Icons.people(graphics, hudWidth - 12, 10, 8,
                    Anim.fade(ClientState.capturing() ? Theme.GREEN_BRIGHT : Theme.GREEN, shown));
        }

        RasterCache.resetExtraScale();
        Platform.popMatrix(graphics);
    }
}
