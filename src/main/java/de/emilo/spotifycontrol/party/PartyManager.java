package de.emilo.spotifycontrol.party;

import de.emilo.spotifycontrol.SpotifyControl;
import de.emilo.spotifycontrol.config.ConfigManager;
import de.emilo.spotifycontrol.config.Lang;
import de.emilo.spotifycontrol.config.ServerConfig;
import de.emilo.spotifycontrol.net.payload.AudioFramePayload;
import de.emilo.spotifycontrol.net.payload.NowPlayingPayload;
import de.emilo.spotifycontrol.net.payload.PartyStatePayload;
import de.emilo.spotifycontrol.voice.AudioBroadcaster;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import de.emilo.spotifycontrol.net.payload.PartyMarkersPayload;
import de.emilo.spotifycontrol.platform.Platform;

public class PartyManager {

    /**
     * How long the audio may be silent before members stop being told the host is streaming.
     * Long enough to ride out a track change or a hiccup in the capture, short enough that a host
     * who really stopped does not keep the marker for a noticeable while.
     */
    private static final long STREAM_IDLE_SECONDS = 3L;

    private final Map<UUID, ListeningParty> partiesByHost = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> hostByMember = new ConcurrentHashMap<>();
    private final AudioBroadcaster broadcaster;

    private int tickCounter;

    /**
     * The marker list as it last left the server. Kept so the broadcast can be skipped while nothing
     * has changed, which is nearly always: parties are started and left a few times an evening.
     */
    private volatile PartyMarkersPayload pushedMarkers = PartyMarkersPayload.NONE;

    public PartyManager() {
        broadcaster = SpotifyControl.voicechatPresent() ? new AudioBroadcaster() : null;
    }

    public int partyCount() {
        return partiesByHost.size();
    }

    public int listenerCount() {
        return hostByMember.size();
    }

    public ListeningParty partyOf(UUID playerId) {
        ListeningParty hosted = partiesByHost.get(playerId);
        if (hosted != null) {
            return hosted;
        }
        UUID host = hostByMember.get(playerId);
        return host == null ? null : partiesByHost.get(host);
    }

    public void start(ServerPlayer player, String mode) {
        ServerConfig config = ConfigManager.server();
        if (!config.allowListeningParties) {
            Platform.sendMessage(player, Lang.chat("party.broadcast_disabled"));
            return;
        }
        if (partyOf(player.getUUID()) != null) {
            Platform.sendMessage(player, Lang.chat("party.already_in"));
            return;
        }
        String resolved = ListeningParty.normalizeMode(mode);
        if ("BROADCAST".equals(resolved) && !(config.allowAudioBroadcast && SpotifyControl.voicechatPresent())) {
            Platform.sendMessage(player, Lang.chat("party.broadcast_disabled"));
            resolved = "SYNC";
        }
        ListeningParty party = new ListeningParty(player.getUUID(), Platform.playerName(player), resolved);
        partiesByHost.put(player.getUUID(), party);
        Platform.sendMessage(player, Lang.chat("party.created", "mode", party.mode()));
        pushState(Platform.serverOf(player), party);
    }

    public void stop(ServerPlayer player) {
        ListeningParty party = partiesByHost.remove(player.getUUID());
        if (party == null) {
            Platform.sendMessage(player, Lang.chat("party.host_only"));
            return;
        }
        closeParty(Platform.serverOf(player), party);
    }

    public void join(ServerPlayer player, String hostName) {
        if (!ConfigManager.server().allowListeningParties) {
            Platform.sendMessage(player, Lang.chat("party.broadcast_disabled"));
            return;
        }
        if (partyOf(player.getUUID()) != null) {
            Platform.sendMessage(player, Lang.chat("party.already_in"));
            return;
        }
        ServerPlayer host = Platform.serverOf(player).getPlayerList().getPlayerByName(hostName);
        ListeningParty party = host == null ? null : partiesByHost.get(host.getUUID());
        if (party == null) {
            Platform.sendMessage(player, Lang.chat("party.unknown_host", "host", hostName));
            return;
        }
        if (party.size() >= ConfigManager.server().maxPartySize) {
            Platform.sendMessage(player, Lang.chat("party.broadcast_disabled"));
            return;
        }
        party.members().add(player.getUUID());
        hostByMember.put(player.getUUID(), party.hostId());
        Platform.sendMessage(player, Lang.chat("party.joined", "host", party.hostName()));
        announce(Platform.serverOf(player), party, Lang.plain("party.member_joined", "player", Platform.playerName(player)),
                player.getUUID());
        if (broadcaster != null && party.isBroadcast()) {
            broadcaster.addListener(party, player);
        }
        ServerPlayNetworking.send(player, party.nowPlaying());
        pushState(Platform.serverOf(player), party);
    }

    public void leave(ServerPlayer player) {
        ListeningParty hosted = partiesByHost.get(player.getUUID());
        if (hosted != null) {
            stop(player);
            return;
        }
        UUID hostId = hostByMember.remove(player.getUUID());
        ListeningParty party = hostId == null ? null : partiesByHost.get(hostId);
        if (party == null) {
            Platform.sendMessage(player, Lang.chat("party.not_in"));
            return;
        }
        party.members().remove(player.getUUID());
        if (broadcaster != null) {
            broadcaster.removeListener(party, player.getUUID());
        }
        Platform.sendMessage(player, Lang.chat("party.left"));
        ServerPlayNetworking.send(player, PartyStatePayload.NONE);
        announce(Platform.serverOf(player), party, Lang.plain("party.member_left", "player", Platform.playerName(player)),
                player.getUUID());
        pushState(Platform.serverOf(player), party);
    }

    public void invite(ServerPlayer player, String targetName) {
        ListeningParty party = partiesByHost.get(player.getUUID());
        if (party == null) {
            Platform.sendMessage(player, Lang.chat("party.host_only"));
            return;
        }
        ServerPlayer target = Platform.serverOf(player).getPlayerList().getPlayerByName(targetName);
        if (target == null) {
            Platform.sendMessage(player, Lang.chat("party.unknown_host", "host", targetName));
            return;
        }
        Platform.sendMessage(target, Lang.chat("party.invite_received", "host", party.hostName()));
        Platform.sendMessage(player, Lang.chat("party.invited", "player", Platform.playerName(target)));
    }

    public void setMode(ServerPlayer player, String mode) {
        ListeningParty party = partiesByHost.get(player.getUUID());
        if (party == null) {
            Platform.sendMessage(player, Lang.chat("party.host_only"));
            return;
        }
        String resolved = ListeningParty.normalizeMode(mode);
        if ("BROADCAST".equals(resolved)
                && !(ConfigManager.server().allowAudioBroadcast && SpotifyControl.voicechatPresent())) {
            Platform.sendMessage(player, Lang.chat("party.broadcast_disabled"));
            return;
        }
        if (broadcaster != null && party.isBroadcast() && !"BROADCAST".equals(resolved)) {
            broadcaster.closeParty(party);
        }
        party.setMode(resolved);
        if (broadcaster != null && party.isBroadcast()) {
            broadcaster.openParty(player, party, collectMembers(Platform.serverOf(player), party));
        }
        pushState(Platform.serverOf(player), party);
    }

    public void updateNowPlaying(ServerPlayer player, NowPlayingPayload payload) {
        ListeningParty party = partiesByHost.get(player.getUUID());
        if (party == null || !ConfigManager.server().broadcastNowPlayingToParty) {
            return;
        }
        party.setNowPlaying(payload);
        for (ServerPlayer member : collectMembers(Platform.serverOf(player), party)) {
            ServerPlayNetworking.send(member, payload);
        }
    }

    public void relayAudio(ServerPlayer player, AudioFramePayload payload) {
        if (broadcaster == null || !ConfigManager.server().allowAudioBroadcast) {
            return;
        }
        ListeningParty party = partiesByHost.get(player.getUUID());
        if (party == null || !party.isBroadcast()) {
            return;
        }
        if (payload.opus().length == 0 || payload.opus().length > AudioFramePayload.MAX_FRAME_BYTES) {
            return;
        }
        party.markAudio();
        broadcaster.send(party, player, payload.opus());
    }

    public void handleDisconnect(ServerPlayer player) {
        if (player == null) {
            return;
        }
        ListeningParty hosted = partiesByHost.remove(player.getUUID());
        if (hosted != null) {
            closeParty(Platform.serverOf(player), hosted);
            return;
        }
        UUID hostId = hostByMember.remove(player.getUUID());
        if (hostId != null) {
            ListeningParty party = partiesByHost.get(hostId);
            if (party != null) {
                party.members().remove(player.getUUID());
                if (broadcaster != null) {
                    broadcaster.removeListener(party, player.getUUID());
                }
                pushState(Platform.serverOf(player), party);
            }
        }
    }

    public void tick(MinecraftServer server) {
        // Before the early return below, not after it. The frame in which the last party closes is
        // the frame with no parties left, and that is precisely when the marks have to come off.
        pushMarkersIfChanged(server);
        if (partiesByHost.isEmpty()) {
            return;
        }
        tickCounter++;
        if (tickCounter % 4 == 0) {
            // Every four ticks is a fifth of a second, so the marker above the host follows the
            // audio closely enough to look immediate without a packet per tick per member.
            for (ListeningParty party : partiesByHost.values()) {
                if (broadcaster != null && party.isBroadcast()) {
                    ServerPlayer host = server.getPlayerList().getPlayer(party.hostId());
                    if (host != null) {
                        broadcaster.updatePosition(party, host);
                    }
                }
                if (party.isStreaming(STREAM_IDLE_SECONDS) != party.streamingPushed()) {
                    pushState(server, party);
                }
            }
        }
        if (tickCounter % 40 == 0) {
            int timeout = ConfigManager.server().partyIdleTimeoutSeconds;
            for (ListeningParty party : List.copyOf(partiesByHost.values())) {
                if (party.isBroadcast() && timeout > 0 && party.audioIdleFor(timeout)) {
                    partiesByHost.remove(party.hostId());
                    closeParty(server, party);
                }
            }
        }
    }

    /** Everyone in any party right now, for the marks above their heads. */
    public PartyMarkersPayload markers() {
        return pushedMarkers;
    }

    /**
      * Recomputes the marker list and, when it has moved, sends it to everybody on the server.
      *
      * <p>Driven from the tick rather than from each of start, stop, join, leave, close and
      * disconnect. Those are six places that would each have to remember to call it, and the one
      * that forgot would leave a mark hanging over someone who had left - whereas a comparison that
      * runs every tick cannot be forgotten and costs a set of a few uuids.
      */
    private void pushMarkersIfChanged(MinecraftServer server) {
        List<UUID> listeners = new ArrayList<>();
        for (ListeningParty party : partiesByHost.values()) {
            listeners.add(party.hostId());
            listeners.addAll(party.members());
        }
        if (listeners.equals(pushedMarkers.listeners())) {
            return;
        }
        PartyMarkersPayload markers = new PartyMarkersPayload(List.copyOf(listeners));
        pushedMarkers = markers;
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            ServerPlayNetworking.send(online, markers);
        }
    }

    public void shutdown() {
        if (broadcaster != null) {
            broadcaster.shutdown();
        }
        partiesByHost.clear();
        hostByMember.clear();
    }

    private void closeParty(MinecraftServer server, ListeningParty party) {
        if (broadcaster != null) {
            broadcaster.closeParty(party);
        }
        for (UUID memberId : party.members()) {
            hostByMember.remove(memberId);
            ServerPlayer member = server.getPlayerList().getPlayer(memberId);
            if (member != null) {
                Platform.sendMessage(member, Lang.chat("party.ended", "host", party.hostName()));
                ServerPlayNetworking.send(member, PartyStatePayload.NONE);
            }
        }
        party.members().clear();
        ServerPlayer host = server.getPlayerList().getPlayer(party.hostId());
        if (host != null) {
            ServerPlayNetworking.send(host, PartyStatePayload.NONE);
        }
    }

    private List<ServerPlayer> collectMembers(MinecraftServer server, ListeningParty party) {
        List<ServerPlayer> players = new ArrayList<>(party.members().size());
        for (UUID memberId : party.members()) {
            ServerPlayer member = server.getPlayerList().getPlayer(memberId);
            if (member != null) {
                players.add(member);
            }
        }
        return players;
    }

    private void announce(MinecraftServer server, ListeningParty party, String message, UUID except) {
        if (!ConfigManager.server().announcePartyInChat) {
            return;
        }
        ServerPlayer host = server.getPlayerList().getPlayer(party.hostId());
        if (host != null && !host.getUUID().equals(except)) {
            Platform.sendMessage(host, Lang.text("prefix").copy().append(message));
        }
        for (ServerPlayer member : collectMembers(server, party)) {
            if (!member.getUUID().equals(except)) {
                Platform.sendMessage(member, Lang.text("prefix").copy().append(message));
            }
        }
    }

    private void pushState(MinecraftServer server, ListeningParty party) {
        // One pass over the members feeds both lists and the send loop below, so the roster the
        // clients receive is the same one they are sent to even if a player leaves mid push.
        List<ServerPlayer> online = collectMembers(server, party);
        List<String> names = new ArrayList<>(online.size() + 1);
        List<UUID> ids = new ArrayList<>(online.size() + 1);
        // The host is kept apart from the member set in the model, but for anyone reading the party
        // it is just another listener, so it leads both lists and the two stay index aligned.
        names.add(party.hostName());
        ids.add(party.hostId());
        for (ServerPlayer member : online) {
            names.add(Platform.playerName(member));
            ids.add(member.getUUID());
        }
        // Recorded on the way out rather than by the caller, so every push - join, leave, mode
        // change - resets what the tick compares against and none of them fires a second packet.
        boolean streaming = party.isStreaming(STREAM_IDLE_SECONDS);
        party.setStreamingPushed(streaming);
        ServerPlayer host = server.getPlayerList().getPlayer(party.hostId());
        if (host != null) {
            ServerPlayNetworking.send(host, new PartyStatePayload(true, true, streaming, party.hostId(),
                    party.hostName(), party.mode(), names, ids));
        }
        for (ServerPlayer member : online) {
            ServerPlayNetworking.send(member, new PartyStatePayload(true, false, streaming, party.hostId(),
                    party.hostName(), party.mode(), names, ids));
        }
    }
}
