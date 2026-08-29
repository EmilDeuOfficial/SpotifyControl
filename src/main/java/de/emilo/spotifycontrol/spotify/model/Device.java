package de.emilo.spotifycontrol.spotify.model;

import com.google.gson.JsonObject;

public record Device(String id, String name, String type, boolean active, boolean restricted, int volumePercent) {

    public static Device from(JsonObject json) {
        return new Device(
                Json.string(json, "id"),
                Json.string(json, "name"),
                Json.string(json, "type"),
                Json.bool(json, "is_active"),
                Json.bool(json, "is_restricted"),
                (int) Json.number(json, "volume_percent"));
    }
}
