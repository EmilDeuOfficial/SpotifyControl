package de.emilo.spotifycontrol.spotify;

import de.emilo.spotifycontrol.SpotifyControl;
import de.emilo.spotifycontrol.config.ConfigManager;
import de.emilo.spotifycontrol.config.Lang;
import de.emilo.spotifycontrol.spotify.model.Device;
import de.emilo.spotifycontrol.spotify.model.MediaItem;
import de.emilo.spotifycontrol.spotify.model.PlaybackState;
import de.emilo.spotifycontrol.spotify.model.SearchResults;
import de.emilo.spotifycontrol.spotify.model.Track;
import de.emilo.spotifycontrol.spotify.model.TrackPage;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class SpotifySession {

    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SpotifyControl-Api");
        thread.setDaemon(true);
        return thread;
    });

    private final TokenStore tokens = TokenStore.load();
    private final SpotifyAuth auth = new SpotifyAuth(tokens);
    private final SpotifyApi api = new SpotifyApi(tokens, auth);

    private final List<MediaItem> playlists = new CopyOnWriteArrayList<>();
    private volatile List<Device> devices = List.of();
    private final Set<String> likedTrackIds = ConcurrentHashMap.newKeySet();

    private volatile PlaybackState playback = PlaybackState.IDLE;
    private volatile SearchResults search = SearchResults.EMPTY;
    private volatile String status = "";
    private volatile boolean connecting;
    private volatile long connectingSinceMillis;
    private volatile boolean connected;
    /** Hard end for the starting notice, in case playback never comes up. */
    private static final long LAUNCH_NOTICE_MILLIS = 90_000L;

    /** Longest gap between polls while nothing of the mod is on screen. */
    private static final long HIDDEN_POLL_MILLIS = 30_000L;

    /** Longest gap while something is on screen but nothing is playing. */
    private static final long IDLE_POLL_MILLIS = 15_000L;

    private volatile long lastPollNanos;
    private volatile long visibleAtNanos;

    /** Set while the desktop app is being started, cleared as soon as a track plays. */
    private volatile long launchNoticeUntilMillis;

    /** Extra polls after a command, because Spotify needs a moment to report the new state. */
    private volatile long forcePollAtNanos;

    /** Written by the worker, counted down by the render thread, so it has to be atomic. */
    private final AtomicInteger forcePollsLeft = new AtomicInteger();
    private volatile long lastProgressSyncNanos;
    private volatile Consumer<String> errorListener = error -> {
    };
    private volatile Consumer<String> toastListener = message -> {
    };

    @FunctionalInterface
    public interface DeviceAction {
        boolean run(String deviceId);
    }

    public void setErrorListener(Consumer<String> listener) {
        errorListener = listener;
    }

    public void setToastListener(Consumer<String> listener) {
        toastListener = listener;
    }

    public TokenStore tokens() {
        return tokens;
    }

    public SpotifyApi api() {
        return api;
    }

    public boolean connected() {
        return connected;
    }

    /** True while a different app is configured than the saved tokens were issued for. */
    public boolean clientIdChanged() {
        return !tokens.clientId.isEmpty()
                && !ConfigManager.client().spotifyClientId.trim().equals(tokens.clientId);
    }

    public boolean connecting() {
        return connecting && rateLimitedForMillis() == 0L
                && System.currentTimeMillis() - connectingSinceMillis < 120_000L;
    }

    /** Milliseconds until Spotify will talk to this app again, zero when it is not throttling. */
    public long rateLimitedForMillis() {
        return api.rateLimitedForMillis();
    }

    /** A readable "2 h 14 min" for the remaining block. */
    public static String formatDuration(long millis) {
        long seconds = Math.max(0L, millis / 1000L);
        long hours = seconds / 3600L;
        long minutes = seconds % 3600L / 60L;
        if (hours > 0L) {
            return hours + " h " + minutes + " min";
        }
        if (minutes > 0L) {
            return minutes + " min";
        }
        return seconds + " s";
    }

    public String status() {
        return status;
    }

    public PlaybackState playback() {
        return playback;
    }

    public List<MediaItem> playlists() {
        return playlists;
    }

    public List<Device> devices() {
        return devices;
    }

    public SearchResults searchResults() {
        return search;
    }

    public void resumeSavedSession() {
        if (!tokens.hasTokens()) {
            return;
        }
        connecting = true;
        connectingSinceMillis = System.currentTimeMillis();
        worker.execute(() -> {
            boolean ok = auth.refreshIfNeeded(message -> status = message) && api.loadProfile();
            connecting = false;
            connected = ok;
            if (ok) {
                refreshLibrary();
                pollNow();
            }
        });
    }

    public void login() {
        // Guard on the raw flag: connecting() also reports false while rate limited, which would
        // let repeated clicks start a second authorization on top of the first.
        if (connecting) {
            return;
        }
        String configured = ConfigManager.client().spotifyClientId.trim();
        if (!tokens.clientId.isEmpty() && !configured.equals(tokens.clientId)) {
            // The saved tokens authenticate as the previous app and would carry its rate limit with
            // them. A different app needs its own authorization anyway, so start from scratch.
            tokens.clear();
            api.clearRateLimit();
            connected = false;
        }
        connecting = true;
        connectingSinceMillis = System.currentTimeMillis();
        String waiting = Lang.plain("auth.waiting");
        worker.execute(() -> auth.beginLogin(message -> {
            status = message;
            if (!message.equals(waiting)) {
                connecting = false;
            }
        }, () -> {
            connecting = false;
            connected = api.loadProfile();
            if (connected) {
                status = "";
                refreshLibrary();
                pollNow();
            }
        }));
    }

    public void logout() {
        worker.execute(() -> {
            auth.stopCallbackServer();
            tokens.clear();
            connected = false;
            forcePollsLeft.set(0);
            launchNoticeUntilMillis = 0L;
            playlists.clear();
            devices = List.of();
            devicesFetchedAtMillis = 0L;
            likedResolved.clear();
            libraryFailedAtMillis = 0L;
            playback = PlaybackState.IDLE;
            search = SearchResults.EMPTY;
        });
    }

    public void refreshLibrary() {
        worker.execute(() -> {
            List<MediaItem> loaded = api.playlists();
            playlists.clear();
            playlists.addAll(loaded);
            publishDevices(api.devices());
        });
    }

    /** Called by the app window and by the overlay while they are drawing. */
    public void markVisible() {
        visibleAtNanos = System.nanoTime();
    }

    /**
      * How long to wait before asking Spotify again. The rate limit is a rolling 30 second window
      * per app, so a fixed one second poll spends the whole budget on a screen nobody is looking at.
      * Playback position is counted locally between polls, so a longer gap costs no accuracy.
      */
    private long pollIntervalMillis() {
        long base = Math.max(1000, ConfigManager.client().playbackPollMillis);
        boolean visible = (System.nanoTime() - visibleAtNanos) / 1_000_000L < 1000L;
        if (!visible) {
            return Math.max(base, HIDDEN_POLL_MILLIS);
        }
        if (playback.playing()) {
            return base;
        }
        return Math.min(IDLE_POLL_MILLIS, base * 4L);
    }

    public void tick() {
        if (!connected) {
            return;
        }
        long now = System.nanoTime();
        long interval = pollIntervalMillis() * 1_000_000L;
        boolean forced = forcePollsLeft.get() > 0 && now - forcePollAtNanos >= 0L;
        if (forced || now - lastPollNanos >= interval) {
            lastPollNanos = now;
            boolean started = pollNow();
            if (forced && started) {
                // Only a request that went out counts against the follow ups.
                forcePollsLeft.decrementAndGet();
                forcePollAtNanos = now + 1_500_000_000L;
            }
        } else if (playback.playing()) {
            long elapsed = (now - lastProgressSyncNanos) / 1_000_000L;
            if (elapsed > 0) {
                lastProgressSyncNanos = now;
                long advanced = Math.min(playback.track().durationMs(), playback.progressMs() + elapsed);
                playback = playback.withProgress(advanced);
            }
        }
    }

    private final AtomicBoolean pollInFlight = new AtomicBoolean();

    /** Pauses only when something is actually playing, so no pointless command goes out. */
    public void pauseIfPlaying() {
        if (!connected() || !playback.playing()) {
            return;
        }
        runThenPoll(() -> api.pause());
    }

    /** Returns whether a request was actually started, so a dropped call is not counted. */
    public boolean pollNow() {
        if (!pollInFlight.compareAndSet(false, true)) {
            return false;
        }
        try {
            worker.execute(() -> {
                try {
                    adoptPlayback();
                    syncLikedForCurrentTrack();
                } finally {
                    lastPollNanos = System.nanoTime();
                    pollInFlight.set(false);
                }
            });
        } catch (RuntimeException e) {
            pollInFlight.set(false);
            return false;
        }
        return true;
    }

    /**
      * Takes over a freshly read playback state. A request that failed returns the same empty state
      * as "nothing is playing", so the error is what tells them apart - without this a single
      * failed poll clears the interface and drops polling to the slow interval.
      */
    private void adoptPlayback() {
        PlaybackState fresh = api.playback();
        if (fresh.active() || api.lastError().isEmpty()) {
            playback = fresh;
            lastProgressSyncNanos = System.nanoTime();
            if (fresh.playing()) {
                launchNoticeUntilMillis = 0L;
            }
        }
    }

    /**
      * Whether the notice about the starting desktop app should still be shown. It goes away once
      * something plays, and has a hard end so it cannot sit there forever if nothing ever does.
      */
    public boolean startingSpotify() {
        return launchNoticeUntilMillis != 0L
                && System.currentTimeMillis() < launchNoticeUntilMillis
                && !playback.playing();
    }

    private volatile String likedCheckedTrackId = "";
    private volatile long likedCheckFailedAtMillis;

    private void syncLikedForCurrentTrack() {
        String id = playback.track().id();
        if (id.isEmpty() || id.equals(likedCheckedTrackId)) {
            return;
        }
        if (System.currentTimeMillis() - likedCheckFailedAtMillis < 10_000L
                || System.currentTimeMillis() - libraryFailedAtMillis < 60_000L) {
            return;
        }
        if (likedResolved.contains(id)) {
            likedCheckedTrackId = id;
            return;
        }
        List<Boolean> status = api.likedStatus(List.of(id));
        if (status.isEmpty()) {
            likedCheckFailedAtMillis = System.currentTimeMillis();
            return;
        }
        if (status.get(0)) {
            likedTrackIds.add(id);
        } else {
            likedTrackIds.remove(id);
        }
        likedResolved.add(id);
        likedCheckedTrackId = id;
        likedCheckFailedAtMillis = 0L;
    }

    public void loadQueue(Consumer<List<Track>> callback) {
        run(() -> callback.accept(new ArrayList<>(api.playbackQueue())));
    }

    public void run(Runnable action) {
        worker.execute(() -> {
            try {
                action.run();
            } catch (Exception e) {
                SpotifyControl.LOGGER.error("Spotify action failed", e);
            }
        });
    }

    public void runThenPoll(Runnable action) {
        run(() -> {
            action.run();
            String error = api.lastError();
            if (!error.isEmpty()) {
                errorListener.accept(error);
            }
            adoptPlayback();
            if (error.isEmpty()) {
                scheduleFollowUpPolls();
            }
        });
    }

    public boolean isLiked(String trackId) {
        return !trackId.isEmpty() && likedTrackIds.contains(trackId);
    }

    public void toggleLiked(Track track) {
        if (track.id().isEmpty()) {
            return;
        }
        boolean liked = !likedTrackIds.contains(track.id());
        if (liked) {
            likedTrackIds.add(track.id());
        } else {
            likedTrackIds.remove(track.id());
        }
        likedResolved.add(track.id());
        run(() -> {
            likedCheckedTrackId = "";
            if (!api.like(track.id(), liked)) {
                if (liked) {
                    likedTrackIds.remove(track.id());
                } else {
                    likedTrackIds.add(track.id());
                }
            }
        });
    }

    private final Set<String> likedResolved = ConcurrentHashMap.newKeySet();
    private volatile long libraryFailedAtMillis;

    private static final int LIKED_LOOKUP_LIMIT = 80;

    /**
      * Resolves the heart state for tracks whose state is not known yet. Everything already seen in
      * the saved-tracks list counts as known, the number of lookups per view is capped, and a failed
      * lookup pauses further attempts. All API calls share a single thread, so an unbounded sweep
      * here would delay playback commands behind hundreds of requests.
      */
    public void refreshLikedStatus(List<Track> tracks) {
        if (tracks.isEmpty()) {
            return;
        }
        if (System.currentTimeMillis() - libraryFailedAtMillis < 60_000L) {
            return;
        }
        List<String> ids = new ArrayList<>();
        for (Track track : tracks) {
            String id = track.id();
            if (!id.isEmpty() && !likedResolved.contains(id) && !ids.contains(id)) {
                ids.add(id);
                if (ids.size() >= LIKED_LOOKUP_LIMIT) {
                    break;
                }
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        run(() -> {
            for (int start = 0; start < ids.size(); start += SpotifyApi.LIBRARY_BATCH) {
                List<String> chunk = ids.subList(start,
                        Math.min(ids.size(), start + SpotifyApi.LIBRARY_BATCH));
                List<Boolean> status = api.likedStatus(chunk);
                if (status.isEmpty()) {
                    libraryFailedAtMillis = System.currentTimeMillis();
                    return;
                }
                for (int index = 0; index < status.size() && index < chunk.size(); index++) {
                    String id = chunk.get(index);
                    if (status.get(index)) {
                        likedTrackIds.add(id);
                    } else {
                        likedTrackIds.remove(id);
                    }
                    likedResolved.add(id);
                }
            }
        });
    }

    /**
      * Loads one page of an item's tracks. The caller keeps the offset, which must advance by
      * {@link TrackPage#rawCount()} rather than by the number of usable tracks.
      */
    public void loadTrackPage(MediaItem item, int offset, Consumer<TrackPage> callback) {
        run(() -> {
            TrackPage page = switch (item.kind()) {
                case PLAYLIST -> api.playlistTrackPage(item.id(), offset);
                case ALBUM -> api.albumTrackPage(item.id(), offset, item.name(), item.imageUrl());
                case ARTIST -> offset > 0
                        ? TrackPage.single(List.of())
                        : api.singlePage(api.artistTopTracks(item.id()));
            };
            reportIfPageFailed(page);
            rememberLiked(page);
            callback.accept(page);
        });
    }

    public void loadLikedPage(int offset, Consumer<TrackPage> callback) {
        run(() -> {
            TrackPage page = api.likedTrackPage(offset);
            reportIfPageFailed(page);
            for (Track track : page.tracks()) {
                if (!track.id().isEmpty()) {
                    likedTrackIds.add(track.id());
                    likedResolved.add(track.id());
                }
            }
            callback.accept(page);
        });
    }

    private void reportIfPageFailed(TrackPage page) {
        String error = api.lastError();
        if (page.failed() && !error.isEmpty()) {
            errorListener.accept(error);
        }
    }

    /** A page whose tracks are already known to be saved needs no separate heart lookup. */
    private void rememberLiked(TrackPage page) {
        if (page.failed()) {
            return;
        }
        refreshLikedStatus(page.tracks());
    }

    public void loadRecent(Consumer<List<Track>> callback) {
        run(() -> callback.accept(new ArrayList<>(api.recentlyPlayed())));
    }

    public void loadTop(Consumer<List<Track>> callback) {
        run(() -> callback.accept(new ArrayList<>(api.topTracks())));
    }

    public void search(String query, Consumer<SearchResults> callback) {
        run(() -> {
            SearchResults results = api.search(query);
            if (results.isEmpty() && !api.lastError().isEmpty()) {
                errorListener.accept(api.lastError());
            }
            search = results;
            callback.accept(results);
        });
    }

    public void togglePlayback() {
        PlaybackState current = playback;
        if (current.playing()) {
            runThenPoll(api::pause);
            return;
        }
        withDevice(api::resume);
    }

    private final AtomicBoolean deviceBusy = new AtomicBoolean();
    private final AtomicReference<DeviceAction> queuedAction = new AtomicReference<>();

    public boolean deviceBusy() {
        return deviceBusy.get();
    }

    public void withDevice(DeviceAction action) {
        if (!deviceBusy.compareAndSet(false, true)) {
            queuedAction.set(action);
            return;
        }
        run(() -> {
            try {
                DeviceAction pending = action;
                while (pending != null) {
                    performDevice(pending);
                    pending = queuedAction.getAndSet(null);
                }
            } finally {
                deviceBusy.set(false);
            }
        });
    }

    private void performDevice(DeviceAction action) {
        String deviceId = ensureDevice();
        if (deviceId == null) {
            errorListener.accept("no_device");
            return;
        }
        boolean ok = action.run(deviceId);
        if (!ok && "no_device".equals(api.lastError())) {
            playback = PlaybackState.IDLE;
            deviceId = ensureDevice(true);
            ok = deviceId != null && action.run(deviceId);
        }
        if (!ok && !api.lastError().isEmpty()) {
            errorListener.accept(api.lastError());
        }
        adoptPlayback();
        if (ok) {
            scheduleFollowUpPolls();
        }
    }

    /**
      * Right after a command Spotify often still reports the previous state, and the normal
      * interval is the slow one for as long as nothing looks like it is playing.
      */
    private void scheduleFollowUpPolls() {
        forcePollAtNanos = System.nanoTime() + 1_200_000_000L;
        forcePollsLeft.set(3);
    }

    private String ensureDevice() {
        return ensureDevice(false);
    }

    /**
      * Resolves the device to play on. The cached device list is trusted while it already names an
      * active device, so an ordinary play or pause costs no extra request. Only when no usable
      * device is cached - or when a command just failed with {@code no_device} - is the list
      * refreshed and, if needed, playback transferred.
      */
    private String ensureDevice(boolean forceRefresh) {
        if (!forceRefresh && System.currentTimeMillis() - devicesFetchedAtMillis < DEVICE_CACHE_MILLIS) {
            Device cached = pickDevice(devices);
            if (cached != null && cached.active() && !cached.id().isEmpty()) {
                return cached.id();
            }
        }
        List<Device> available = api.devices();
        publishDevices(available);
        Device chosen = pickDevice(available);
        if (chosen == null && ConfigManager.client().autoLaunchSpotify) {
            if (!SpotifyLauncher.launch(ConfigManager.client().minimizeSpotifyWindow)) {
                launchNoticeUntilMillis = 0L;
                toastListener.accept(Lang.plain("device.launch_failed"));
                return null;
            }
            // Not a timed toast: this one stays up until a track is actually playing. A repeat
            // attempt keeps the original deadline instead of extending it.
            if (launchNoticeUntilMillis == 0L) {
                launchNoticeUntilMillis = System.currentTimeMillis() + LAUNCH_NOTICE_MILLIS;
            }
            long deadline = System.currentTimeMillis() + 45_000L;
            while (chosen == null && System.currentTimeMillis() < deadline
                    && api.rateLimitedForMillis() == 0L) {
                sleep(1_500L);
                available = api.devices();
                publishDevices(available);
                chosen = pickDevice(available);
            }
        }
        if (chosen == null) {
            launchNoticeUntilMillis = 0L;
            SpotifyControl.LOGGER.warn("No Spotify playback device available, saw {} device(s)", available.size());
            return null;
        }
        if (!chosen.active()) {
            api.transfer(chosen.id(), false);
            sleep(900L);
            toastListener.accept(Lang.plain("device.ready", "device", chosen.name()));
            publishDevices(api.devices());
        }
        return chosen.id();
    }

    private static boolean contains(List<Device> devices, String deviceId) {
        for (Device device : devices) {
            if (device.id().equals(deviceId)) {
                return true;
            }
        }
        return false;
    }

    /** How long the device list may answer without asking Spotify again. */
    private static final long DEVICE_CACHE_MILLIS = 30_000L;

    private volatile long devicesFetchedAtMillis;

    private void publishDevices(List<Device> available) {
        devices = List.copyOf(available);
        devicesFetchedAtMillis = System.currentTimeMillis();
    }

    private static Device preferredDevice(List<Device> candidates) {
        String preferred = ConfigManager.client().preferredDeviceName.trim();
        if (preferred.isEmpty()) {
            return null;
        }
        for (Device device : candidates) {
            if (!device.restricted() && !device.id().isEmpty() && device.name().equalsIgnoreCase(preferred)) {
                return device;
            }
        }
        return null;
    }

    private static Device pickDevice(List<Device> candidates) {
        String preferred = ConfigManager.client().preferredDeviceName.trim();
        Device activeComputer = null;
        Device computer = null;
        Device active = null;
        Device fallback = null;
        for (Device device : candidates) {
            if (device.restricted() || device.id().isEmpty()) {
                continue;
            }
            if (!preferred.isEmpty() && device.name().equalsIgnoreCase(preferred)) {
                return device;
            }
            boolean isComputer = "Computer".equalsIgnoreCase(device.type());
            if (isComputer && device.active() && activeComputer == null) {
                activeComputer = device;
            }
            if (isComputer && computer == null) {
                computer = device;
            }
            if (device.active() && active == null) {
                active = device;
            }
            if (fallback == null) {
                fallback = device;
            }
        }
        if (activeComputer != null) {
            return activeComputer;
        }
        if (computer != null) {
            return computer;
        }
        return active != null ? active : fallback;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void nextTrack() {
        runThenPoll(api::next);
    }

    public void previousTrack() {
        runThenPoll(api::previous);
    }

    public void changeVolume(int delta) {
        PlaybackState current = playback;
        if (!current.active() || current.volumePercent() < 0) {
            return;
        }
        runThenPoll(() -> api.volume(current.volumePercent() + delta));
    }

    public void shutdown() {
        auth.stopCallbackServer();
        worker.shutdownNow();
    }
}
