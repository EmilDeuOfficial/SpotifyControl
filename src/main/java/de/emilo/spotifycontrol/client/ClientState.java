package de.emilo.spotifycontrol.client;

import de.emilo.spotifycontrol.SpotifyControl;
import de.emilo.spotifycontrol.config.ConfigManager;
import de.emilo.spotifycontrol.config.Lang;
import de.emilo.spotifycontrol.net.payload.NowPlayingPayload;
import de.emilo.spotifycontrol.net.payload.PartyActionPayload;
import de.emilo.spotifycontrol.net.payload.PartyStatePayload;
import de.emilo.spotifycontrol.net.payload.PartyMarkersPayload;
import de.emilo.spotifycontrol.net.payload.ServerSettingsPayload;
import de.emilo.spotifycontrol.spotify.SpotifySession;
import de.emilo.spotifycontrol.spotify.model.PlaybackState;
import de.emilo.spotifycontrol.voice.AudioDevices;
import de.emilo.spotifycontrol.voice.DesktopAudioCapture;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public final class ClientState {

    private static final long SYNC_TOLERANCE_MS = 2500L;

    private static SpotifySession session;
    private static AlbumArtCache artwork;
    private static DesktopAudioCapture capture;

    private static volatile PartyStatePayload party = PartyStatePayload.NONE;
    private static volatile PartyMarkersPayload partyMarkers = PartyMarkersPayload.NONE;
    private static volatile ServerSettingsPayload serverSettings = ServerSettingsPayload.SINGLEPLAYER_DEFAULT;
    private static volatile NowPlayingPayload remoteTrack = NowPlayingPayload.EMPTY;
    private static volatile String toast = "";
    private static volatile long toastUntilMillis;
    private static long lastHostReportMillis;
    private static String lastReportedUri = "";

    private ClientState() {
    }

    public static void bootstrap() {
        session = new SpotifySession();
        session.setErrorListener(ClientState::reportApiError);
        session.setToastListener(ClientState::toast);
        artwork = new AlbumArtCache();
        session.resumeSavedSession();
    }

    private static void reportApiError(String error) {
        if (error.isEmpty()) {
            return;
        }
        toast(describeApiError(error));
    }

    /** Turns an error code from the Spotify API into the sentence shown to the user. */
    public static String describeApiError(String error) {
        return switch (error) {
            case "premium" -> Lang.plain("gui.premium_required");
            case "no_device" -> Lang.plain("gui.no_device");
            case "rate_limited" -> session.rateLimitedForMillis() > 0L
                    ? Lang.plain("gui.rate_limited_wait", "time",
                            SpotifySession.formatDuration(session.rateLimitedForMillis()))
                    : Lang.plain("gui.rate_limited");
            case "playlist_locked" -> Lang.plain("gui.playlist_locked");
            case "server_busy" -> Lang.plain("gui.server_busy");
            case "network", "auth", "auth_client", "not_connected", "bad_request", "forbidden",
                 "not_found" -> Lang.plain("error." + error);
            // Anything else is an internal code or text that would mean nothing to the user. It is
            // already in the log with the details.
            default -> Lang.plain("error.unknown");
        };
    }

    public static SpotifySession session() {
        return session;
    }

    public static AlbumArtCache artwork() {
        return artwork;
    }

    public static PartyStatePayload party() {
        return party;
    }

    /** Everyone on the server who is in a party, whether or not the local player is in one. */
    public static PartyMarkersPayload partyMarkers() {
        return partyMarkers;
    }

    public static void setPartyMarkers(PartyMarkersPayload markers) {
        partyMarkers = markers;
    }

    public static ServerSettingsPayload serverSettings() {
        return serverSettings;
    }

    public static NowPlayingPayload remoteTrack() {
        return remoteTrack;
    }

    public static String toast() {
        return System.currentTimeMillis() < toastUntilMillis ? toast : "";
    }

    public static void toast(String message) {
        toast = message;
        toastUntilMillis = System.currentTimeMillis() + 4000L;
    }

    public static DesktopAudioCapture capture() {
        if (capture == null && SpotifyControl.voicechatPresent()) {
            capture = new DesktopAudioCapture();
        }
        return capture;
    }

    public static boolean capturing() {
        return capture != null && capture.isRunning();
    }

    public static void setParty(PartyStatePayload state) {
        party = state;
        if (!state.inParty() || !state.host() || !"BROADCAST".equals(state.mode())) {
            stopCapture();
        }
        if (!state.inParty()) {
            remoteTrack = NowPlayingPayload.EMPTY;
        }
    }

    public static void setServerSettings(ServerSettingsPayload settings) {
        serverSettings = settings;
    }

    public static void setRemoteTrack(NowPlayingPayload payload) {
        remoteTrack = payload;
        if (party.inParty() && !party.host() && "SYNC".equals(party.mode())) {
            followRemoteTrack(payload);
        }
    }

    private static void followRemoteTrack(NowPlayingPayload payload) {
        if (session == null || !session.connected() || payload.trackUri().isEmpty()) {
            return;
        }
        if (session.tokens().knownFreeAccount()) {
            return;
        }
        PlaybackState local = session.playback();
        boolean differentTrack = !local.track().uri().equals(payload.trackUri());
        boolean drifted = Math.abs(local.progressMs() - payload.positionMs()) > SYNC_TOLERANCE_MS;
        if (!differentTrack && !drifted && local.playing() == payload.playing()) {
            return;
        }
        session.runThenPoll(() -> {
            if (!payload.playing()) {
                session.api().pause();
                return;
            }
            if (differentTrack) {
                session.api().play("", java.util.List.of(payload.trackUri()), "", local.deviceId());
            }
            session.api().seek(payload.positionMs());
        });
    }

    public static void reportNowPlayingIfHost() {
        if (session == null || !party.inParty() || !party.host()) {
            return;
        }
        PlaybackState state = session.playback();
        long now = System.currentTimeMillis();
        boolean trackChanged = !state.track().uri().equals(lastReportedUri);
        if (!trackChanged && now - lastHostReportMillis < 5000L) {
            return;
        }
        lastHostReportMillis = now;
        lastReportedUri = state.track().uri();
        ClientPlayNetworking.send(new NowPlayingPayload(
                state.track().uri(),
                state.track().title(),
                state.track().artist(),
                state.track().artworkUrl(),
                state.track().durationMs(),
                state.progressMs(),
                state.playing()));
    }

    public static void startParty(String mode) {
        ClientPlayNetworking.send(new PartyActionPayload("start", "", mode));
    }

    public static void stopParty() {
        ClientPlayNetworking.send(PartyActionPayload.of("stop"));
        stopCapture();
    }

    public static void leaveParty() {
        ClientPlayNetworking.send(PartyActionPayload.of("leave"));
    }

    public static void inviteToParty(String player) {
        ClientPlayNetworking.send(new PartyActionPayload("invite", player, ""));
    }

    public static void joinParty(String host) {
        ClientPlayNetworking.send(new PartyActionPayload("join", host, ""));
    }

    public static void setPartyMode(String mode) {
        ClientPlayNetworking.send(new PartyActionPayload("mode", "", mode));
    }

    /**
     * Opens the broadcast line and says what it is actually carrying.
     *
     * <p>The kind of the device decides the message. Announcing desktop audio while a microphone is
     * on the line is the mislabel this feature was reported for: the capture is a second, private
     * line into the sound system, so being muted in the voice chat hides nothing, and a user told
     * "broadcasting desktop audio" has no reason to suspect the party can hear the room.</p>
     */
    public static void startCapture() {
        DesktopAudioCapture audio = capture();
        if (audio == null) {
            toast(Lang.plain("audio.voicechat_missing"));
            return;
        }
        String device = captureDevice();
        if (device.isEmpty()) {
            toast(Lang.plain("audio.no_loopback"));
            return;
        }
        // The capture layer is given the stored name, every message is given the readable one. The
        // speaker mix is stored under a marker that names no real device, so a toast repeating it
        // verbatim would tell the user less than saying nothing.
        String shown = captureSourceName(device);
        boolean loopback = AudioDevices.isLoopback(device);
        if (!loopback) {
            // Warn, never refuse. The kind is guessed from the device name, so refusing here would
            // shut out a genuine loopback whose name the heuristic does not know. The user gets the
            // warning through audio.capturing_input below, which is the toast that survives; this
            // line records it before the device is touched, for anyone reading a bug report.
            SpotifyControl.LOGGER.warn("Capture device '{}' does not look like a loopback device, "
                    + "the party may hear a microphone instead of Spotify", device);
        }
        boolean started = audio.start(device, key -> toast(Lang.plain(key, "device", shown, "error",
                audio.lastError())));
        if (started) {
            toast(Lang.plain(loopback ? "audio.capturing_output" : "audio.capturing_input",
                    "device", shown));
        }
    }

    /**
     * The name to put in front of a user for a stored capture device.
     *
     * <p>{@code AudioDevices.SYSTEM_OUTPUT} is a marker the capture layer understands, not a mixer
     * anybody has ever seen in their sound settings, so it is translated here. Every other name
     * comes from the sound system and is already the string the operating system shows, so it is
     * passed through untouched rather than guessed at.</p>
     */
    public static String captureSourceName(String device) {
        return AudioDevices.displayName(device);
    }

    /**
     * The configured capture device, or the best loopback candidate when the user never picked one.
     *
     * <p>On Windows the best candidate is now the speaker mix, so the common case is no longer an
     * empty result but a working one that cost the user nothing to set up. An empty name is left
     * for the machines where that is still true: no loopback at all, which no choice of device can
     * fix - it needs Stereomix enabled, a virtual cable installed or a monitor source - so the
     * caller says so instead of opening the first microphone it finds.</p>
     *
     * <p>What gets stored is whatever {@code bestLoopback} returned, unchanged. For the speaker mix
     * that is the synthetic marker, not a readable name: the config is read back by the capture
     * layer, which has to recognise the source again on the next start, and a display string would
     * match nothing. Readability is the job of {@link #captureSourceName(String)} at the point of
     * printing.</p>
     */
    private static String captureDevice() {
        String configured = ConfigManager.client().captureDeviceName;
        if (!configured.isBlank()) {
            return configured;
        }
        String fallback = AudioDevices.bestLoopback();
        if (fallback == null) {
            return "";
        }
        // Store it, so the settings row stops reporting that nothing is selected while this very
        // device is on the air.
        ConfigManager.client().captureDeviceName = fallback;
        ConfigManager.saveClient();
        return fallback;
    }

    public static void stopCapture() {
        if (capture != null && capture.isRunning()) {
            capture.stop();
            toast(Lang.plain("audio.stopped"));
        }
    }

    public static void shutdown() {
        stopCapture();
        if (session != null) {
            session.shutdown();
        }
        if (artwork != null) {
            artwork.shutdown();
        }
    }
}
