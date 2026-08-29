package de.emilo.spotifycontrol.net;

import de.emilo.spotifycontrol.client.ClientState;
import de.emilo.spotifycontrol.net.payload.NowPlayingPayload;
import de.emilo.spotifycontrol.net.payload.PartyMarkersPayload;
import de.emilo.spotifycontrol.net.payload.PartyStatePayload;
import de.emilo.spotifycontrol.net.payload.ServerSettingsPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public final class ClientNetworking {

    private ClientNetworking() {
    }

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(PartyStatePayload.TYPE, (payload, context) ->
                context.client().execute(() -> ClientState.setParty(payload)));

        ClientPlayNetworking.registerGlobalReceiver(NowPlayingPayload.TYPE, (payload, context) ->
                context.client().execute(() -> ClientState.setRemoteTrack(payload)));

        ClientPlayNetworking.registerGlobalReceiver(ServerSettingsPayload.TYPE, (payload, context) ->
                context.client().execute(() -> ClientState.setServerSettings(payload)));

        ClientPlayNetworking.registerGlobalReceiver(PartyMarkersPayload.TYPE, (payload, context) ->
                context.client().execute(() -> ClientState.setPartyMarkers(payload)));
    }
}
