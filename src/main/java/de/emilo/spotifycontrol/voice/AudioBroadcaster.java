package de.emilo.spotifycontrol.voice;

import de.emilo.spotifycontrol.SpotifyControl;
import de.emilo.spotifycontrol.config.ConfigManager;
import de.emilo.spotifycontrol.party.ListeningParty;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.LocationalAudioChannel;
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import de.emilo.spotifycontrol.platform.Platform;

public class AudioBroadcaster {

    private static volatile VoicechatServerApi serverApi;

    private final Map<UUID, PartyChannels> channelsByParty = new ConcurrentHashMap<>();

    public static void setServerApi(VoicechatServerApi api) {
        serverApi = api;
    }

    public void openParty(ServerPlayer host, ListeningParty party, List<ServerPlayer> listeners) {
        if (serverApi == null) {
            return;
        }
        PartyChannels channels = channelsByParty.computeIfAbsent(party.hostId(), key -> new PartyChannels());
        if (ConfigManager.server().broadcastRangeBlocks > 0) {
            channels.ensureLocational(party, host);
        } else {
            for (ServerPlayer listener : listeners) {
                channels.ensureStatic(party, listener);
            }
        }
    }

    public void addListener(ListeningParty party, ServerPlayer listener) {
        if (serverApi == null || ConfigManager.server().broadcastRangeBlocks > 0) {
            return;
        }
        channelsByParty.computeIfAbsent(party.hostId(), key -> new PartyChannels()).ensureStatic(party, listener);
    }

    public void removeListener(ListeningParty party, UUID listenerId) {
        PartyChannels channels = channelsByParty.get(party.hostId());
        if (channels != null) {
            channels.statics.remove(listenerId);
        }
    }

    public void closeParty(ListeningParty party) {
        PartyChannels channels = channelsByParty.remove(party.hostId());
        if (channels != null) {
            channels.statics.clear();
            channels.locational = null;
        }
    }

    public void updatePosition(ListeningParty party, ServerPlayer host) {
        PartyChannels channels = channelsByParty.get(party.hostId());
        if (channels == null || channels.locational == null || serverApi == null) {
            return;
        }
        channels.locational.updateLocation(serverApi.createPosition(host.getX(), host.getY() + 1.0D, host.getZ()));
    }

    public void send(ListeningParty party, ServerPlayer host, byte[] opus) {
        VoicechatServerApi api = serverApi;
        if (api == null) {
            return;
        }
        PartyChannels channels = channelsByParty.computeIfAbsent(party.hostId(), key -> new PartyChannels());
        if (ConfigManager.server().broadcastRangeBlocks > 0) {
            LocationalAudioChannel channel = channels.ensureLocational(party, host);
            if (channel != null) {
                channel.send(opus);
            }
            return;
        }
        for (UUID memberId : party.members()) {
            ServerPlayer listener = Platform.serverOf(host).getPlayerList().getPlayer(memberId);
            if (listener == null) {
                continue;
            }
            if (ConfigManager.server().requireSameDimension && Platform.levelOf(listener) != Platform.levelOf(host)) {
                continue;
            }
            StaticAudioChannel channel = channels.ensureStatic(party, listener);
            if (channel != null) {
                channel.send(opus);
            }
        }
    }

    public void shutdown() {
        channelsByParty.clear();
    }

    private static final class PartyChannels {

        private final Map<UUID, StaticAudioChannel> statics = new ConcurrentHashMap<>();
        private volatile LocationalAudioChannel locational;

        private StaticAudioChannel ensureStatic(ListeningParty party, ServerPlayer listener) {
            return statics.computeIfAbsent(listener.getUUID(), key -> {
                try {
                    VoicechatConnection connection = serverApi.getConnectionOf(key);
                    if (connection == null) {
                        return null;
                    }
                    StaticAudioChannel channel = serverApi.createStaticAudioChannel(
                            party.channelId(),
                            serverApi.fromServerLevel(Platform.levelOf(listener)),
                            connection);
                    if (channel != null) {
                        channel.setCategory(SpotifyVoicechatPlugin.PLUGIN_ID);
                    }
                    return channel;
                } catch (Exception e) {
                    SpotifyControl.LOGGER.error("Could not open voice chat channel for listener", e);
                    return null;
                }
            });
        }

        private LocationalAudioChannel ensureLocational(ListeningParty party, ServerPlayer host) {
            LocationalAudioChannel channel = locational;
            if (channel != null) {
                return channel;
            }
            try {
                channel = serverApi.createLocationalAudioChannel(
                        party.channelId(),
                        serverApi.fromServerLevel(Platform.levelOf(host)),
                        serverApi.createPosition(host.getX(), host.getY() + 1.0D, host.getZ()));
                if (channel != null) {
                    channel.setDistance(ConfigManager.server().broadcastRangeBlocks);
                    channel.setCategory(SpotifyVoicechatPlugin.PLUGIN_ID);
                }
                locational = channel;
                return channel;
            } catch (Exception e) {
                SpotifyControl.LOGGER.error("Could not open locational voice chat channel", e);
                return null;
            }
        }
    }
}
