package de.emilo.spotifycontrol.spotify;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import de.emilo.spotifycontrol.SpotifyControl;
import de.emilo.spotifycontrol.config.ConfigManager;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

public class TokenStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public String accessToken = "";
    public String refreshToken = "";
    public long expiresAtEpochSecond;
    public String userId = "";
    public String displayName = "";
    public String product = "";
    public String country = "";

    /** Client id of the app these tokens were issued for. Empty for tokens saved before. */
    public String clientId = "";

    public static Path file() {
        return ConfigManager.configDirectory().resolve("tokens.json");
    }

    public static TokenStore load() {
        Path path = file();
        if (!Files.exists(path)) {
            return new TokenStore();
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            TokenStore parsed = GSON.fromJson(reader, TokenStore.class);
            return parsed == null ? new TokenStore() : parsed;
        } catch (Exception e) {
            SpotifyControl.LOGGER.error("Could not read Spotify tokens", e);
            return new TokenStore();
        }
    }

    public void save() {
        try (Writer writer = Files.newBufferedWriter(file(), StandardCharsets.UTF_8)) {
            GSON.toJson(this, writer);
        } catch (Exception e) {
            SpotifyControl.LOGGER.error("Could not write Spotify tokens", e);
        }
    }

    public void clear() {
        accessToken = "";
        refreshToken = "";
        expiresAtEpochSecond = 0L;
        userId = "";
        displayName = "";
        product = "";
        country = "";
        clientId = "";
        try {
            Files.deleteIfExists(file());
        } catch (Exception e) {
            SpotifyControl.LOGGER.error("Could not delete Spotify tokens", e);
        }
    }

    public boolean hasTokens() {
        return !accessToken.isEmpty() && !refreshToken.isEmpty();
    }

    public boolean expired() {
        return Instant.now().getEpochSecond() >= expiresAtEpochSecond - 30L;
    }

    /**
      * Whether Spotify explicitly reported a plan other than premium. The {@code product} field of
      * {@code GET /v1/me} is deprecated and may be absent, so an unknown plan must not be taken for
      * a free account. If the account really cannot control playback, the API says so itself with
      * {@code PREMIUM_REQUIRED}.
      */
    public boolean knownFreeAccount() {
        return !product.isEmpty() && !"premium".equalsIgnoreCase(product);
    }
}
