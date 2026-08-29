package de.emilo.spotifycontrol.spotify.model;

import com.google.gson.JsonObject;

public record PlaybackState(boolean active, boolean playing, Track track, long progressMs, boolean shuffle,
                            String repeatMode, int volumePercent, String deviceId, String deviceName,
                            String contextUri) {

    public static final PlaybackState IDLE =
            new PlaybackState(false, false, Track.NONE, 0L, false, "off", -1, "", "", "");

    public static PlaybackState from(JsonObject json) {
        if (json == null || json.isJsonNull() || !json.has("item")) {
            return IDLE;
        }
        JsonObject device = Json.object(json, "device");
        JsonObject context = Json.object(json, "context");
        return new PlaybackState(
                true,
                Json.bool(json, "is_playing"),
                Track.from(Json.object(json, "item")),
                Json.number(json, "progress_ms"),
                Json.bool(json, "shuffle_state"),
                Json.string(json, "repeat_state"),
                device == null ? -1 : (int) Json.numberOrDefault(device, "volume_percent", -1),
                device == null ? "" : Json.string(device, "id"),
                device == null ? "" : Json.string(device, "name"),
                context == null ? "" : Json.string(context, "uri"));
    }

    public PlaybackState withProgress(long millis) {
        return new PlaybackState(active, playing, track, millis, shuffle, repeatMode, volumePercent, deviceId,
                deviceName, contextUri);
    }

    public String formattedProgress() {
        return Json.formatMillis(progressMs);
    }
}
