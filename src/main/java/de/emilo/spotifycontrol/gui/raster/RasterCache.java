package de.emilo.spotifycontrol.gui.raster;

import com.mojang.blaze3d.platform.NativeImage;
import de.emilo.spotifycontrol.SpotifyControl;
import de.emilo.spotifycontrol.config.ClientConfig;
import de.emilo.spotifycontrol.config.ConfigManager;
import de.emilo.spotifycontrol.platform.Platform;
import net.minecraft.client.gui.GuiGraphics;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

public final class RasterCache {

    public static final int TOP_LEFT = 0;
    public static final int TOP_RIGHT = 1;
    public static final int BOTTOM_LEFT = 2;
    public static final int BOTTOM_RIGHT = 3;

    private static final int MAX_ENTRIES = 512;
    private static final int SUPERSAMPLES = 2;
    private static final int MAX_ICON_PIXELS = 256;

    private static final Map<String, Object> TEXTURES = new LinkedHashMap<>(256, 0.75f, true);
    private static final Map<String, Long> SLOTS = new java.util.HashMap<>();
    private static final java.util.ArrayDeque<Long> FREE_SLOTS = new java.util.ArrayDeque<>();
    private static long sequence;
    private static float extraScale = 1.0f;
    private static boolean broken;

    private RasterCache() {
    }

    public static void setExtraScale(float scale) {
        extraScale = Math.max(0.25f, scale);
    }

    public static void resetExtraScale() {
        extraScale = 1.0f;
    }

    public static boolean available() {
        return !broken;
    }

    public static int scale() {
        return Math.max(1, Math.round(Platform.guiScale() * extraScale));
    }

    /** Screen pixels one drawn pixel covers while smooth graphics is off. */
    private static final int PIXEL_BLOCK = 2;

    /** Distinguishes the two render modes in the cache keys. */
    private static String modeTag() {
        return ConfigManager.client().smoothGraphics ? "s" : "p";
    }

    /**
      * Resolution a shape is rasterized at. There is only one renderer: the blocky look is the same
      * vector shape resolved coarsely and stretched back up, so both modes stay in step and no
      * second set of hand drawn icons has to be kept alive. Below a few points nothing would be
      * recognisable any more, hence the floor.
      */
    public static int rasterPixels(int size) {
        int full = Math.max(1, Math.min(MAX_ICON_PIXELS, size * scale()));
        if (ConfigManager.client().smoothGraphics) {
            return full;
        }
        return Math.max(3, full / PIXEL_BLOCK);
    }

    public static void drawShape(GuiGraphics graphics, String name, Shape shape, int x, int y, int size, int color) {
        if (size <= 0 || broken) {
            return;
        }
        int pixels = rasterPixels(size);
        String key = "shape/" + name + "/" + modeTag() + "/" + pixels + "/" + Integer.toHexString(color);
        Object handle = texture(key, pixels, pixels, () -> rasterizeShape(shape, pixels, color));
        if (handle != null) {
            Platform.drawTexture(graphics, handle, x, y, size, size);
        }
    }

    /**
     * Draws one of the outlines in {@link VectorIcons}, rasterized to match the
     * current gui scale so the result stays crisp instead of being stretched.
     */
    public static void drawIcon(GuiGraphics graphics, String name, String data, int x, int y, int size, int color) {
        if (size <= 0 || broken) {
            return;
        }
        int pixels = rasterPixels(size);
        String key = "icon/" + name + "/" + modeTag() + "/" + pixels + "/" + Integer.toHexString(color);
        Object handle = texture(key, pixels, pixels, () -> VectorRaster.rasterize(data, pixels, color, !ConfigManager.client().smoothGraphics));
        if (handle != null) {
            Platform.drawTexture(graphics, handle, x, y, size, size);
        }
    }

    public static void drawCorner(GuiGraphics graphics, int quadrant, int x, int y, int radius, int color,
                                  boolean inverted) {
        if (radius <= 0 || broken) {
            return;
        }
        int pixels = rasterPixels(radius);
        String key = "corner/" + quadrant + "/" + modeTag() + "/" + pixels + "/" + Integer.toHexString(color)
                + (inverted ? "/i" : "");
        Object handle = texture(key, pixels, pixels, () -> rasterizeCorner(quadrant, pixels, color, inverted));
        if (handle != null) {
            Platform.drawTexture(graphics, handle, x, y, radius, radius);
        }
    }

    private static Object texture(String key, int width, int height, PixelSource source) {
        Object cached = TEXTURES.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            int[] argb = source.pixels();
            NativeImage image = NativeImage.read(new java.io.ByteArrayInputStream(PngEncoder.encode(width, height, argb)));
            long slot = FREE_SLOTS.isEmpty() ? sequence++ : FREE_SLOTS.pollFirst();
            Object handle = Platform.registerTexture("raster/" + slot, image);
            TEXTURES.put(key, handle);
            SLOTS.put(key, slot);
            evict();
            return handle;
        } catch (Throwable e) {
            broken = true;
            SpotifyControl.LOGGER.error("Smooth rendering disabled, texture rasterization failed", e);
            return null;
        }
    }

    private static void evict() {
        while (TEXTURES.size() > MAX_ENTRIES) {
            Iterator<Map.Entry<String, Object>> iterator = TEXTURES.entrySet().iterator();
            Map.Entry<String, Object> eldest = iterator.next();
            iterator.remove();
            Platform.releaseTexture(eldest.getValue());
            Long slot = SLOTS.remove(eldest.getKey());
            if (slot != null) {
                FREE_SLOTS.addLast(slot);
            }
        }
    }

    private static int[] rasterizeShape(Shape shape, int pixels, int color) {
        int[] argb = new int[pixels * pixels];
        int alpha = color >>> 24;
        int rgb = color & 0xFFFFFF;
        double step = 1.0 / (pixels * SUPERSAMPLES);
        double samples = SUPERSAMPLES * SUPERSAMPLES;
        for (int py = 0; py < pixels; py++) {
            for (int px = 0; px < pixels; px++) {
                double coverage = 0.0;
                for (int sy = 0; sy < SUPERSAMPLES; sy++) {
                    for (int sx = 0; sx < SUPERSAMPLES; sx++) {
                        double u = (px * SUPERSAMPLES + sx + 0.5) * step;
                        double v = (py * SUPERSAMPLES + sy + 0.5) * step;
                        double distance = shape.distance(u, v) * pixels;
                        coverage += Sdf.clamp(0.5 - distance, 0.0, 1.0);
                    }
                }
                double inside = coverage / samples;
                if (!ConfigManager.client().smoothGraphics) {
                    inside = inside >= 0.5 ? 1.0 : 0.0;
                }
                int a = (int) Math.round(alpha * inside);
                argb[py * pixels + px] = (a << 24) | rgb;
            }
        }
        return argb;
    }

    /**
      * Same rounded corner, but the colour is interpolated down the rows. A gradient laid over a
      * rounded panel has to follow the same arc, otherwise it paints a stepped edge back on.
      */
    public static void drawCornerGradient(GuiGraphics graphics, int quadrant, int x, int y, int radius,
                                          int topColor, int bottomColor) {
        if (radius <= 0 || broken) {
            return;
        }
        int pixels = rasterPixels(radius);
        String key = "cornerfade/" + quadrant + "/" + modeTag() + "/" + pixels + "/"
                + Integer.toHexString(topColor) + "/" + Integer.toHexString(bottomColor);
        Object handle = texture(key, pixels, pixels,
                () -> rasterizeCornerGradient(quadrant, pixels, topColor, bottomColor));
        if (handle != null) {
            Platform.drawTexture(graphics, handle, x, y, radius, radius);
        }
    }

    private static int[] rasterizeCornerGradient(int quadrant, int pixels, int topColor, int bottomColor) {
        int[] argb = new int[pixels * pixels];
        double centerX = quadrant == TOP_LEFT || quadrant == BOTTOM_LEFT ? pixels : 0.0;
        double centerY = quadrant == TOP_LEFT || quadrant == TOP_RIGHT ? pixels : 0.0;
        double step = 1.0 / SUPERSAMPLES;
        double samples = SUPERSAMPLES * SUPERSAMPLES;
        boolean sharp = !ConfigManager.client().smoothGraphics;
        for (int py = 0; py < pixels; py++) {
            int color = lerpColor(topColor, bottomColor, pixels <= 1 ? 0.0f : (float) py / (pixels - 1));
            int alpha = color >>> 24;
            int rgb = color & 0xFFFFFF;
            for (int px = 0; px < pixels; px++) {
                double coverage = 0.0;
                for (int sy = 0; sy < SUPERSAMPLES; sy++) {
                    for (int sx = 0; sx < SUPERSAMPLES; sx++) {
                        double fx = px + (sx + 0.5) * step;
                        double fy = py + (sy + 0.5) * step;
                        coverage += Sdf.clamp(0.5 - (Math.hypot(fx - centerX, fy - centerY) - pixels), 0.0, 1.0);
                    }
                }
                double inside = coverage / samples;
                if (sharp) {
                    inside = inside >= 0.5 ? 1.0 : 0.0;
                }
                argb[py * pixels + px] = ((int) Math.round(alpha * inside) << 24) | rgb;
            }
        }
        return argb;
    }

    private static int lerpColor(int from, int to, float t) {
        int a = channel(from >>> 24, to >>> 24, t);
        int r = channel((from >> 16) & 0xFF, (to >> 16) & 0xFF, t);
        int g = channel((from >> 8) & 0xFF, (to >> 8) & 0xFF, t);
        int b = channel(from & 0xFF, to & 0xFF, t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int channel(int from, int to, float t) {
        return Math.max(0, Math.min(255, Math.round(from + (to - from) * t)));
    }

    private static int[] rasterizeCorner(int quadrant, int pixels, int color, boolean inverted) {
        boolean sharp = !ConfigManager.client().smoothGraphics;
        int[] argb = new int[pixels * pixels];
        int alpha = color >>> 24;
        int rgb = color & 0xFFFFFF;
        double centerX = quadrant == TOP_LEFT || quadrant == BOTTOM_LEFT ? pixels : 0.0;
        double centerY = quadrant == TOP_LEFT || quadrant == TOP_RIGHT ? pixels : 0.0;
        double step = 1.0 / SUPERSAMPLES;
        double samples = SUPERSAMPLES * SUPERSAMPLES;
        for (int py = 0; py < pixels; py++) {
            for (int px = 0; px < pixels; px++) {
                double coverage = 0.0;
                for (int sy = 0; sy < SUPERSAMPLES; sy++) {
                    for (int sx = 0; sx < SUPERSAMPLES; sx++) {
                        double x = px + (sx + 0.5) * step;
                        double y = py + (sy + 0.5) * step;
                        double distance = Math.hypot(x - centerX, y - centerY) - pixels;
                        coverage += Sdf.clamp(0.5 - distance, 0.0, 1.0);
                    }
                }
                double inside = coverage / samples;
                if (sharp) {
                    inside = inside >= 0.5 ? 1.0 : 0.0;
                }
                int a = (int) Math.round(alpha * (inverted ? 1.0 - inside : inside));
                argb[py * pixels + px] = (a << 24) | rgb;
            }
        }
        return argb;
    }

    @FunctionalInterface
    private interface PixelSource {
        int[] pixels();
    }
}
