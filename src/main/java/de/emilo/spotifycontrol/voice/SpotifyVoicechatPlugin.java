package de.emilo.spotifycontrol.voice;

import de.emilo.spotifycontrol.SpotifyControl;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent;

public class SpotifyVoicechatPlugin implements VoicechatPlugin {

    public static final String PLUGIN_ID = "spotifycontrol";

    @Override
    public String getPluginId() {
        return PLUGIN_ID;
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class, this::onServerStarted);
        registration.registerEvent(VoicechatServerStoppedEvent.class, this::onServerStopped);
    }

    private void onServerStarted(VoicechatServerStartedEvent event) {
        AudioBroadcaster.setServerApi(event.getVoicechat());
        SpotifyControl.LOGGER.info("Simple Voice Chat server API bound, broadcast mode available");
    }

    private void onServerStopped(VoicechatServerStoppedEvent event) {
        AudioBroadcaster.setServerApi(null);
    }
}
