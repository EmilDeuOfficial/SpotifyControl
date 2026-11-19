package de.emilo.spotifycontrol.lyrics;

import de.emilo.spotifycontrol.config.ClientConfig;
import de.emilo.spotifycontrol.gui.Theme;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.Locale;

/**
 * The colours of the lyrics, apart from the colour theme of the interface.
 *
 * <p>Each choice is a list of colour stops. One stop is a plain colour, several are a gradient
 * that runs along the letters of a word from the first stop to the last. THEME follows the accent
 * colour of the interface and is the only one that has no stops of its own. CUSTOM and
 * CUSTOM_GRADIENT are made from the hue, saturation and brightness sliders in the settings.</p>
 */
public enum LyricsColor {

    THEME("Theme colour"),
    WHITE("White", 0xFFFFFF),
    GOLD("Gold", 0xFFD34D),
    ROSE("Rose", 0xFF6FA8),
    SKY("Sky", 0x6CC8FF),
    MINT("Mint", 0x7DFFB0),
    LAVA("Lava", 0xFFE259, 0xFF8A3D, 0xE8312B),
    OCEAN("Ocean", 0x7CF7FF, 0x2F7BFF),
    SUNSET("Sunset", 0xFFC15E, 0xFF5E7E, 0x8A4DFF),
    CANDY("Candy", 0xFF8AD8, 0x8AF3FF),
    AURORA("Aurora", 0x5CFFB0, 0x4D9EFF, 0xB05CFF),
    RAINBOW("Rainbow", 0xFF4D4D, 0xFFB84D, 0xFFF24D, 0x5CFF7A, 0x4DC8FF, 0xB06BFF),
    CUSTOM("Custom colour"),
    CUSTOM_GRADIENT("Custom gradient");

    private final String label;
    private final int[] stops;

    LyricsColor(String label, int... stops) {
        this.label = label;
        this.stops = stops;
    }

    public String label() {
        return label;
    }

    /** The colour of that name, or THEME for a name this version does not know. */
    public static LyricsColor of(String name) {
        if (name != null) {
            String wanted = name.trim().toUpperCase(Locale.ROOT);
            for (LyricsColor color : values()) {
                if (color.name().equals(wanted)) {
                    return color;
                }
            }
        }
        return THEME;
    }

    /**
     * The colour stops, as 0xRRGGBB. THEME has no colour of its own, so it takes the accent colour
     * of the interface, lightened by {@code theme} for the words that alternate in the world.
     */
    public int[] stops(ClientConfig config, float theme) {
        return switch (this) {
            case THEME -> new int[] {Theme.mix(Theme.accent(), 0xFFFFFFFF, theme) & 0xFFFFFF};
            case CUSTOM -> new int[] {
                    hsv(config.lyricsCustom1Hue, config.lyricsCustom1Saturation, config.lyricsCustom1Brightness)};
            case CUSTOM_GRADIENT -> new int[] {
                    hsv(config.lyricsCustom1Hue, config.lyricsCustom1Saturation, config.lyricsCustom1Brightness),
                    hsv(config.lyricsCustom2Hue, config.lyricsCustom2Saturation, config.lyricsCustom2Brightness)};
            default -> stops;
        };
    }

    /** The text with every letter in its place of the gradient, in the font and weight of {@code base}. */
    public static MutableComponent build(String text, int[] stops, Style base) {
        MutableComponent result = Component.empty();
        int[] points = text.codePoints().toArray();
        for (int i = 0; i < points.length; i++) {
            float at = points.length <= 1 ? 0.0f : (float) i / (points.length - 1);
            result.append(Component.literal(new String(Character.toChars(points[i])))
                    .withStyle(base.withColor(sample(stops, at))));
        }
        return result;
    }

    /** The colour at {@code at}, 0 to 1, along the stops. */
    public static int sample(int[] stops, float at) {
        if (stops.length == 1) {
            return stops[0];
        }
        float position = Math.max(0.0f, Math.min(1.0f, at)) * (stops.length - 1);
        int from = Math.min(stops.length - 2, (int) position);
        float mix = position - from;
        int a = stops[from];
        int b = stops[from + 1];
        int red = Math.round(((a >> 16) & 0xFF) * (1.0f - mix) + ((b >> 16) & 0xFF) * mix);
        int green = Math.round(((a >> 8) & 0xFF) * (1.0f - mix) + ((b >> 8) & 0xFF) * mix);
        int blue = Math.round((a & 0xFF) * (1.0f - mix) + (b & 0xFF) * mix);
        return (red << 16) | (green << 8) | blue;
    }

    /** Hue in degrees, saturation and brightness in percent, as 0xRRGGBB. */
    public static int hsv(int hue, int saturation, int brightness) {
        float h = ((hue % 360) + 360) % 360 / 60.0f;
        float s = Math.max(0, Math.min(100, saturation)) / 100.0f;
        float v = Math.max(0, Math.min(100, brightness)) / 100.0f;
        int sector = (int) h;
        float f = h - sector;
        float p = v * (1.0f - s);
        float q = v * (1.0f - s * f);
        float t = v * (1.0f - s * (1.0f - f));
        float r;
        float g;
        float b;
        switch (sector % 6) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        return (Math.round(r * 255) << 16) | (Math.round(g * 255) << 8) | Math.round(b * 255);
    }
}
