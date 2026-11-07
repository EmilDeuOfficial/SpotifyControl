package de.emilo.spotifycontrol.lyrics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.emilo.spotifycontrol.SpotifyControl;
import de.emilo.spotifycontrol.spotify.model.Track;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Looks up time synced lyrics on LRCLIB (lrclib.net).
 *
 * <p>The Spotify Web API offers no lyrics, so they come from LRCLIB, a free and open lyrics
 * database that needs no account or key. Only the track title, the first artist, the album name and
 * the duration are sent. Lyrics without timestamps are ignored, because the world lyrics have to
 * appear in time with the music.</p>
 */
final class LyricsFetcher {

    private static final String BASE = "https://lrclib.net/api/";
    private static final String USER_AGENT =
            "SpotifyControl (https://github.com/EmilDeuOfficial/SpotifyControl)";
    private static final Pattern STAMP = Pattern.compile("\\[(\\d+):(\\d+(?:\\.\\d+)?)]");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** One timed line, start in milliseconds from the beginning of the track. */
    record Line(long startMs, String text) {
    }

    /** Recently looked up tracks, so a song checked in a list is not fetched again when it plays. */
    private static final int CACHE_SIZE = 64;
    private static final Map<String, List<Line>> CACHE = new LinkedHashMap<>(CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<Line>> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    private LyricsFetcher() {
    }

    /**
     * Returns the synced lines, an empty list when the track has none, or null when the lookup
     * failed and the answer is unknown.
     */
    static List<Line> fetch(Track track) {
        synchronized (CACHE) {
            List<Line> cached = CACHE.get(track.uri());
            if (cached != null) {
                return cached;
            }
        }
        List<Line> lines = lookup(track);
        if (lines != null) {
            synchronized (CACHE) {
                CACHE.put(track.uri(), lines);
            }
            LyricsIndex.record(track.uri(), !lines.isEmpty());
        }
        return lines;
    }

    private static List<Line> lookup(Track track) {
        String artist = track.artists().isEmpty() ? track.artist() : track.artists().get(0).name();
        try {
            // One search per track. It already carries the synced lyrics of every match, and the
            // entry whose length is closest to the track wins. The exact lookup used before cost
            // a second request per track, and with a whole playlist being checked at once that
            // was what ran into LRCLIB's rate limit.
            String found = get("search?track_name=" + enc(track.title()) + "&artist_name=" + enc(artist));
            if (found == null) {
                return List.of();
            }
            JsonArray results = JsonParser.parseString(found).getAsJsonArray();
            JsonObject best = null;
            double bestGap = Double.MAX_VALUE;
            for (JsonElement element : results) {
                JsonObject candidate = element.getAsJsonObject();
                if (!hasSynced(candidate)) {
                    continue;
                }
                double gap = candidate.has("duration") && !candidate.get("duration").isJsonNull()
                        ? Math.abs(candidate.get("duration").getAsDouble() - track.durationMs() / 1000.0)
                        : 1000.0;
                if (gap < bestGap) {
                    bestGap = gap;
                    best = candidate;
                }
            }
            return best == null ? List.of() : fromRecord(best);
        } catch (RateLimited e) {
            backOff();
            return null;
        } catch (Exception e) {
            SpotifyControl.LOGGER.warn("Lyrics lookup failed for '{}': {}", track.title(), e.toString());
            pausedUntilMillis = System.currentTimeMillis() + 1000L;
            return null;
        }
    }

    /** Thrown when LRCLIB asks to slow down. */
    private static final class RateLimited extends Exception {
        RateLimited() {
            super(null, null, false, false);
        }
    }

    /** Until when no request may be sent, because LRCLIB asked to slow down. */
    private static volatile long pausedUntilMillis;
    private static volatile long backOffMillis = 1000L;

    private static void backOff() {
        long wait = backOffMillis;
        pausedUntilMillis = System.currentTimeMillis() + wait;
        backOffMillis = Math.min(30_000L, wait * 2L);
        SpotifyControl.LOGGER.debug("LRCLIB rate limit, pausing lookups for {} ms", wait);
    }

    /** Waits out a pause LRCLIB asked for, so every thread that looks lyrics up keeps to it. */
    private static void waitForTurn() throws InterruptedException {
        long wait = pausedUntilMillis - System.currentTimeMillis();
        if (wait > 0L) {
            Thread.sleep(wait);
        }
    }

    private static String get(String path) throws Exception {
        waitForTurn();
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE + path))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        if (status == 200) {
            backOffMillis = 1000L;
            return response.body();
        }
        if (status == 429) {
            throw new RateLimited();
        }
        if (status == 404) {
            return null;
        }
        // Anything else, a busy or rate limited service above all, says nothing about the track.
        // Reading it as "no lyrics" marked songs that have lyrics as missing for a week.
        throw new IllegalStateException("LRCLIB answered " + status);
    }

    private static boolean hasSynced(JsonObject record) {
        return record.has("syncedLyrics") && !record.get("syncedLyrics").isJsonNull()
                && !record.get("syncedLyrics").getAsString().isBlank();
    }

    private static List<Line> fromRecord(JsonObject record) {
        if (!hasSynced(record)) {
            return List.of();
        }
        return parse(record.get("syncedLyrics").getAsString());
    }

    /** Reads LRC text. A line may carry several timestamps when it repeats. */
    static List<Line> parse(String lrc) {
        List<Line> lines = new ArrayList<>();
        for (String raw : lrc.split("\\R")) {
            Matcher matcher = STAMP.matcher(raw);
            List<Long> starts = new ArrayList<>();
            int textFrom = 0;
            while (matcher.find() && matcher.start() == textFrom) {
                long minutes = Long.parseLong(matcher.group(1));
                double seconds = Double.parseDouble(matcher.group(2));
                starts.add(minutes * 60_000L + Math.round(seconds * 1000.0));
                textFrom = matcher.end();
            }
            String text = raw.substring(textFrom).trim();
            for (long start : starts) {
                lines.add(new Line(start, text));
            }
        }
        lines.sort((a, b) -> Long.compare(a.startMs(), b.startMs()));
        return lines;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
