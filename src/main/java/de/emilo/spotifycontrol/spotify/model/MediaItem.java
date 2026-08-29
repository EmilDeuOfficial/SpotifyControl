package de.emilo.spotifycontrol.spotify.model;

import com.google.gson.JsonObject;

public record MediaItem(Kind kind, String uri, String id, String name, String subtitle, String imageUrl,
                        int trackCount, boolean owned) {

    public enum Kind {
        PLAYLIST,
        ALBUM,
        ARTIST
    }

    public static MediaItem playlist(JsonObject json, String ownerId) {
        JsonObject owner = Json.object(json, "owner");
        String ownerName = owner == null ? "" : Json.string(owner, "display_name");
        JsonObject tracks = Json.object(json, "tracks");
        int count = tracks == null ? 0 : (int) Json.number(tracks, "total");
        boolean owned = owner != null && Json.string(owner, "id").equals(ownerId);
        return new MediaItem(Kind.PLAYLIST,
                Json.string(json, "uri"),
                Json.string(json, "id"),
                Json.string(json, "name"),
                ownerName,
                Json.firstImage(Json.array(json, "images")),
                count,
                owned);
    }

    public static MediaItem album(JsonObject json) {
        return new MediaItem(Kind.ALBUM,
                Json.string(json, "uri"),
                Json.string(json, "id"),
                Json.string(json, "name"),
                artistNames(json),
                Json.firstImage(Json.array(json, "images")),
                (int) Json.number(json, "total_tracks"),
                false);
    }

    public static MediaItem artist(JsonObject json) {
        return new MediaItem(Kind.ARTIST,
                Json.string(json, "uri"),
                Json.string(json, "id"),
                Json.string(json, "name"),
                "",
                Json.firstImage(Json.array(json, "images")),
                0,
                false);
    }

    private static String artistNames(JsonObject json) {
        var artists = Json.array(json, "artists");
        if (artists == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < artists.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(Json.string(artists.get(i).getAsJsonObject(), "name"));
        }
        return builder.toString();
    }
}
