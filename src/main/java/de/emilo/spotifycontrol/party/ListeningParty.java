package de.emilo.spotifycontrol.party;

import de.emilo.spotifycontrol.net.payload.NowPlayingPayload;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

public class ListeningParty {

    private final UUID hostId;
    private final String hostName;
    private final UUID channelId = UUID.randomUUID();
    private final Set<UUID> members = new LinkedHashSet<>();

    private String mode;
    private NowPlayingPayload nowPlaying = NowPlayingPayload.EMPTY;
    private long lastAudioNanos;
    private boolean streamingPushed;

    public ListeningParty(UUID hostId, String hostName, String mode) {
        this.hostId = hostId;
        this.hostName = hostName;
        this.mode = normalizeMode(mode);
    }

    public static String normalizeMode(String mode) {
        return "BROADCAST".equalsIgnoreCase(mode) ? "BROADCAST" : "SYNC";
    }

    public UUID hostId() {
        return hostId;
    }

    public String hostName() {
        return hostName;
    }

    public UUID channelId() {
        return channelId;
    }

    public Set<UUID> members() {
        return members;
    }

    public String mode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = normalizeMode(mode);
    }

    public boolean isBroadcast() {
        return "BROADCAST".equals(mode);
    }

    public NowPlayingPayload nowPlaying() {
        return nowPlaying;
    }

    public void setNowPlaying(NowPlayingPayload nowPlaying) {
        this.nowPlaying = nowPlaying;
    }

    public void markAudio() {
        lastAudioNanos = System.nanoTime();
    }

    public boolean audioIdleFor(long seconds) {
        return lastAudioNanos != 0L && System.nanoTime() - lastAudioNanos > seconds * 1_000_000_000L;
    }

    /**
     * Whether members are hearing the host right now. The lastAudioNanos check is not redundant:
     * audioIdleFor reports false for a party that has never sent a single frame, so a host who
     * opened a BROADCAST party and never started capturing would otherwise read as live.
     */
    public boolean isStreaming(long idleSeconds) {
        return isBroadcast() && lastAudioNanos != 0L && !audioIdleFor(idleSeconds);
    }

    /**
     * The streaming flag as it last left the server for this party. Kept per party so a stream
     * that stops pushes exactly once as well, instead of only ever pushing while it is running.
     */
    public boolean streamingPushed() {
        return streamingPushed;
    }

    public void setStreamingPushed(boolean streamingPushed) {
        this.streamingPushed = streamingPushed;
    }

    public int size() {
        return members.size() + 1;
    }
}
