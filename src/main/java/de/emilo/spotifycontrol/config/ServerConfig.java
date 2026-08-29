package de.emilo.spotifycontrol.config;

public class ServerConfig {

    public boolean enabled = true;
    public String language = "en";
    public boolean allowListeningParties = true;
    public boolean allowAudioBroadcast = true;
    public int maxPartySize = 20;
    public int broadcastRangeBlocks = 0;
    public boolean requireSameDimension = true;
    public int maxAudioKilobitsPerSecond = 96;
    public boolean announcePartyInChat = true;
    public boolean broadcastNowPlayingToParty = true;
    public int partyIdleTimeoutSeconds = 120;
}
