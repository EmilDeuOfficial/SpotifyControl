package de.emilo.spotifycontrol.lyrics;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import de.emilo.spotifycontrol.SpotifyControl;
import de.emilo.spotifycontrol.config.ConfigManager;
import de.emilo.spotifycontrol.spotify.model.Track;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Knows which tracks have synced lyrics, so the track lists can mark them.
 *
 * <p>A list hands over its tracks as soon as they are loaded, and a few lookups run side by side
 * in the background, so the marks arrive with the list instead of trickling in row by row. The
 * newest request goes first: those are the tracks on screen. The answers are kept on disk, so a
 * list that was seen before is marked at once. A "no lyrics" answer is asked again after a week,
 * because LRCLIB keeps growing.</p>
 *
 * <p>Nothing is looked up while the world lyrics are switched off, because the lookup is what
 * sends track names to LRCLIB.</p>
 */
public final class LyricsIndex {

    public enum Status {
        /** Not known yet: not asked for, waiting in the queue or being looked up. */
        UNKNOWN,
        AVAILABLE,
        MISSING
    }

    /** One stored answer: whether there are lyrics, and when that was found out. */
    private record Entry(boolean available, long checkedAtMillis) {
    }

    private static final int WORKERS = 2;
    /** How often one track is tried again after a failed lookup before it waits for a redraw. */
    private static final int MAX_TRIES = 4;
    private static final int MAX_QUEUED = 200;
    private static final long MISSING_LIFETIME_MILLIS = 7L * 24L * 60L * 60L * 1000L;
    private static final long SAVE_EVERY_MILLIS = 15_000L;
    private static final Gson GSON = new Gson();

    private static final Map<String, Entry> ENTRIES = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> QUEUED = new ConcurrentHashMap<>();
    private static final Map<String, Integer> TRIES = new ConcurrentHashMap<>();
    private static final Deque<Track> QUEUE = new ArrayDeque<>();
    private static boolean started;
    private static volatile boolean dirty;

    private LyricsIndex() {
    }

    /** The lyrics status of a track, and a lookup in the background if it is not known yet. */
    public static Status status(Track track) {
        if (!usable(track)) {
            return Status.UNKNOWN;
        }
        Entry known = fresh(track.uri());
        if (known != null) {
            return known.available() ? Status.AVAILABLE : Status.MISSING;
        }
        request(track);
        return Status.UNKNOWN;
    }

    /** Starts the lookups for a list that has just been loaded, before its rows are drawn. */
    public static void prefetch(List<Track> tracks) {
        if (!ConfigManager.client().worldLyrics) {
            return;
        }
        // Queued back to front, so the first rows of the list end up at the head of the queue.
        for (int i = tracks.size() - 1; i >= 0; i--) {
            Track track = tracks.get(i);
            if (usable(track) && fresh(track.uri()) == null) {
                request(track);
            }
        }
    }

    static void record(String uri, boolean available) {
        ENTRIES.put(uri, new Entry(available, System.currentTimeMillis()));
        dirty = true;
    }

    private static boolean usable(Track track) {
        return track != null && !track.uri().isEmpty() && !track.title().isEmpty()
                && !track.uri().startsWith("spotify:local:") && ConfigManager.client().worldLyrics;
    }

    private static Entry fresh(String uri) {
        start();
        Entry entry = ENTRIES.get(uri);
        if (entry == null) {
            return null;
        }
        if (!entry.available() && System.currentTimeMillis() - entry.checkedAtMillis() > MISSING_LIFETIME_MILLIS) {
            return null;
        }
        return entry;
    }

    private static void request(Track track) {
        if (QUEUED.putIfAbsent(track.uri(), Boolean.TRUE) != null) {
            return;
        }
        synchronized (QUEUE) {
            QUEUE.addFirst(track);
            while (QUEUE.size() > MAX_QUEUED) {
                QUEUED.remove(QUEUE.removeLast().uri());
            }
            QUEUE.notifyAll();
        }
    }

    private static synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        load();
        for (int i = 0; i < WORKERS; i++) {
            Thread worker = new Thread(LyricsIndex::work, "SpotifyControl lyrics index " + (i + 1));
            worker.setDaemon(true);
            worker.start();
        }
        Thread saver = new Thread(LyricsIndex::keepSaving, "SpotifyControl lyrics index saver");
        saver.setDaemon(true);
        saver.start();
    }

    private static void work() {
        while (true) {
            Track next;
            synchronized (QUEUE) {
                while (QUEUE.isEmpty()) {
                    try {
                        QUEUE.wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                next = QUEUE.removeFirst();
            }
            boolean failed = false;
            if (fresh(next.uri()) == null && ConfigManager.client().worldLyrics) {
                failed = LyricsFetcher.fetch(next) == null;
            }
            QUEUED.remove(next.uri());
            if (failed) {
                // Straight back to the front of the queue: the fetcher already waits out whatever
                // pause LRCLIB asked for. After a few tries the track waits until it is drawn again.
                int tries = TRIES.merge(next.uri(), 1, Integer::sum);
                if (tries < MAX_TRIES) {
                    request(next);
                } else {
                    TRIES.remove(next.uri());
                }
            } else {
                TRIES.remove(next.uri());
            }
        }
    }

    private static Path file() {
        // Version 2: the first file could hold songs wrongly marked as having no lyrics, because a
        // busy LRCLIB was read as "nothing found". Starting a new file drops those.
        return ConfigManager.configDirectory().resolve("lyrics_index_v2.json");
    }

    private static void load() {
        try {
            Path file = file();
            if (!Files.isRegularFile(file)) {
                return;
            }
            Map<String, Entry> stored = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                    new TypeToken<HashMap<String, Entry>>() { }.getType());
            if (stored != null) {
                ENTRIES.putAll(stored);
            }
        } catch (Exception e) {
            SpotifyControl.LOGGER.warn("Could not read the lyrics index, starting a new one", e);
        }
    }

    private static void keepSaving() {
        while (true) {
            try {
                Thread.sleep(SAVE_EVERY_MILLIS);
            } catch (InterruptedException e) {
                return;
            }
            if (!dirty) {
                continue;
            }
            dirty = false;
            try {
                Files.writeString(file(), GSON.toJson(new HashMap<>(ENTRIES)), StandardCharsets.UTF_8);
            } catch (Exception e) {
                SpotifyControl.LOGGER.warn("Could not save the lyrics index", e);
            }
        }
    }
}
