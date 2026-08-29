package de.emilo.spotifycontrol.gui;

import de.emilo.spotifycontrol.config.ConfigManager;
import de.emilo.spotifycontrol.gui.raster.IconShapes;
import de.emilo.spotifycontrol.gui.raster.RasterCache;
import de.emilo.spotifycontrol.platform.Platform;
import net.minecraft.client.gui.GuiGraphics;

public final class Theme {

    public static final int BLACK = 0xFF000000;
    public static final int SURFACE = 0xFF181818;
    public static final int SURFACE_HOVER = 0xFF282828;
    public static final int ELEVATED = 0xFF242424;
    public static final int BORDER = 0xFF2E2E2E;
    public static int GREEN = 0xFF1DB954;
    public static int GREEN_BRIGHT = 0xFF1ED760;
    public static int GREEN_DARK = 0xFF169C46;
    public static final int TEXT = 0xFFFFFFFF;
    public static final int TEXT_MUTED = 0xFFB3B3B3;
    public static final int TEXT_DIM = 0xFF727272;
    public static final int TRACK = 0xFF4D4D4D;
    public static final int THUMB = 0xFFA7A7A7;
    public static final int RED = 0xFFE22134;

    private Theme() {
    }

    public static int alpha(int color, float factor) {
        int a = (int) (((color >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, factor)));
        return (a << 24) | (color & 0x00FFFFFF);
    }

    public static int mix(int from, int to, float progress) {
        float t = Math.max(0f, Math.min(1f, progress));
        int a = lerpChannel(from >>> 24, to >>> 24, t);
        int r = lerpChannel((from >> 16) & 0xFF, (to >> 16) & 0xFF, t);
        int g = lerpChannel((from >> 8) & 0xFF, (to >> 8) & 0xFF, t);
        int b = lerpChannel(from & 0xFF, to & 0xFF, t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int lerpChannel(int from, int to, float t) {
        return (int) (from + (to - from) * t) & 0xFF;
    }

    private static float overlayScale = 1.0f;

    public static void setOverlayScale(float scale) {
        overlayScale = scale;
    }

    public static float computeOverlayScale() {
        return guiStepScale(ConfigManager.client().overlayGuiScale);
    }

    public static void scissor(GuiGraphics graphics, int x1, int y1, int x2, int y2) {
        if (overlayScale == 1.0f || Platform.scissorFollowsPose()) {
            graphics.enableScissor(x1, y1, x2, y2);
            return;
        }
        graphics.enableScissor(Math.round(x1 * overlayScale), Math.round(y1 * overlayScale),
                Math.round(x2 * overlayScale), Math.round(y2 * overlayScale));
    }

    public static void endScissor(GuiGraphics graphics) {
        graphics.disableScissor();
    }

    /**
      * Whether the anti-aliased path can be used. There is no setting for this any more; it is only
      * false if texture rasterization failed at runtime, in which case the blocky fallback keeps the
      * interface usable instead of blank.
      */
    public static boolean smooth() {
        return RasterCache.available();
    }

    /** Corner radius of the assembled window. Only its four outer corners use it. */
    public static final int PANEL_RADIUS = 10;

    private static int shade = 0xE0000000;

    private static final int[][] ACCENTS = {
            {0xFF1DB954, 0xFF1ED760, 0xFF14833B},
            {0xFF2E77D0, 0xFF3E8EF0, 0xFF1F5490},
            {0xFF8E4EC6, 0xFFA96BE0, 0xFF62348A},
            {0xFFE0653A, 0xFFF57F52, 0xFF9C4425},
            {0xFFD94F86, 0xFFF06CA0, 0xFF95355C},
            {0xFFD03A3A, 0xFFEC5555, 0xFF8E2727},
    };

    public static final String[] ACCENT_NAMES = {"Green", "Blue", "Purple", "Orange", "Pink", "Red"};

    /**
      * The chosen accent, read straight out of the settings rather than from {@link #GREEN}.
      *
      * <p>{@code GREEN} only holds the accent once {@link #syncFromConfig} has run, and that happens
      * while the app or the small overlay is being drawn. Anything outside those two - the marker on
      * a name tag in the world, for one - has no such guarantee and has to ask the settings itself.
      */
    public static int accent() {
        return ACCENTS[Math.floorMod(ConfigManager.client().accentColor, ACCENTS.length)][0];
    }

    /** Pulls the themeable settings in. Cheap enough to call once per frame. */
    public static void syncFromConfig() {
        int dim = Math.max(0, Math.min(100, ConfigManager.client().backgroundDim));
        shade = Math.round(dim * 255f / 100f) << 24;
        int[] accent = ACCENTS[Math.floorMod(ConfigManager.client().accentColor, ACCENTS.length)];
        GREEN = accent[0];
        GREEN_BRIGHT = accent[1];
        GREEN_DARK = accent[2];
    }

    /** The HUD follows the same steps as the overlay rather than a free multiplier. */
    public static float computeHudScale() {
        return guiStepScale(ConfigManager.client().hudGuiScale);
    }

    private static float guiStepScale(int target) {
        int game = Platform.guiScale();
        if (target <= 0 || target >= game) {
            return 1.0f;
        }
        return (float) target / game;
    }

    public static int shade() {
        return shade;
    }

    /** Height of every slider track. Even, so its centre lands on a whole pixel. */
    public static final int SLIDER_HEIGHT = 4;

    public static final int CORNER_TOP_LEFT = 1;
    public static final int CORNER_TOP_RIGHT = 2;
    public static final int CORNER_BOTTOM_LEFT = 4;
    public static final int CORNER_BOTTOM_RIGHT = 8;
    public static final int CORNERS_ALL =
            CORNER_TOP_LEFT | CORNER_TOP_RIGHT | CORNER_BOTTOM_LEFT | CORNER_BOTTOM_RIGHT;
    public static final int CORNERS_BOTTOM = CORNER_BOTTOM_LEFT | CORNER_BOTTOM_RIGHT;

    public static void rounded(GuiGraphics graphics, int x, int y, int width, int height, int radius, int color) {
        rounded(graphics, x, y, width, height, radius, color, CORNERS_ALL);
    }

    /**
      * Rounds only the named corners. Panels that sit flush against each other keep their shared
      * edges square, so the assembled surface has one rounded outline and no seam between the parts.
      */
    public static void rounded(GuiGraphics graphics, int x, int y, int width, int height, int radius, int color,
                               int corners) {
        if (width <= 0 || height <= 0) {
            return;
        }
        int r = Math.min(radius, Math.min(width, height) / 2);
        if (r < 2 || !smooth()) {
            pixelRounded(graphics, x, y, width, height, r, color, corners);
            return;
        }
        graphics.fill(x + r, y, x + width - r, y + height, color);
        graphics.fill(x, y + r, x + r, y + height - r, color);
        graphics.fill(x + width - r, y + r, x + width, y + height - r, color);
        corner(graphics, corners, CORNER_TOP_LEFT, RasterCache.TOP_LEFT, x, y, r, color);
        corner(graphics, corners, CORNER_TOP_RIGHT, RasterCache.TOP_RIGHT, x + width - r, y, r, color);
        corner(graphics, corners, CORNER_BOTTOM_LEFT, RasterCache.BOTTOM_LEFT, x, y + height - r, r, color);
        corner(graphics, corners, CORNER_BOTTOM_RIGHT, RasterCache.BOTTOM_RIGHT, x + width - r, y + height - r, r,
                color);
    }

    private static void corner(GuiGraphics graphics, int corners, int flag, int quadrant, int x, int y, int r,
                               int color) {
        if ((corners & flag) != 0) {
            RasterCache.drawCorner(graphics, quadrant, x, y, r, color, false);
        } else {
            graphics.fill(x, y, x + r, y + r, color);
        }
    }

    public static void roundedMask(GuiGraphics graphics, int x, int y, int width, int height, int radius,
                                   int background) {
        int r = Math.min(radius, Math.min(width, height) / 2);
        if (r < 2 || !smooth()) {
            return;
        }
        RasterCache.drawCorner(graphics, RasterCache.TOP_LEFT, x, y, r, background, true);
        RasterCache.drawCorner(graphics, RasterCache.TOP_RIGHT, x + width - r, y, r, background, true);
        RasterCache.drawCorner(graphics, RasterCache.BOTTOM_LEFT, x, y + height - r, r, background, true);
        RasterCache.drawCorner(graphics, RasterCache.BOTTOM_RIGHT, x + width - r, y + height - r, r, background, true);
    }

    public static void roundedBorder(GuiGraphics graphics, int x, int y, int width, int height, int radius,
                                     int border, int fill) {
        rounded(graphics, x, y, width, height, radius, border);
        rounded(graphics, x + 1, y + 1, width - 2, height - 2, Math.max(0, radius - 1), fill);
    }

    /**
      * Draws a slider track with fully rounded ends and returns the width of the filled part, so the
      * caller can place the knob on it.
      */
    public static int slider(GuiGraphics graphics, int x, int y, int width, int height, float fraction,
                             int trackColor, int fillColor) {
        if (width <= 0 || height <= 0) {
            return 0;
        }
        int radius = Math.max(1, height / 2);
        rounded(graphics, x, y, width, height, radius, trackColor);
        int filled = Math.round(width * Math.max(0.0f, Math.min(1.0f, fraction)));
        if (filled > 0) {
            // A fill narrower than the track is tall cannot be round. The radius is clamped to half
            // the shorter side, so a few pixels in it drops under two and falls to the square path -
            // which is why the first seconds of a song showed a hard little block on the end of the
            // bar. Drawing at least one track height keeps the cap round. The number returned is
            // still the true one, so the knob stays where the song actually is.
            rounded(graphics, x, y, Math.min(width, Math.max(filled, height)), height, radius, fillColor);
        }
        return filled;
    }

    public static void circle(GuiGraphics graphics, int centerX, int centerY, int radius, int color) {
        if (radius <= 0) {
            return;
        }
        if (radius < 2 || !smooth()) {
            pixelCircle(graphics, centerX, centerY, radius, color);
            return;
        }
        int size = radius * 2;
        RasterCache.drawShape(graphics, "circle", IconShapes.CIRCLE, centerX - radius, centerY - radius, size, color);
    }

    private static void pixelRounded(GuiGraphics graphics, int x, int y, int width, int height, int r, int color,
                                     int corners) {
        graphics.fill(x + r, y, x + width - r, y + height, color);
        graphics.fill(x, y + r, x + r, y + height - r, color);
        graphics.fill(x + width - r, y + r, x + width, y + height - r, color);
        for (int i = 0; i < r; i++) {
            int inset = r - (int) Math.floor(Math.sqrt((double) r * r - (double) (r - i - 1) * (r - i - 1)));
            graphics.fill(x + ((corners & CORNER_TOP_LEFT) != 0 ? inset : 0), y + i,
                    x + width - ((corners & CORNER_TOP_RIGHT) != 0 ? inset : 0), y + i + 1, color);
            graphics.fill(x + ((corners & CORNER_BOTTOM_LEFT) != 0 ? inset : 0), y + height - i - 1,
                    x + width - ((corners & CORNER_BOTTOM_RIGHT) != 0 ? inset : 0), y + height - i, color);
        }
    }

    private static void pixelCircle(GuiGraphics graphics, int centerX, int centerY, int radius, int color) {
        for (int dy = -radius; dy <= radius; dy++) {
            int span = (int) Math.sqrt((double) radius * radius - (double) dy * dy);
            graphics.fill(centerX - span, centerY + dy, centerX + span, centerY + dy + 1, color);
        }
    }

    public static void outline(GuiGraphics graphics, int x, int y, int width, int height, int color) {
        graphics.fill(x, y, x + width, y + 1, color);
        graphics.fill(x, y + height - 1, x + width, y + height, color);
        graphics.fill(x, y + 1, x + 1, y + height - 1, color);
        graphics.fill(x + width - 1, y + 1, x + width, y + height - 1, color);
    }

    /**
      * A vertical fade whose first rows follow a rounded top edge. Drawn flat it would paint square
      * corners back onto the rounded panel underneath.
      */
    public static void verticalFadeRounded(GuiGraphics graphics, int x, int y, int width, int height, int top,
                                           int bottom, int radius, int corners) {
        int r = Math.max(0, Math.min(radius, Math.min(width / 2, height)));
        int last = Math.max(1, height - 1);
        int edge = mix(top, bottom, (float) r / last);
        if (r > 0) {
            graphics.fillGradient(x + r, y, x + width - r, y + r, top, edge);
            fadeCorner(graphics, corners, CORNER_TOP_LEFT, RasterCache.TOP_LEFT, x, y, r, top, edge);
            fadeCorner(graphics, corners, CORNER_TOP_RIGHT, RasterCache.TOP_RIGHT, x + width - r, y, r, top, edge);
        }
        if (height > r) {
            graphics.fillGradient(x, y + r, x + width, y + height, edge, bottom);
        }
    }

    private static void fadeCorner(GuiGraphics graphics, int corners, int flag, int quadrant, int x, int y, int r,
                                   int top, int bottom) {
        if ((corners & flag) != 0 && smooth()) {
            RasterCache.drawCornerGradient(graphics, quadrant, x, y, r, top, bottom);
            return;
        }
        if ((corners & flag) == 0) {
            graphics.fillGradient(x, y, x + r, y + r, top, bottom);
            return;
        }
        for (int row = 0; row < r; row++) {
            int inset = r - (int) Math.floor(Math.sqrt((double) r * r - (double) (r - row - 1) * (r - row - 1)));
            boolean left = quadrant == RasterCache.TOP_LEFT || quadrant == RasterCache.BOTTOM_LEFT;
            int from = left ? x + inset : x;
            int to = left ? x + r : x + r - inset;
            graphics.fillGradient(from, y + row, to, y + row + 1,
                    mix(top, bottom, (float) row / Math.max(1, r)), mix(top, bottom, (float) (row + 1) / Math.max(1, r)));
        }
    }

    public static boolean hovered(int mouseX, int mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    private record Marquee(String text, long start) {
    }

    private static final java.util.Map<String, Marquee> MARQUEES = new java.util.HashMap<>();

    /** Pixels per second a title slides at. */
    private static final int MARQUEE_SPEED = 24;

    /** How long it rests at each end, in milliseconds. */
    private static final long MARQUEE_PAUSE = 1600L;

    /**
      * Draws a line of text that slides back and forth when it is too wide for its box, the way the
      * Spotify client does, instead of cutting it off. Every box needs its own {@code id} so two of
      * them do not share a phase. Falls back to an ellipsis when the setting is off.
      */
    public static void scrollingText(GuiGraphics graphics, net.minecraft.client.gui.Font font, String text,
                                     int x, int y, int width, int color, String id) {
        scrollingText(graphics, font, text, x, y, width, color, id, true);
    }

    /**
      * {@code clipSafe} says whether a scissor rectangle lands where this code expects. Before
      * Minecraft 1.21.4 the scissor ignores the pose, and {@link #scissor} can only undo a scale
      * applied at the origin - so a caller that also translated, like the HUD, has to say no and
      * gets the ellipsis instead of a sliding title.
      */
    public static void scrollingText(GuiGraphics graphics, net.minecraft.client.gui.Font font, String text,
                                     int x, int y, int width, int color, String id, boolean clipSafe) {
        String value = text == null ? "" : text;
        int textWidth = font.width(value);
        if (width <= 0 || textWidth <= width) {
            MARQUEES.remove(id);
            Platform.drawString(graphics, font, value, x, y, color, false);
            return;
        }
        if (!ConfigManager.client().scrollingTitles || !clipSafe) {
            MARQUEES.remove(id);
            Platform.drawString(graphics, font, fit(font, value, width), x, y, color, false);
            return;
        }
        long now = System.currentTimeMillis();
        Marquee state = MARQUEES.get(id);
        if (state == null || !state.text().equals(value)) {
            state = new Marquee(value, now);
            MARQUEES.put(id, state);
        }
        int overflow = textWidth - width;
        long travel = Math.max(1L, overflow * 1000L / MARQUEE_SPEED);
        long phase = (now - state.start()) % ((MARQUEE_PAUSE + travel) * 2L);
        int offset;
        if (phase < MARQUEE_PAUSE) {
            offset = 0;
        } else if (phase < MARQUEE_PAUSE + travel) {
            offset = (int) (overflow * (phase - MARQUEE_PAUSE) / travel);
        } else if (phase < MARQUEE_PAUSE * 2L + travel) {
            offset = overflow;
        } else {
            offset = (int) (overflow - overflow * (phase - MARQUEE_PAUSE * 2L - travel) / travel);
        }
        scissor(graphics, x, y - 1, x + width, y + 10);
        Platform.drawString(graphics, font, value, x - offset, y, color, false);
        endScissor(graphics);
    }

    public static String fit(net.minecraft.client.gui.Font font, String text, int maxWidth) {
        if (text == null || text.isEmpty() || font.width(text) <= maxWidth) {
            return text == null ? "" : text;
        }
        int ellipsis = font.width("...");
        return font.plainSubstrByWidth(text, Math.max(0, maxWidth - ellipsis)) + "...";
    }
}
