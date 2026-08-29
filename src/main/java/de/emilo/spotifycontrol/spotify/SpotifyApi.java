package de.emilo.spotifycontrol.spotify;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.emilo.spotifycontrol.SpotifyControl;
import de.emilo.spotifycontrol.config.ConfigManager;
import de.emilo.spotifycontrol.spotify.model.Device;
import de.emilo.spotifycontrol.spotify.model.Json;
import de.emilo.spotifycontrol.spotify.model.MediaItem;
import de.emilo.spotifycontrol.spotify.model.PlaybackState;
import de.emilo.spotifycontrol.spotify.model.SearchResults;
import de.emilo.spotifycontrol.spotify.model.Track;
import de.emilo.spotifycontrol.spotify.model.TrackPage;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SpotifyApi {

    private static final String BASE = "https://api.spotify.com";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final TokenStore tokens;
    private final SpotifyAuth auth;

    private volatile String lastError = "";

    public SpotifyApi(TokenStore tokens, SpotifyAuth auth) {
        this.tokens = tokens;
        this.auth = auth;
    }

    public String lastError() {
        return lastError;
    }

    public boolean loadProfile() {
        JsonObject me = get("/v1/me", Map.of());
        if (me == null) {
            return false;
        }
        tokens.userId = Json.string(me, "id");
        tokens.displayName = Json.string(me, "display_name");
        tokens.product = Json.string(me, "product");
        tokens.country = Json.string(me, "country");
        if (tokens.clientId.isEmpty()) {
            tokens.clientId = ConfigManager.client().spotifyClientId.trim();
        }
        tokens.save();
        return true;
    }

    public PlaybackState playback() {
        JsonObject json = get("/v1/me/player", Map.of("additional_types", "track"));
        return json == null ? PlaybackState.IDLE : PlaybackState.from(json);
    }

    public List<Device> devices() {
        JsonObject json = get("/v1/me/player/devices", Map.of());
        List<Device> devices = new ArrayList<>();
        JsonArray array = json == null ? null : Json.array(json, "devices");
        if (array != null) {
            for (int i = 0; i < array.size(); i++) {
                devices.add(Device.from(array.get(i).getAsJsonObject()));
            }
        }
        return devices;
    }

    public List<MediaItem> playlists() {
        List<MediaItem> items = new ArrayList<>();
        for (JsonObject page : pages("/v1/me/playlists", Map.of(), 1000)) {
            JsonArray array = Json.array(page, "items");
            if (array == null) {
                continue;
            }
            for (int i = 0; i < array.size(); i++) {
                if (array.get(i).isJsonObject()) {
                    items.add(MediaItem.playlist(array.get(i).getAsJsonObject(), tokens.userId));
                }
            }
        }
        return items;
    }

    /**
      * Reads a playlist through {@code /items}. The {@code /tracks} variant was removed with the
      * February 2026 API revision and now answers 403 for every playlist. Entries carry the track
      * under {@code item}; {@code track} is the deprecated alias and is still accepted here.
      */
    /** Tracks fetched per page. Also the documented maximum for these endpoints. */
    public static final int TRACK_PAGE_SIZE = 50;

    /**
      * Reads one page of a track listing. The page is described by Spotify's paging object, so
      * {@code total} and the presence of {@code next} come straight from the response rather than
      * being guessed from how many usable tracks survived filtering.
      */
    private TrackPage trackPage(String path, int offset, String... nested) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("limit", String.valueOf(TRACK_PAGE_SIZE));
        query.put("offset", String.valueOf(offset));
        if (!tokens.country.isEmpty()) {
            query.put("market", tokens.country);
        }
        JsonObject page = get(path, query);
        if (page == null) {
            return TrackPage.FAILED;
        }
        List<Track> tracks = extractTracks(page, nested);
        JsonArray items = Json.array(page, "items");
        int rawCount = items == null ? 0 : items.size();
        int total = (int) Json.numberOrDefault(page, "total", offset + rawCount);
        boolean complete = rawCount == 0 || page.get("next") == null || page.get("next").isJsonNull();
        return new TrackPage(tracks, rawCount, total, complete, false);
    }

    public TrackPage playlistTrackPage(String playlistId, int offset) {
        return trackPage("/v1/playlists/" + playlistId + "/items", offset, "item", "track");
    }

    /** Wraps a one-shot listing, reporting failure rather than an empty result. */
    public TrackPage singlePage(List<Track> tracks) {
        return tracks.isEmpty() && !lastError.isEmpty() ? TrackPage.FAILED : TrackPage.single(tracks);
    }

    public TrackPage likedTrackPage(int offset) {
        return trackPage("/v1/me/tracks", offset, "track");
    }

    /**
      * Album pages carry neither the album name nor its cover on the individual tracks, so both are
      * passed in from the item the user opened instead of costing an extra request per page.
      */
    public TrackPage albumTrackPage(String albumId, int offset, String albumName, String artwork) {
        TrackPage page = trackPage("/v1/albums/" + albumId + "/tracks", offset);
        if (page.failed()) {
            return page;
        }
        List<Track> enriched = new ArrayList<>(page.tracks().size());
        for (Track track : page.tracks()) {
            enriched.add(new Track(track.uri(), track.id(), track.title(), track.artist(), track.artists(),
                    albumName, artwork,
                    track.durationMs(), track.explicit(), track.playable()));
        }
        return new TrackPage(enriched, page.rawCount(), page.total(), page.complete(), false);
    }

    private static final int PAGE_SIZE = 50;

    private volatile boolean lastLoadIncomplete;

    public boolean lastLoadIncomplete() {
        return lastLoadIncomplete;
    }

    private List<JsonObject> pages(String path, Map<String, String> baseQuery, int maxItems) {
        lastLoadIncomplete = false;
        List<JsonObject> collected = new ArrayList<>();
        int offset = 0;
        while (offset < maxItems) {
            Map<String, String> query = new LinkedHashMap<>(baseQuery);
            query.put("limit", String.valueOf(PAGE_SIZE));
            query.put("offset", String.valueOf(offset));
            JsonObject page = get(path, query);
            if (page == null) {
                lastLoadIncomplete = true;
                return collected;
            }
            collected.add(page);
            JsonArray items = Json.array(page, "items");
            int received = items == null ? 0 : items.size();
            if (received == 0 || page.get("next") == null || page.get("next").isJsonNull()) {
                return collected;
            }
            offset += PAGE_SIZE;
        }
        lastLoadIncomplete = true;
        return collected;
    }

    /**
      * Looks one artist up, so a name clicked in a track row can open a page with a cover and a
      * title instead of an empty header. Returns null when the request failed.
      */
    public MediaItem artist(String artistId) {
        JsonObject json = get("/v1/artists/" + artistId, Map.of());
        return json == null || Json.string(json, "id").isEmpty() ? null : MediaItem.artist(json);
    }

    public List<Track> artistTopTracks(String artistId) {
        String market = tokens.country.isEmpty() ? "US" : tokens.country;
        JsonObject json = get("/v1/artists/" + artistId + "/top-tracks", Map.of("market", market));
        List<Track> tracks = new ArrayList<>();
        JsonArray array = json == null ? null : Json.array(json, "tracks");
        if (array != null) {
            for (int i = 0; i < array.size(); i++) {
                if (array.get(i).isJsonObject()) {
                    tracks.add(Track.from(array.get(i).getAsJsonObject()));
                }
            }
        }
        if (!tracks.isEmpty()) {
            return tracks;
        }
        // Top tracks is deprecated and is refused for apps in development mode. Falling back to a
        // search for the artist keeps the page useful instead of leaving it blank.
        JsonObject artist = get("/v1/artists/" + artistId, Map.of());
        String name = artist == null ? "" : Json.string(artist, "name");
        if (name.isEmpty()) {
            return tracks;
        }
        lastError = "";
        return search("artist:" + name).tracks();
    }

    public List<Track> recentlyPlayed() {
        JsonObject json = get("/v1/me/player/recently-played", Map.of("limit", "50"));
        return extractTracks(json, "track");
    }

    public List<Track> topTracks() {
        JsonObject json = get("/v1/me/top/tracks", Map.of("limit", "50", "time_range", "short_term"));
        List<Track> tracks = new ArrayList<>();
        JsonArray array = json == null ? null : Json.array(json, "items");
        if (array != null) {
            for (int i = 0; i < array.size(); i++) {
                tracks.add(Track.from(array.get(i).getAsJsonObject()));
            }
        }
        return tracks;
    }

    private static final int SEARCH_PAGE_SIZE = 10;
    private static final int SEARCH_PAGES = 3;

    public SearchResults search(String query) {
        List<Track> tracks = new ArrayList<>();
        List<MediaItem> playlists = new ArrayList<>();
        List<MediaItem> albums = new ArrayList<>();
        List<MediaItem> artists = new ArrayList<>();
        boolean anyPage = false;
        for (int page = 0; page < SEARCH_PAGES; page++) {
            JsonObject json = get("/v1/search", Map.of(
                    "q", query,
                    "type", "track,album,playlist,artist",
                    "limit", String.valueOf(SEARCH_PAGE_SIZE),
                    "offset", String.valueOf(page * SEARCH_PAGE_SIZE)));
            if (json == null) {
                break;
            }
            anyPage = true;
            int before = tracks.size();
            JsonObject trackWrapper = Json.object(json, "tracks");
            JsonArray trackItems = trackWrapper == null ? null : Json.array(trackWrapper, "items");
            if (trackItems != null) {
                for (int i = 0; i < trackItems.size(); i++) {
                    if (trackItems.get(i).isJsonObject()) {
                        tracks.add(Track.from(trackItems.get(i).getAsJsonObject()));
                    }
                }
            }
            playlists.addAll(collect(json, "playlists", raw -> MediaItem.playlist(raw, tokens.userId)));
            albums.addAll(collect(json, "albums", MediaItem::album));
            artists.addAll(collect(json, "artists", MediaItem::artist));
            if (tracks.size() - before < SEARCH_PAGE_SIZE) {
                break;
            }
        }
        if (!anyPage) {
            SpotifyControl.LOGGER.warn("Spotify search for \"{}\" returned nothing: {}", query, lastError);
            return SearchResults.EMPTY;
        }
        SpotifyControl.LOGGER.info("Spotify search \"{}\": {} tracks, {} playlists, {} albums, {} artists",
                query, tracks.size(), playlists.size(), albums.size(), artists.size());
        return new SearchResults(query, tracks, playlists, albums, artists);
    }

    public boolean play(String contextUri, List<String> trackUris, String offsetUri, String deviceId) {
        JsonObject body = new JsonObject();
        if (contextUri != null && !contextUri.isEmpty()) {
            body.addProperty("context_uri", contextUri);
            if (offsetUri != null && !offsetUri.isEmpty() && !offsetUri.startsWith("spotify:local:")) {
                JsonObject offset = new JsonObject();
                offset.addProperty("uri", offsetUri);
                body.add("offset", offset);
            }
        } else if (trackUris != null && !trackUris.isEmpty()) {
            JsonArray uris = new JsonArray();
            for (String uri : trackUris) {
                uris.add(uri);
            }
            body.add("uris", uris);
        }
        Map<String, String> query = deviceId == null || deviceId.isEmpty() ? Map.of() : Map.of("device_id", deviceId);
        return send("PUT", "/v1/me/player/play", query, body.toString());
    }

    public boolean resume(String deviceId) {
        Map<String, String> query = deviceId == null || deviceId.isEmpty() ? Map.of() : Map.of("device_id", deviceId);
        return send("PUT", "/v1/me/player/play", query, "{}");
    }

    public boolean pause() {
        return send("PUT", "/v1/me/player/pause", Map.of(), "");
    }

    public boolean next() {
        return send("POST", "/v1/me/player/next", Map.of(), "");
    }

    public boolean previous() {
        return send("POST", "/v1/me/player/previous", Map.of(), "");
    }

    public boolean seek(long positionMs) {
        return send("PUT", "/v1/me/player/seek", Map.of("position_ms", String.valueOf(positionMs)), "");
    }

    public boolean volume(int percent) {
        int clamped = Math.max(0, Math.min(100, percent));
        return send("PUT", "/v1/me/player/volume", Map.of("volume_percent", String.valueOf(clamped)), "");
    }

    public boolean shuffle(boolean enabled) {
        return send("PUT", "/v1/me/player/shuffle", Map.of("state", String.valueOf(enabled)), "");
    }

    public boolean repeat(String mode) {
        return send("PUT", "/v1/me/player/repeat", Map.of("state", mode), "");
    }

    public boolean queue(String trackUri) {
        return send("POST", "/v1/me/player/queue", Map.of("uri", trackUri), "");
    }

    public List<Track> playbackQueue() {
        JsonObject json = get("/v1/me/player/queue", Map.of());
        List<Track> tracks = new ArrayList<>();
        JsonArray array = json == null ? null : Json.array(json, "queue");
        if (array != null) {
            for (int i = 0; i < array.size(); i++) {
                if (array.get(i).isJsonObject()) {
                    Track track = Track.from(array.get(i).getAsJsonObject());
                    if (!track.isEmpty()) {
                        tracks.add(track);
                    }
                }
            }
        }
        return tracks;
    }

    public boolean transfer(String deviceId, boolean play) {
        JsonObject body = new JsonObject();
        JsonArray ids = new JsonArray();
        ids.add(deviceId);
        body.add("device_ids", ids);
        body.addProperty("play", play);
        return send("PUT", "/v1/me/player", Map.of(), body.toString());
    }

    /** Maximum number of URIs the consolidated library endpoints accept in one request. */
    public static final int LIBRARY_BATCH = 40;

    /**
      * Saves or removes a track. {@code PUT}/{@code DELETE /v1/me/tracks} were replaced by the
      * consolidated {@code /v1/me/library} endpoints, which take Spotify URIs rather than bare ids.
      */
    public boolean like(String trackId, boolean liked) {
        return send(liked ? "PUT" : "DELETE", "/v1/me/library",
                Map.of("uris", "spotify:track:" + trackId), null);
    }

    private static String trackUris(List<String> trackIds) {
        StringBuilder uris = new StringBuilder();
        for (String id : trackIds) {
            if (uris.length() > 0) {
                uris.append(',');
            }
            uris.append("spotify:track:").append(id);
        }
        return uris.toString();
    }

    public List<Boolean> likedStatus(List<String> trackIds) {
        List<Boolean> result = new ArrayList<>();
        if (trackIds.isEmpty()) {
            return result;
        }
        List<String> capped = trackIds.size() > LIBRARY_BATCH ? trackIds.subList(0, LIBRARY_BATCH) : trackIds;
        JsonObject json = getArrayWrapped("/v1/me/library/contains", Map.of("uris", trackUris(capped)));
        JsonArray array = json == null ? null : Json.array(json, "values");
        if (array != null) {
            for (int i = 0; i < array.size(); i++) {
                result.add(array.get(i).getAsBoolean());
            }
        }
        return result;
    }

    public boolean addToPlaylist(String playlistId, String trackUri) {
        JsonObject body = new JsonObject();
        JsonArray uris = new JsonArray();
        uris.add(trackUri);
        body.add("uris", uris);
        return send("POST", "/v1/playlists/" + playlistId + "/items", Map.of(), body.toString());
    }

    public boolean removeFromPlaylist(String playlistId, String trackUri) {
        JsonObject body = new JsonObject();
        JsonArray items = new JsonArray();
        JsonObject entry = new JsonObject();
        entry.addProperty("uri", trackUri);
        items.add(entry);
        body.add("items", items);
        return send("DELETE", "/v1/playlists/" + playlistId + "/items", Map.of(), body.toString());
    }

    /**
      * Moves one entry of a playlist to another position.
      *
      * <p>{@code insertBefore} counts positions in the list as it stands before the move, so moving
      * an entry further down has to aim one past its destination: the entry itself is still in the
      * way at that point. Spotify's own example is moving the first of ten items to the end with
      * range_start 0 and insert_before 10.
      *
      * <p>No snapshot id is sent. That would pin the change to one particular version of the
      * playlist and fail if anything else had touched it since - which is the safer choice for a
      * batch edit, but here the user is dragging a row they are looking at, and a refusal they
      * cannot act on is worse than applying the move to whatever the playlist looks like now.
      */
    public boolean reorderPlaylist(String playlistId, int from, int to) {
        if (from == to || from < 0 || to < 0) {
            return true;
        }
        JsonObject body = new JsonObject();
        body.addProperty("range_start", from);
        body.addProperty("insert_before", to > from ? to + 1 : to);
        body.addProperty("range_length", 1);
        return send("PUT", "/v1/playlists/" + playlistId + "/items", Map.of(), body.toString());
    }

    public MediaItem createPlaylist(String name, boolean isPublic) {
        JsonObject body = new JsonObject();
        body.addProperty("name", name);
        body.addProperty("public", isPublic);
        JsonObject created = request("POST", "/v1/me/playlists", Map.of(), body.toString());
        return created == null ? null : MediaItem.playlist(created, tokens.userId);
    }

    private List<MediaItem> collect(JsonObject root, String key, java.util.function.Function<JsonObject,
            MediaItem> mapper) {
        List<MediaItem> items = new ArrayList<>();
        JsonObject wrapper = Json.object(root, key);
        JsonArray array = wrapper == null ? null : Json.array(wrapper, "items");
        if (array != null) {
            for (int i = 0; i < array.size(); i++) {
                if (array.get(i).isJsonObject()) {
                    items.add(mapper.apply(array.get(i).getAsJsonObject()));
                }
            }
        }
        return items;
    }

    private List<Track> extractTracks(JsonObject json, String... nested) {
        List<Track> tracks = new ArrayList<>();
        JsonArray array = json == null ? null : Json.array(json, "items");
        if (array == null) {
            return tracks;
        }
        for (int i = 0; i < array.size(); i++) {
            if (!array.get(i).isJsonObject()) {
                continue;
            }
            JsonObject entry = array.get(i).getAsJsonObject();
            // Album pages carry the track object directly; playlist and library pages wrap it.
            JsonObject raw = nested.length == 0 ? entry : null;
            for (String key : nested) {
                raw = Json.object(entry, key);
                if (raw != null) {
                    break;
                }
            }
            if (raw != null) {
                Track track = Track.from(raw);
                if (!track.isEmpty()) {
                    tracks.add(track);
                }
            }
        }
        return tracks;
    }

    private JsonObject get(String path, Map<String, String> query) {
        return request("GET", path, query, null);
    }

    private JsonObject getArrayWrapped(String path, Map<String, String> query) {
        String body = raw("GET", path, query, null);
        if (body == null || body.isBlank()) {
            return null;
        }
        JsonObject wrapper = new JsonObject();
        wrapper.add("values", JsonParser.parseString(body).getAsJsonArray());
        return wrapper;
    }

    private boolean send(String method, String path, Map<String, String> query, String body) {
        return raw(method, path, query, body) != null;
    }

    private JsonObject request(String method, String path, Map<String, String> query, String body) {
        String response = raw(method, path, query, body);
        if (response == null || response.isBlank()) {
            return null;
        }
        try {
            return JsonParser.parseString(response).getAsJsonObject();
        } catch (Exception e) {
            SpotifyControl.LOGGER.debug("Unexpected response body for {} {}", method, path);
            return null;
        }
    }

    private volatile long rateLimitedUntilMillis;

    /**
      * Milliseconds until Spotify accepts requests again, zero when it is not throttling us. Pure
      * read: the render thread asks this every frame and must not change anything by doing so.
      */
    public long rateLimitedForMillis() {
        return Math.max(0L, rateLimitedUntilMillis - System.currentTimeMillis());
    }

    /**
      * Drops the block. Only meaningful together with tokens for a different app, because Spotify
      * recognises the app from the token that is sent, not from the client id in the config.
      */
    public void clearRateLimit() {
        rateLimitedUntilMillis = 0L;
    }

    private String raw(String method, String path, Map<String, String> query, String body) {
        if (rateLimitedForMillis() > 0L) {
            // Spotify told us exactly when to come back. Sending anything before then keeps the
            // penalty alive and blocks the single worker thread for everything else.
            lastError = "rate_limited";
            return null;
        }
        int lastStatus = 0;
        int rateLimitRetries = 0;
        int authRetries = 0;
        for (int attempt = 0; attempt < 6; attempt++) {
            if (!auth.refreshIfNeeded(error -> lastError = error)) {
                lastError = "not connected";
                return null;
            }
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(BASE + path + encode(query)))
                        .header("Authorization", "Bearer " + tokens.accessToken)
                        .timeout(Duration.ofSeconds(15));
                if (body == null) {
                    builder.method(method, HttpRequest.BodyPublishers.noBody());
                } else {
                    builder.header("Content-Type", "application/json");
                    builder.method(method, HttpRequest.BodyPublishers.ofString(body));
                }
                HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status == 401) {
                    lastStatus = 401;
                    if (authRetries++ >= 1) {
                        break;
                    }
                    tokens.expiresAtEpochSecond = 0L;
                    continue;
                }
                if (status == 429) {
                    lastStatus = 429;
                    long retryAfter = Math.max(1L, response.headers().firstValue("Retry-After")
                            .map(SpotifyApi::parseRetryAfter).orElse(30L));
                    rateLimitedUntilMillis = System.currentTimeMillis() + retryAfter * 1000L;
                    SpotifyControl.LOGGER.warn("Spotify rate limited {} {}, no requests for {}s",
                            method, path, retryAfter);
                    // Only a pause of a few seconds is worth sitting out on the worker thread.
                    // Anything longer becomes a global block instead of a retry.
                    if (retryAfter > 5L || rateLimitRetries++ >= 1) {
                        break;
                    }
                    Thread.sleep(retryAfter * 1000L);
                    continue;
                }
                if (status == 204) {
                    lastError = "";
                    return "";
                }
                if (status / 100 == 2) {
                    clearRateLimit();
                }
                if (status / 100 != 2) {
                    lastError = describeError(status, path, response.body());
                    SpotifyControl.LOGGER.warn("Spotify {} {} returned {}: {}", method, path, status,
                            abbreviate(response.body()));
                    return null;
                }
                lastError = "";
                return response.body();
            } catch (InterruptedException e) {
                lastError = "interrupted";
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception e) {
                lastError = e.getMessage() == null ? e.toString() : e.getMessage();
                SpotifyControl.LOGGER.warn("Spotify request failed: {} {}", method, path, e);
                return null;
            }
        }
        lastError = switch (lastStatus) {
            case 429 -> "rate_limited";
            case 401 -> "auth";
            case 0 -> "no response";
            default -> "HTTP " + lastStatus;
        };
        SpotifyControl.LOGGER.warn("Spotify {} {} gave up after retries: {}", method, path, lastError);
        return null;
    }

    private static long parseRetryAfter(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 1L;
        }
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        String single = body.replaceAll("\s+", " ").trim();
        return single.length() > 300 ? single.substring(0, 300) + "..." : single;
    }

    /**
      * Player restrictions that describe an ordinary state rather than a failure. Pausing an
      * already paused player or skipping past the end of a context answers 403 on a perfectly
      * healthy Premium account, so these must not reach the user as an error.
      */
    private static final Set<String> BENIGN_PLAYER_REASONS = Set.of(
            "ALREADY_PAUSED", "NOT_PAUSED", "ALREADY_PLAYING", "NOT_PLAYING_TRACK",
            "NOT_PLAYING_CONTEXT", "NOT_PLAYING_LOCALLY", "NO_PREV_TRACK", "NO_NEXT_TRACK",
            "NO_SPECIFIC_TRACK", "ENDLESS_CONTEXT", "CONTEXT_DISALLOW", "UNKNOWN");

    private String describeError(int status, String path, String body) {
        String reason = "";
        String message = "";
        try {
            JsonObject error = Json.object(JsonParser.parseString(body).getAsJsonObject(), "error");
            reason = Json.string(error, "reason");
            message = Json.string(error, "message");
        } catch (Exception ignored) {
            message = "";
        }
        if ("NO_ACTIVE_DEVICE".equals(reason) || "DEVICE_NOT_FOUND".equals(reason)) {
            return "no_device";
        }
        if ("PREMIUM_REQUIRED".equals(reason)) {
            return "premium";
        }
        if ("RATE_LIMITED".equals(reason)) {
            return "rate_limited";
        }
        boolean player = path.startsWith("/v1/me/player");
        if (player && status == 404) {
            return "no_device";
        }
        if (player && BENIGN_PLAYER_REASONS.contains(reason)) {
            return "";
        }
        if (status == 403 && path.startsWith("/v1/playlists/")) {
            return "playlist_locked";
        }
        // A bare 403 means many things. Only Spotify's own reason may claim "premium".
        return message.isEmpty() ? "HTTP " + status : message;
    }

    private static final String QUERY_SAFE =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~!$'()*,;:@/?";

    private static String encode(Map<String, String> query) {
        if (query.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder("?");
        for (Map.Entry<String, String> entry : new LinkedHashMap<>(query).entrySet()) {
            if (builder.length() > 1) {
                builder.append('&');
            }
            builder.append(encodeComponent(entry.getKey()))
                    .append('=')
                    .append(encodeComponent(entry.getValue()));
        }
        return builder.toString();
    }

    private static String encodeComponent(String value) {
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (byte raw : value.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (raw & 0xFF);
            if (QUERY_SAFE.indexOf(c) >= 0) {
                out.append(c);
            } else {
                out.append('%')
                        .append(Character.toUpperCase(Character.forDigit((raw >> 4) & 0xF, 16)))
                        .append(Character.toUpperCase(Character.forDigit(raw & 0xF, 16)));
            }
        }
        return out.toString();
    }
}
