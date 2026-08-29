package de.emilo.spotifycontrol.config;

import de.emilo.spotifycontrol.SpotifyControl;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public final class Lang {

    private static final Map<String, String> MESSAGES = new HashMap<>();
    private static final Map<String, String> FALLBACK = new HashMap<>();

    private Lang() {
    }

    public static void load(String language) {
        FALLBACK.clear();
        MESSAGES.clear();
        read("en", FALLBACK);
        if (!"en".equals(language)) {
            read(language, MESSAGES);
        }
    }

    private static void read(String language, Map<String, String> target) {
        try (InputStream stream = Lang.class.getResourceAsStream("/lang/messages_" + language + ".yml")) {
            if (stream == null) {
                SpotifyControl.LOGGER.warn("Language file for '{}' not found, falling back to English", language);
                return;
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int separator = trimmed.indexOf(':');
                if (separator <= 0) {
                    continue;
                }
                String key = trimmed.substring(0, separator).trim();
                String value = trimmed.substring(separator + 1).trim();
                target.put(key, unquote(value));
            }
        } catch (Exception e) {
            SpotifyControl.LOGGER.error("Failed to read language file for '{}'", language, e);
        }
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return value.replace("\\\"", "\"").replace("\\n", "\n");
    }

    public static String raw(String key) {
        String value = MESSAGES.get(key);
        if (value == null) {
            value = FALLBACK.get(key);
        }
        return value == null ? key : value;
    }

    public static String get(String key, Object... placeholders) {
        String value = raw(key);
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            value = value.replace("{" + placeholders[i] + "}", String.valueOf(placeholders[i + 1]));
        }
        return value;
    }

    public static String plain(String key, Object... placeholders) {
        return strip(get(key, placeholders));
    }

    public static Component chat(String key, Object... placeholders) {
        return Component.literal(colorize(raw("prefix") + get(key, placeholders)));
    }

    public static Component text(String key, Object... placeholders) {
        return Component.literal(colorize(get(key, placeholders)));
    }

    private static String colorize(String value) {
        return value.replace('&', ChatFormatting.PREFIX_CODE);
    }

    private static String strip(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '&' && i + 1 < value.length() && isFormatChar(value.charAt(i + 1))) {
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    private static boolean isFormatChar(char c) {
        return "0123456789abcdefklmnorABCDEFKLMNOR".indexOf(c) >= 0;
    }
}
