package de.emilo.spotifycontrol.gui;

import de.emilo.spotifycontrol.gui.raster.RasterCache;
import de.emilo.spotifycontrol.gui.raster.VectorIcons;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Draws the overlay icons from the vector outlines in {@link VectorIcons}.
 *
 * <p>Every icon is normalized into a square that is centered on the requested
 * point, so {@code size} is the icon's real extent along its longer axis. The
 * square is rounded up to an even number of pixels, which keeps the centre of
 * the glyph exactly on {@code cx}/{@code cy} instead of half a pixel off.
 */
public final class Icons {

    private Icons() {
    }

    /**
      * Rounds up to an even number of pixels, because only an even box has its centre on a whole
      * pixel: an odd one would sit half a pixel off the point it is drawn around.
      *
      * <p>Which means a size of eleven and a size of twelve are the same picture. Anything that
      * moves an icon's size has to move it in TWOS, or the change lands on the same extent and
      * nothing happens - and worse, whether it happens depends on whether that icon's resting size
      * is odd, so half the interface would animate and the other half would not. {@link Anim#ICON_STEP}
      * and {@link Anim#iconPress} exist to keep that from being decided one call site at a time.
      * A circle is exempt: {@link Theme#circle} is asked for a radius and rasterized at twice it, so
      * its steps are already even.
      */
    private static void draw(GuiGraphics graphics, String name, String data, int cx, int cy, int size, int color) {
        int extent = Math.max(4, (size + 1) & ~1);
        RasterCache.drawIcon(graphics, name, data, cx - extent / 2, cy - extent / 2, extent, color);
    }

    public static void play(GuiGraphics graphics, int cx, int cy, int size, int color) {
        if (Theme.smooth()) {
            draw(graphics, "play", VectorIcons.PLAY, cx, cy, size, color);
        } else {
            PixelIcons.play(graphics, cx, cy, size, color);
        }
    }

    public static void pause(GuiGraphics graphics, int cx, int cy, int size, int color) {
        if (Theme.smooth()) {
            draw(graphics, "pause", VectorIcons.PAUSE, cx, cy, size, color);
        } else {
            PixelIcons.pause(graphics, cx, cy, size, color);
        }
    }

    public static void next(GuiGraphics graphics, int cx, int cy, int size, int color) {
        if (Theme.smooth()) {
            draw(graphics, "next", VectorIcons.NEXT, cx, cy, size, color);
        } else {
            PixelIcons.next(graphics, cx, cy, size, color);
        }
    }

    public static void previous(GuiGraphics graphics, int cx, int cy, int size, int color) {
        if (Theme.smooth()) {
            draw(graphics, "previous", VectorIcons.PREVIOUS, cx, cy, size, color);
        } else {
            PixelIcons.previous(graphics, cx, cy, size, color);
        }
    }

    public static void shuffle(GuiGraphics graphics, int cx, int cy, int size, int color) {
        if (Theme.smooth()) {
            draw(graphics, "shuffle", VectorIcons.SHUFFLE, cx, cy, size, color);
        } else {
            PixelIcons.shuffle(graphics, cx, cy, size, color);
        }
    }

    public static void repeat(GuiGraphics graphics, int cx, int cy, int size, int color) {
        if (Theme.smooth()) {
            draw(graphics, "repeat", VectorIcons.REPEAT, cx, cy, size, color);
        } else {
            PixelIcons.repeat(graphics, cx, cy, size, color);
        }
    }

    public static void heart(GuiGraphics graphics, int cx, int cy, int size, int color) {
        heart(graphics, cx, cy, size, true, color);
    }

    public static void heart(GuiGraphics graphics, int cx, int cy, int size, boolean filled, int color) {
        if (Theme.smooth()) {
            draw(graphics, filled ? "heart" : "heart_outline",
                    filled ? VectorIcons.HEART : VectorIcons.HEART_OUTLINE, cx, cy, size, color);
        } else {
            PixelIcons.heart(graphics, cx, cy, size, color);
        }
    }

    public static void search(GuiGraphics graphics, int cx, int cy, int size, int color) {
        if (Theme.smooth()) {
            draw(graphics, "search", VectorIcons.SEARCH, cx, cy, size, color);
        } else {
            PixelIcons.search(graphics, cx, cy, size, color);
        }
    }

    public static void home(GuiGraphics graphics, int cx, int cy, int size, int color) {
        if (Theme.smooth()) {
            draw(graphics, "home", VectorIcons.HOME, cx, cy, size, color);
        } else {
            PixelIcons.home(graphics, cx, cy, size, color);
        }
    }

    public static void library(GuiGraphics graphics, int cx, int cy, int size, int color) {
        if (Theme.smooth()) {
            draw(graphics, "library", VectorIcons.LIBRARY, cx, cy, size, color);
        } else {
            PixelIcons.library(graphics, cx, cy, size, color);
        }
    }

    public static void speaker(GuiGraphics graphics, int cx, int cy, int size, int level, int color) {
        if (!Theme.smooth()) {
            PixelIcons.speaker(graphics, cx, cy, size, level, color);
            return;
        }
        String data = switch (Math.max(0, Math.min(3, level))) {
            case 0 -> VectorIcons.SPEAKER_MUTE;
            case 3 -> VectorIcons.SPEAKER_LOUD;
            default -> VectorIcons.SPEAKER_QUIET;
        };
        draw(graphics, "speaker" + level, data, cx, cy, size, color);
    }

    public static void devices(GuiGraphics graphics, int cx, int cy, int size, int color) {
        if (Theme.smooth()) {
            draw(graphics, "devices", VectorIcons.DEVICES, cx, cy, size, color);
        } else {
            PixelIcons.devices(graphics, cx, cy, size, color);
        }
    }

    public static void plus(GuiGraphics graphics, int cx, int cy, int size, int color) {
        if (Theme.smooth()) {
            draw(graphics, "plus", VectorIcons.PLUS, cx, cy, size, color);
        } else {
            PixelIcons.plus(graphics, cx, cy, size, color);
        }
    }

    public static void close(GuiGraphics graphics, int cx, int cy, int size, int color) {
        if (Theme.smooth()) {
            draw(graphics, "close", VectorIcons.CLOSE, cx, cy, size, color);
        } else {
            PixelIcons.close(graphics, cx, cy, size, color);
        }
    }

    public static void queue(GuiGraphics graphics, int cx, int cy, int size, int color) {
        if (Theme.smooth()) {
            draw(graphics, "queue", VectorIcons.QUEUE, cx, cy, size, color);
        } else {
            PixelIcons.queue(graphics, cx, cy, size, color);
        }
    }

    public static void people(GuiGraphics graphics, int cx, int cy, int size, int color) {
        if (Theme.smooth()) {
            draw(graphics, "people", VectorIcons.PEOPLE, cx, cy, size, color);
        } else {
            PixelIcons.people(graphics, cx, cy, size, color);
        }
    }

    public static void gear(GuiGraphics graphics, int cx, int cy, int size, int color) {
        if (Theme.smooth()) {
            draw(graphics, "gear", VectorIcons.GEAR, cx, cy, size, color);
        } else {
            PixelIcons.gear(graphics, cx, cy, size, color);
        }
    }

    public static void note(GuiGraphics graphics, int cx, int cy, int size, int color) {
        if (Theme.smooth()) {
            draw(graphics, "note", VectorIcons.NOTE, cx, cy, size, color);
        } else {
            PixelIcons.library(graphics, cx, cy, size, color);
        }
    }

    public static void check(GuiGraphics graphics, int cx, int cy, int size, int color) {
        if (Theme.smooth()) {
            draw(graphics, "check", VectorIcons.CHECK, cx, cy, size, color);
        } else {
            PixelIcons.plus(graphics, cx, cy, size, color);
        }
    }

    public static void spotify(GuiGraphics graphics, int cx, int cy, int radius) {
        if (Theme.smooth()) {
            int extent = Math.max(4, radius * 2);
            int x = cx - extent / 2;
            int y = cy - extent / 2;
            RasterCache.drawIcon(graphics, "spotify_disc", VectorIcons.SPOTIFY_DISC, x, y, extent, Theme.GREEN);
            RasterCache.drawIcon(graphics, "spotify_waves", VectorIcons.SPOTIFY_WAVES, x, y, extent, Theme.BLACK);
        } else {
            Theme.circle(graphics, cx, cy, radius, Theme.GREEN);
            graphics.fill(cx - 5, cy - 3, cx + 6, cy - 2, Theme.BLACK);
            graphics.fill(cx - 4, cy, cx + 5, cy + 1, Theme.BLACK);
            graphics.fill(cx - 3, cy + 3, cx + 4, cy + 4, Theme.BLACK);
        }
    }
}
