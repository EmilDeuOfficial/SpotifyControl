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

    /**
     * Like {@link #drawIcon}, but for a size that is not a whole number of interface pixels, so an
     * icon can grow by one device pixel at a time instead of by one interface pixel.
     *
     * <p>The outline is rasterized at exactly the device pixels it will cover and then placed on the
     * device pixel grid, so it is still sampled one to one and stays sharp. Only the way it is put on
     * screen is fractional: a translate and a scale of the quad that holds the texture.</p>
     *
     * @param cx centre, in interface pixels
     * @param cy centre, in interface pixels
     */
    public static void drawIconExact(GuiGraphics graphics, String name, String data, float cx, float cy,
                                     float size, int color) {
        if (size <= 0.0f || broken) {
            return;
        }
        int scale = scale();
        if (ConfigManager.client().smoothGraphics) {
            drawFractional(graphics, "icon/" + name, () -> VectorRaster.rasterize(data, SOURCE_PIXELS, 0xFFFFFFFF, false),
                    cx, cy, size, color, scale);
            return;
        }
        int device = Math.max(2, Math.round(size * scale));
        int pixels = rasterPixelsOfDevice(device);
        String key = "icon/" + name + "/" + modeTag() + "/" + pixels + "/" + Integer.toHexString(color);
        Object handle = texture(key, pixels, pixels, () -> VectorRaster.rasterize(data, pixels, color, !ConfigManager.client().smoothGraphics));
        if (handle != null) {
            drawOnDeviceGrid(graphics, handle, cx, cy, device, scale);
        }
    }

    /** {@link #drawShape} for a size that is not a whole number of interface pixels. See {@link #drawIconExact}. */
    public static void drawShapeExact(GuiGraphics graphics, String name, Shape shape, float cx, float cy,
                                      float size, int color) {
        if (size <= 0.0f || broken) {
            return;
        }
        int scale = scale();
        if (ConfigManager.client().smoothGraphics) {
            drawFractional(graphics, "shape/" + name, () -> rasterizeShape(shape, SOURCE_PIXELS, 0xFFFFFFFF),
                    cx, cy, size, color, scale);
            return;
        }
        int device = Math.max(2, Math.round(size * scale));
        int pixels = rasterPixelsOfDevice(device);
        String key = "shape/" + name + "/" + modeTag() + "/" + pixels + "/" + Integer.toHexString(color);
        Object handle = texture(key, pixels, pixels, () -> rasterizeShape(shape, pixels, color));
        if (handle != null) {
            drawOnDeviceGrid(graphics, handle, cx, cy, device, scale);
        }
    }

    /** Resolution of the master picture a shape is kept at, which every fractional size is cut from. */
    private static final int SOURCE_PIXELS = 128;
    private static final int MAX_SOURCES = 64;
    private static final Map<String, int[]> SOURCES = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, int[]> eldest) {
            return size() > MAX_SOURCES;
        }
    };

    /**
     * Draws a shape at a size that is a fraction of a device pixel, 1/8 of one at a time. The shape
     * is kept once, large and white; each size is the area average of that master over the device
     * pixels it covers, so the edge is anti-aliased at exactly the size asked for and a growing icon
     * grows continuously instead of jumping from one whole pixel size to the next.
     */
    private static void drawFractional(GuiGraphics graphics, String name, PixelSource master, float cx, float cy,
                                       float size, int color, int scale) {
        float extent = Math.min(MAX_ICON_PIXELS, Math.max(2.0f, Math.round(size * scale * 8.0f) / 8.0f));
        int pixels = (int) Math.ceil(extent);
        String key = name + "/f/" + pixels + "/" + Math.round(extent * 8.0f) + "/" + Integer.toHexString(color);
        Object handle = texture(key, pixels, pixels, () -> {
            int[] source = SOURCES.get(name);
            if (source == null) {
                source = master.pixels();
                SOURCES.put(name, source);
            }
            return resample(source, SOURCE_PIXELS, pixels, extent, color);
        });
        if (handle != null) {
            drawOnDeviceGrid(graphics, handle, cx, cy, pixels, scale);
        }
    }

    /**
     * The master picture, shrunk to {@code extent} pixels in the middle of a square of {@code pixels},
     * tinted with {@code color}. Each result pixel averages the master pixels it covers.
     */
    private static int[] resample(int[] source, int sourcePixels, int pixels, float extent, int color) {
        int[] out = new int[pixels * pixels];
        float margin = (pixels - extent) / 2.0f;
        float ratio = sourcePixels / extent;
        int rgb = color & 0xFFFFFF;
        float tint = ((color >>> 24) & 0xFF) / 255.0f;
        for (int y = 0; y < pixels; y++) {
            float v0 = (y - margin) * ratio;
            float v1 = (y + 1 - margin) * ratio;
            for (int x = 0; x < pixels; x++) {
                float u0 = (x - margin) * ratio;
                float u1 = (x + 1 - margin) * ratio;
                float sum = 0.0f;
                for (int sy = Math.max(0, (int) Math.floor(v0)); sy < Math.min(sourcePixels, (int) Math.ceil(v1)); sy++) {
                    float wy = Math.min(sy + 1, v1) - Math.max(sy, v0);
                    for (int sx = Math.max(0, (int) Math.floor(u0)); sx < Math.min(sourcePixels, (int) Math.ceil(u1)); sx++) {
                        float wx = Math.min(sx + 1, u1) - Math.max(sx, u0);
                        sum += ((source[sy * sourcePixels + sx] >>> 24) & 0xFF) * wx * wy;
                    }
                }
                float alpha = sum / (ratio * ratio) * tint;
                out[y * pixels + x] = (Math.max(0, Math.min(255, Math.round(alpha))) << 24) | rgb;
            }
        }
        return out;
    }

    private static int rasterPixelsOfDevice(int device) {
        int full = Math.max(1, Math.min(MAX_ICON_PIXELS, device));
        if (ConfigManager.client().smoothGraphics) {
            return full;
        }
        return Math.max(3, full / PIXEL_BLOCK);
    }

    /** Draws the texture {@code device} device pixels wide, its top left corner on a whole device pixel. */
    private static void drawOnDeviceGrid(GuiGraphics graphics, Object handle, float cx, float cy, int device,
                                         int scale) {
        float left = Math.round(cx * scale - device / 2.0f) / (float) scale;
        float top = Math.round(cy * scale - device / 2.0f) / (float) scale;
        Platform.pushScale(graphics, left, top, device / (float) scale);
        Platform.drawTexture(graphics, handle, 0, 0, 1, 1);
        Platform.popMatrix(graphics);
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
