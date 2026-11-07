package de.emilo.spotifycontrol.spotify;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import de.emilo.spotifycontrol.SpotifyControl;
import de.emilo.spotifycontrol.config.ConfigManager;
import de.emilo.spotifycontrol.config.Lang;
import de.emilo.spotifycontrol.platform.Platform;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

public class SpotifyAuth {

    public static final String SCOPES = String.join(" ",
            "user-read-private",
            "user-read-email",
            "user-read-playback-state",
            "user-modify-playback-state",
            "user-read-currently-playing",
            "user-read-recently-played",
            "user-top-read",
            "playlist-read-private",
            "playlist-read-collaborative",
            "playlist-modify-private",
            "playlist-modify-public",
            "user-library-read",
            "user-library-modify");

    private static final String AUTHORIZE_ENDPOINT = "https://accounts.spotify.com/authorize";
    private static final String TOKEN_ENDPOINT = "https://accounts.spotify.com/api/token";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final TokenStore tokens;
    private HttpServer callbackServer;
    private String codeVerifier = "";
    private String state = "";

    public SpotifyAuth(TokenStore tokens) {
        this.tokens = tokens;
    }

    private volatile String lastFailure = "";

    /**
     * Why the last token request failed, as an error code the user facing text is looked up by,
     * or empty when it succeeded.
     */
    public String lastFailure() {
        return lastFailure;
    }

    /** Hands the sentence for an error code to the status line and remembers the code. */
    private void fail(Consumer<String> onStatus, String code, Object... placeholders) {
        lastFailure = code;
        onStatus.accept(Lang.plain("error." + code, placeholders));
    }

    public static String redirectUri(int port) {
        return "http://127.0.0.1:" + port + "/callback";
    }

    public synchronized void beginLogin(Consumer<String> onStatus, Runnable onSuccess) {
        String clientId = ConfigManager.client().spotifyClientId.trim();
        if (clientId.isEmpty()) {
            onStatus.accept(Lang.plain("auth.no_client_id"));
            return;
        }
        stopCallbackServer();
        try {
            codeVerifier = randomUrlSafe(64);
            state = randomUrlSafe(16);
            String challenge = base64Url(sha256(codeVerifier));
            int port = ConfigManager.client().callbackPort;

            callbackServer = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            callbackServer.createContext("/callback", exchange -> handleCallback(exchange, onStatus, onSuccess));
            callbackServer.setExecutor(null);
            callbackServer.start();

            Map<String, String> query = new LinkedHashMap<>();
            query.put("client_id", clientId);
            query.put("response_type", "code");
            query.put("redirect_uri", redirectUri(port));
            query.put("state", state);
            query.put("scope", SCOPES);
            query.put("code_challenge_method", "S256");
            query.put("code_challenge", challenge);

            onStatus.accept(Lang.plain("auth.waiting"));
            Platform.openUri(AUTHORIZE_ENDPOINT + "?" + encode(query));
        } catch (Exception e) {
            SpotifyControl.LOGGER.error("Could not start the Spotify login", e);
            fail(onStatus, "login_start", "port", ConfigManager.client().callbackPort);
            stopCallbackServer();
        }
    }

    private void handleCallback(HttpExchange exchange, Consumer<String> onStatus, Runnable onSuccess) {
        Map<String, String> params = parseQuery(exchange.getRequestURI().getRawQuery());
        String code = params.get("code");
        String returnedState = params.get("state");
        boolean ok = code != null && state.equals(returnedState);
        respond(exchange, ok);
        if (!ok) {
            SpotifyControl.LOGGER.warn("Spotify login callback rejected: {}", params.getOrDefault("error", "state mismatch"));
            fail(onStatus, "access_denied".equals(params.get("error")) ? "auth_denied" : "auth_rejected");
            stopCallbackServer();
            return;
        }
        exchangeCode(code, onStatus, onSuccess);
        stopCallbackServer();
    }

    private void exchangeCode(String code, Consumer<String> onStatus, Runnable onSuccess) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", redirectUri(ConfigManager.client().callbackPort));
        form.put("client_id", ConfigManager.client().spotifyClientId.trim());
        form.put("code_verifier", codeVerifier);
        if (postToken(form, onStatus)) {
            onSuccess.run();
        }
    }

    public synchronized boolean refreshIfNeeded(Consumer<String> onStatus) {
        if (!tokens.hasTokens()) {
            return false;
        }
        if (!tokens.expired()) {
            return true;
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", tokens.refreshToken);
        form.put("client_id", ConfigManager.client().spotifyClientId.trim());
        return postToken(form, onStatus);
    }

    private boolean postToken(Map<String, String> form, Consumer<String> onStatus) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(TOKEN_ENDPOINT))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(encode(form)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status / 100 != 2) {
                SpotifyControl.LOGGER.error("Token request failed: {} {}", status, response.body());
                String reason = "";
                try {
                    reason = JsonParser.parseString(response.body()).getAsJsonObject().get("error").getAsString();
                } catch (Exception ignored) {
                    reason = "";
                }
                if (status >= 500) {
                    fail(onStatus, "server_busy");
                } else if ("invalid_client".equals(reason)) {
                    fail(onStatus, "auth_client");
                } else if ("invalid_grant".equals(reason)) {
                    fail(onStatus, "auth");
                } else {
                    fail(onStatus, "auth_rejected");
                }
                return false;
            }
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            tokens.accessToken = json.get("access_token").getAsString();
            if (json.has("refresh_token")) {
                tokens.refreshToken = json.get("refresh_token").getAsString();
            }
            long lifetime = json.has("expires_in") ? json.get("expires_in").getAsLong() : 3600L;
            tokens.expiresAtEpochSecond = Instant.now().getEpochSecond() + lifetime;
            tokens.clientId = ConfigManager.client().spotifyClientId.trim();
            tokens.save();
            lastFailure = "";
            return true;
        } catch (Exception e) {
            SpotifyControl.LOGGER.error("Token request failed", e);
            fail(onStatus, e instanceof java.io.IOException ? "network" : "auth_rejected");
            return false;
        }
    }

    public synchronized void stopCallbackServer() {
        if (callbackServer != null) {
            callbackServer.stop(0);
            callbackServer = null;
        }
    }

    private void respond(HttpExchange exchange, boolean success) {
        String body = success
                ? "<html><head><title>SpotifyControl</title></head><body style=\"background:#121212;color:#fff;"
                + "font-family:sans-serif;text-align:center;padding-top:120px\"><h2 style=\"color:#1DB954\">"
                + "Connected</h2><p>You can close this tab and return to Minecraft.</p></body></html>"
                : "<html><head><title>SpotifyControl</title></head><body style=\"background:#121212;color:#fff;"
                + "font-family:sans-serif;text-align:center;padding-top:120px\"><h2 style=\"color:#e22134\">"
                + "Login failed</h2><p>Try again from the overlay.</p></body></html>";
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(success ? 200 : 400, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (Exception e) {
            SpotifyControl.LOGGER.error("Could not answer the OAuth callback", e);
        }
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> result = new LinkedHashMap<>();
        if (raw == null) {
            return result;
        }
        for (String pair : raw.split("&")) {
            int index = pair.indexOf('=');
            if (index > 0) {
                result.put(java.net.URLDecoder.decode(pair.substring(0, index), StandardCharsets.UTF_8),
                        java.net.URLDecoder.decode(pair.substring(index + 1), StandardCharsets.UTF_8));
            }
        }
        return result;
    }

    private static String encode(Map<String, String> values) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!builder.isEmpty()) {
                builder.append('&');
            }
            builder.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return builder.toString();
    }

    private static String randomUrlSafe(int bytes) {
        byte[] buffer = new byte[bytes];
        new SecureRandom().nextBytes(buffer);
        return base64Url(buffer);
    }

    private static byte[] sha256(String value) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
    }

    private static String base64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
