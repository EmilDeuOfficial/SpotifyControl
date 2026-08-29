package de.emilo.spotifycontrol.spotify.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * One playable entry.
 *
 * <p>{@code artist} is the names joined for display and {@code artists} is the same list with the
 * ids still attached. Both are kept: the joined string is what almost every caller wants and is not
 * worth rebuilding on every frame, while the list is what makes a single name clickable.
 */
public record Track(String uri, String id, String title, String artist, List<Credit> artists, String album,
                    String artworkUrl, long durationMs, boolean explicit, boolean playable) {

    /** One artist of a track, kept apart from the others so their page can be opened. */
    public record Credit(String id, String name) {
    }

    public static final Track NONE = new Track("", "", "", "", List.of(), "", "", 0L, false, true);

    public static Track from(JsonObject json) {
        if (json == null || json.isJsonNull()) {
            return NONE;
        }
        String uri = Json.string(json, "uri");
        String id = Json.string(json, "id");
        String title = Json.string(json, "name");
        List<Credit> credits = credits(json.getAsJsonArray("artists"));
        String artist = joinArtists(credits);
        JsonObject album = json.getAsJsonObject("album");
        String albumName = album == null ? "" : Json.string(album, "name");
        String artwork = album == null ? "" : Json.firstImage(Json.array(album, "images"));
        long duration = Json.number(json, "duration_ms");
        boolean explicit = Json.bool(json, "explicit");
        // is_playable only appears when the request named a market. Local files never play
        // through the Web API, and a restrictions object means the market blocks the track.
        boolean playable = !uri.startsWith("spotify:local:")
                && (!json.has("is_playable") || Json.bool(json, "is_playable"))
                && Json.object(json, "restrictions") == null;
        return new Track(uri, id, title, artist, credits, albumName, artwork, duration, explicit, playable);
    }

    private static List<Credit> credits(JsonArray artists) {
        if (artists == null) {
            return List.of();
        }
        List<Credit> credits = new ArrayList<>(artists.size());
        for (int i = 0; i < artists.size(); i++) {
            if (!artists.get(i).isJsonObject()) {
                continue;
            }
            JsonObject entry = artists.get(i).getAsJsonObject();
            String name = Json.string(entry, "name");
            if (!name.isEmpty()) {
                // The id is empty for local files and for the odd entry Spotify has no page for.
                // Those still get their name shown, they just are not clickable.
                credits.add(new Credit(Json.string(entry, "id"), name));
            }
        }
        return List.copyOf(credits);
    }

    /** The separator between two artist names, also used to measure where each one starts. */
    public static final String ARTIST_SEPARATOR = ", ";

    private static String joinArtists(List<Credit> credits) {
        StringBuilder builder = new StringBuilder();
        for (Credit credit : credits) {
            if (!builder.isEmpty()) {
                builder.append(ARTIST_SEPARATOR);
            }
            builder.append(credit.name());
        }
        return builder.toString();
    }

    public boolean isEmpty() {
        return uri.isEmpty();
    }

    public String formattedDuration() {
        return Json.formatMillis(durationMs);
    }
}
