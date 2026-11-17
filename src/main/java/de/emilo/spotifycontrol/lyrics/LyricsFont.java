package de.emilo.spotifycontrol.lyrics;

import de.emilo.spotifycontrol.platform.Platform;
import net.minecraft.network.chat.Style;

import java.util.Locale;

/**
 * The fonts the lyrics can be drawn in.
 *
 * <p>The first four are the ones Minecraft ships with. The rest are bundled with the mod under
 * {@code assets/spotifycontrol/font}, each a TrueType file with a font definition beside it, and
 * are free to redistribute under the SIL Open Font License 1.1 or the Apache License 2.0. Their
 * licenses are in the {@code licenses} folder of the jar. Every bundled font is sized so that
 * its capitals are as tall as Minecraft's, and shares its baseline, so a word sits at the same
 * height whichever font it is in. Each falls back to the default font for the characters it
 * does not have.</p>
 */
public enum LyricsFont {

    DEFAULT("Default", null, null),
    UNIFONT("Unifont", "minecraft", "uniform"),
    GALACTIC("Galactic (cipher)", "minecraft", "alt"),
    ILLAGER("Illager runes (cipher)", "minecraft", "illageralt"),
    PACIFICO("Pacifico", "spotifycontrol", "lyrics_pacifico"),
    LOBSTER("Lobster", "spotifycontrol", "lyrics_lobster"),
    CAVEAT("Caveat", "spotifycontrol", "lyrics_caveat"),
    MARKER("Permanent Marker", "spotifycontrol", "lyrics_permanentmarker"),
    BANGERS("Bangers", "spotifycontrol", "lyrics_bangers"),
    BEBAS("Bebas Neue", "spotifycontrol", "lyrics_bebasneue"),
    LUCKY("Luckiest Guy", "spotifycontrol", "lyrics_luckiestguy"),
    BLACKOPS("Black Ops One", "spotifycontrol", "lyrics_blackopsone"),
    CREEPSTER("Creepster", "spotifycontrol", "lyrics_creepster"),
    RIGHTEOUS("Righteous", "spotifycontrol", "lyrics_righteous"),
    AUDIOWIDE("Audiowide", "spotifycontrol", "lyrics_audiowide"),
    ORBITRON("Orbitron", "spotifycontrol", "lyrics_orbitron"),
    TYPEWRITER("Special Elite", "spotifycontrol", "lyrics_specialelite"),
    TERMINAL("VT323", "spotifycontrol", "lyrics_vt323"),
    PIXEL("Press Start 2P", "spotifycontrol", "lyrics_pressstart2p");

    private final String label;
    private final String namespace;
    private final String path;

    LyricsFont(String label, String namespace, String path) {
        this.label = label;
        this.namespace = namespace;
        this.path = path;
    }

    public String label() {
        return label;
    }

    /** The font of that name, or DEFAULT for a name this version does not know. */
    public static LyricsFont of(String name) {
        if (name != null) {
            String wanted = name.trim().toUpperCase(Locale.ROOT);
            for (LyricsFont font : values()) {
                if (font.name().equals(wanted)) {
                    return font;
                }
            }
        }
        return DEFAULT;
    }

    /** The style with this font set. The default font leaves the style as it is. */
    public Style apply(Style style) {
        return path == null ? style : Platform.withFont(style, namespace, path);
    }
}
