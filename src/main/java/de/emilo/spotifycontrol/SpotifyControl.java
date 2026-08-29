package de.emilo.spotifycontrol;

import de.emilo.spotifycontrol.commands.SpotifyControlCommand;
import de.emilo.spotifycontrol.config.ConfigManager;
import de.emilo.spotifycontrol.net.ServerNetworking;
import de.emilo.spotifycontrol.party.PartyManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SpotifyControl implements ModInitializer {

    public static final String MOD_ID = "spotifycontrol";
    public static final Logger LOGGER = LoggerFactory.getLogger("SpotifyControl");

    private static PartyManager partyManager;

    public static PartyManager parties() {
        return partyManager;
    }

    public static boolean voicechatPresent() {
        return FabricLoader.getInstance().isModLoaded("voicechat");
    }

    @Override
    public void onInitialize() {
        ConfigManager.loadServer();
        partyManager = new PartyManager();

        ServerNetworking.registerPayloads();
        ServerNetworking.registerReceivers();
        SpotifyControlCommand.register();

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> partyManager.shutdown());
        ServerTickEvents.END_SERVER_TICK.register(server -> partyManager.tick(server));

        LOGGER.info("SpotifyControl initialized (voice chat {})", voicechatPresent() ? "detected" : "absent");
    }
}
