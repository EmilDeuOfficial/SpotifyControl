package de.emilo.spotifycontrol.voice;

import de.emilo.spotifycontrol.SpotifyControl;
import de.maxhenkel.voicechat.api.VoicechatClientApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.ClientVoicechatInitializationEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;

public class SpotifyVoicechatClientPlugin implements VoicechatPlugin {

    private static volatile VoicechatClientApi clientApi;

    public static VoicechatClientApi clientApi() {
        return clientApi;
    }

    @Override
    public String getPluginId() {
        return "spotifycontrol_client";
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(ClientVoicechatInitializationEvent.class, this::onClientInitialized);
    }

    private void onClientInitialized(ClientVoicechatInitializationEvent event) {
        clientApi = event.getVoicechat();
        SpotifyControl.LOGGER.info("Simple Voice Chat client API bound, desktop audio capture available");
    }
}
