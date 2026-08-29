package de.emilo.spotifycontrol.spotify.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public final class Json {

    private Json() {
    }

    public static String string(JsonObject json, String key) {
        if (json == null) {
            return "";
        }
        JsonElement element = json.get(key);
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }

    public static long number(JsonObject json, String key) {
        if (json == null) {
            return 0L;
        }
        JsonElement element = json.get(key);
        return element == null || element.isJsonNull() ? 0L : element.getAsLong();
    }

    public static long numberOrDefault(JsonObject json, String key, long fallback) {
        if (json == null) {
            return fallback;
        }
        JsonElement element = json.get(key);
        return element == null || element.isJsonNull() ? fallback : element.getAsLong();
    }

    public static boolean bool(JsonObject json, String key) {
        if (json == null) {
            return false;
        }
        JsonElement element = json.get(key);
        return element != null && !element.isJsonNull() && element.getAsBoolean();
    }

    public static JsonObject object(JsonObject json, String key) {
        if (json == null) {
            return null;
        }
        JsonElement element = json.get(key);
        return element == null || !element.isJsonObject() ? null : element.getAsJsonObject();
    }

    public static JsonArray array(JsonObject json, String key) {
        if (json == null) {
            return null;
        }
        JsonElement element = json.get(key);
        return element == null || !element.isJsonArray() ? null : element.getAsJsonArray();
    }

    public static String firstImage(JsonArray images) {
        if (images == null || images.isEmpty()) {
            return "";
        }
        JsonObject best = null;
        for (int i = 0; i < images.size(); i++) {
            if (!images.get(i).isJsonObject()) {
                continue;
            }
            JsonObject candidate = images.get(i).getAsJsonObject();
            long width = number(candidate, "width");
            if (best == null || (width >= 200 && width < number(best, "width"))) {
                best = candidate;
            }
        }
        return best == null ? "" : string(best, "url");
    }

    public static String formatMillis(long millis) {
        long totalSeconds = Math.max(0L, millis) / 1000L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return minutes + ":" + (seconds < 10 ? "0" : "") + seconds;
    }
}
