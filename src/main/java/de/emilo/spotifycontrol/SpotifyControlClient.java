package de.emilo.spotifycontrol;

import de.emilo.spotifycontrol.client.ClientState;
import de.emilo.spotifycontrol.client.KeyBindings;
import de.emilo.spotifycontrol.config.ConfigManager;
import de.emilo.spotifycontrol.spotify.SpotifyLauncher;
import de.emilo.spotifycontrol.gui.NowPlayingHud;
import de.emilo.spotifycontrol.net.ClientNetworking;
import de.emilo.spotifycontrol.net.payload.PartyStatePayload;
import de.emilo.spotifycontrol.net.payload.ServerSettingsPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import de.emilo.spotifycontrol.platform.Platform;
import net.minecraft.client.Minecraft;

public class SpotifyControlClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ConfigManager.loadClient();
        ClientState.bootstrap();
        KeyBindings.register();
        ClientNetworking.register();

        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
        Platform.registerHud(NowPlayingHud::render);

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            ClientState.setParty(PartyStatePayload.NONE);
            ClientState.session().pollNow();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ClientState.setParty(PartyStatePayload.NONE);
            ClientState.setServerSettings(ServerSettingsPayload.SINGLEPLAYER_DEFAULT);
            if (ConfigManager.client().pauseOnLeave) {
                ClientState.session().pauseIfPlaying();
            }
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            if (ConfigManager.client().closeSpotifyOnQuit) {
                SpotifyLauncher.quit();
            }
            ClientState.shutdown();
        });

        SpotifyControl.LOGGER.info("SpotifyControl client ready");
    }

    private void onClientTick(Minecraft client) {
        KeyBindings.tick(client);
        ClientState.session().tick();
        ClientState.reportNowPlayingIfHost();
        if (ConfigManager.client().muteMinecraftMusicWhileListening
                && ClientState.session().playback().playing()) {
            Platform.stopMusic(client);
        }
    }
}
