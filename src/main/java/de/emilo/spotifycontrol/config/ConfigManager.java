package de.emilo.spotifycontrol.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import de.emilo.spotifycontrol.SpotifyControl;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ConfigManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static ServerConfig server = new ServerConfig();
    private static ClientConfig client = new ClientConfig();

    private ConfigManager() {
    }

    public static ServerConfig server() {
        return server;
    }

    public static ClientConfig client() {
        return client;
    }

    public static Path configDirectory() {
        Path directory = FabricLoader.getInstance().getConfigDir().resolve(SpotifyControl.MOD_ID);
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            SpotifyControl.LOGGER.error("Could not create config directory", e);
        }
        return directory;
    }

    public static void loadServer() {
        server = read(configDirectory().resolve("server.json"), ServerConfig.class, new ServerConfig());
        Lang.load(server.language);
    }

    public static void loadClient() {
        client = read(configDirectory().resolve("client.json"), ClientConfig.class, new ClientConfig());
        Lang.load(client.language);
    }

    public static void saveServer() {
        write(configDirectory().resolve("server.json"), server);
    }

    public static void saveClient() {
        write(configDirectory().resolve("client.json"), client);
    }

    private static <T> T read(Path file, Class<T> type, T fallback) {
        if (!Files.exists(file)) {
            write(file, fallback);
            return fallback;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            T parsed = GSON.fromJson(reader, type);
            return parsed == null ? fallback : parsed;
        } catch (Exception e) {
            SpotifyControl.LOGGER.error("Could not read {}, using defaults", file.getFileName(), e);
            return fallback;
        }
    }

    private static void write(Path file, Object value) {
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(value, writer);
        } catch (IOException e) {
            SpotifyControl.LOGGER.error("Could not write {}", file.getFileName(), e);
        }
    }
}
