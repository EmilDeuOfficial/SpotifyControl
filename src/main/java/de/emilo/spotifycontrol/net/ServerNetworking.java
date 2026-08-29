package de.emilo.spotifycontrol.net;

import de.emilo.spotifycontrol.SpotifyControl;
import de.emilo.spotifycontrol.config.ConfigManager;
import de.emilo.spotifycontrol.config.Lang;
import de.emilo.spotifycontrol.config.ServerConfig;
import de.emilo.spotifycontrol.net.payload.AudioFramePayload;
import de.emilo.spotifycontrol.net.payload.NowPlayingPayload;
import de.emilo.spotifycontrol.net.payload.PartyActionPayload;
import de.emilo.spotifycontrol.net.payload.PartyMarkersPayload;
import de.emilo.spotifycontrol.net.payload.PartyStatePayload;
import de.emilo.spotifycontrol.net.payload.ServerSettingsPayload;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import de.emilo.spotifycontrol.platform.Platform;

public final class ServerNetworking {

    private ServerNetworking() {
    }

    public static void registerPayloads() {
        PayloadTypeRegistry.playC2S().register(PartyActionPayload.TYPE, PartyActionPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(AudioFramePayload.TYPE, AudioFramePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(NowPlayingPayload.TYPE, NowPlayingPayload.CODEC);

        PayloadTypeRegistry.playS2C().register(PartyStatePayload.TYPE, PartyStatePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(NowPlayingPayload.TYPE, NowPlayingPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ServerSettingsPayload.TYPE, ServerSettingsPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(PartyMarkersPayload.TYPE, PartyMarkersPayload.CODEC);
    }

    public static void registerReceivers() {
        ServerPlayNetworking.registerGlobalReceiver(PartyActionPayload.TYPE, (payload, context) ->
                context.server().execute(() -> handleAction(context.player(), payload)));

        ServerPlayNetworking.registerGlobalReceiver(NowPlayingPayload.TYPE, (payload, context) ->
                context.server().execute(() -> SpotifyControl.parties().updateNowPlaying(context.player(), payload)));

        ServerPlayNetworking.registerGlobalReceiver(AudioFramePayload.TYPE, (payload, context) ->
                SpotifyControl.parties().relayAudio(context.player(), payload));

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            sender.sendPacket(currentSettings());
            // Someone walking in has to be told who is already listening, or the marks above their
            // heads would only appear once the next party started or ended.
            sender.sendPacket(SpotifyControl.parties().markers());
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                SpotifyControl.parties().handleDisconnect(handler.player));
    }

    public static ServerSettingsPayload currentSettings() {
        ServerConfig config = ConfigManager.server();
        return new ServerSettingsPayload(
                config.enabled,
                config.enabled && config.allowListeningParties,
                config.enabled && config.allowAudioBroadcast && SpotifyControl.voicechatPresent(),
                SpotifyControl.voicechatPresent(),
                config.maxAudioKilobitsPerSecond,
                config.broadcastRangeBlocks);
    }

    public static void broadcastSettings(ServerPlayer player) {
        ServerPlayNetworking.send(player, currentSettings());
    }

    private static void handleAction(ServerPlayer player, PartyActionPayload payload) {
        if (!ConfigManager.server().enabled) {
            Platform.sendMessage(player, Lang.chat("mod.disabled_notice"));
            return;
        }
        switch (payload.action()) {
            case "start" -> SpotifyControl.parties().start(player, payload.mode());
            case "stop" -> SpotifyControl.parties().stop(player);
            case "join" -> SpotifyControl.parties().join(player, payload.target());
            case "leave" -> SpotifyControl.parties().leave(player);
            case "invite" -> SpotifyControl.parties().invite(player, payload.target());
            case "mode" -> SpotifyControl.parties().setMode(player, payload.mode());
            default -> SpotifyControl.LOGGER.debug("Unknown party action {}", payload.action());
        }
    }
}
