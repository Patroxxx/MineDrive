package ca.justpatrox.minedrive.data;

import ca.justpatrox.minedrive.MineDRIVE;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

public final class OAuthManager {
    private static final String DEFAULT_PUBLIC_CLIENT_ID = "862359790378-itgg3fcqb1vtl4h5lnkhkkftkqmpmuev.apps.googleusercontent.com";
    private static final String DEFAULT_PUBLIC_BROKER_BASE_URL = "https://minedrive-oauth-broker.justpatroxcontact.workers.dev";
    private static final String AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(20);
    private static final int DEFAULT_LOOPBACK_PORT = 53682;

    private static final HttpClient CLIENT = HttpClient.newBuilder().build();

    private OAuthManager() {}

    public static AuthResult connectInteractive() {
        try {
            OAuthClientCredentials credentials = defaultOAuthClientCredentials();
            String codeVerifier = generateCodeVerifier();
            String codeChallenge = generateCodeChallenge(codeVerifier);

            HttpServer callbackServer = HttpServer.create(new InetSocketAddress("127.0.0.1", credentials.redirectPort), 0);
            int port = callbackServer.getAddress().getPort();
            String redirectUri = "http://127.0.0.1:" + port + "/oauth2callback";
            ArrayBlockingQueue<OAuthCallback> queue = new ArrayBlockingQueue<>(1);

            callbackServer.createContext("/oauth2callback", exchange -> handleCallback(exchange, queue, callbackServer));
            callbackServer.start();

            String authUrl = AUTH_ENDPOINT
                    + "?client_id=" + enc(credentials.clientId)
                    + "&redirect_uri=" + enc(redirectUri)
                    + "&response_type=code"
                    + "&scope=" + enc(DRIVE_SCOPE)
                    + "&access_type=offline"
                    + "&prompt=consent"
                    + "&code_challenge=" + enc(codeChallenge)
                    + "&code_challenge_method=S256";

            if (!openAuthUrl(authUrl)) {
                callbackServer.stop(0);
                return AuthResult.error("Could not open browser automatically. Open this URL manually: " + authUrl);
            }

            OAuthCallback callback = queue.poll(180, TimeUnit.SECONDS);
            callbackServer.stop(0);
            if (callback == null) return AuthResult.error("OAuth timed out. Please try again.");
            if (callback.error != null) return AuthResult.error("OAuth failed: " + callback.error);
            if (callback.code == null || callback.code.isBlank()) return AuthResult.error("OAuth callback missing code.");

            TokenResponse tokenResponse = exchangeAuthorizationCode(callback.code, codeVerifier, redirectUri, credentials);

            String email = NetworkManager.fetchAuthorizedGoogleEmail(tokenResponse.accessToken);
            if (email == null || email.isBlank()) {
                return AuthResult.error("Could not read Google account email from Drive API.");
            }

            Config config = ConfigManager.getCurrentConfig();
            config.setGoogleSession(email, tokenResponse.accessToken, tokenResponse.refreshToken, tokenResponse.expiresInSeconds);
            ConfigManager.save(config);

            return AuthResult.success(email);
        } catch (Exception e) {
            MineDRIVE.LOGGER.error("OAuth connect failed", e);
            return AuthResult.error("OAuth connect failed: " + e.getMessage());
        }
    }

    public static String getValidAccessToken(Config config) {
        if (config == null) return "";
        config.migrateLegacyFieldsIfNeeded();

        if (!config.isAccessTokenMissingOrExpired()) {
            return config.getAccessToken();
        }

        if (!config.hasRefreshToken()) {
            return config.getAccessToken();
        }

        TokenResponse refreshed = refreshAccessToken(config.getRefreshToken());
        if (refreshed == null || refreshed.accessToken == null || refreshed.accessToken.isBlank()) {
            return config.getAccessToken();
        }

        String existingRefresh = config.getRefreshToken();
        String refresh = (refreshed.refreshToken == null || refreshed.refreshToken.isBlank()) ? existingRefresh : refreshed.refreshToken;
        config.setGoogleSession(config.googleAccount, refreshed.accessToken, refresh, refreshed.expiresInSeconds);
        ConfigManager.save(config);
        return config.getAccessToken();
    }

    private static TokenResponse exchangeAuthorizationCode(String code, String codeVerifier, String redirectUri, OAuthClientCredentials credentials) throws IOException, InterruptedException {
        TokenResponse viaBroker = exchangeAuthorizationCodeViaBroker(code, codeVerifier, redirectUri, credentials);
        if (viaBroker == null || viaBroker.accessToken == null || viaBroker.accessToken.isBlank()) {
            throw new RuntimeException("Token exchange via broker failed.");
        }
        return viaBroker;
    }

    private static TokenResponse refreshAccessToken(String refreshToken) {
        try {
            return refreshAccessTokenViaBroker(refreshToken, defaultOAuthClientCredentials());
        } catch (Exception e) {
            MineDRIVE.LOGGER.error("OAuth token refresh failed", e);
            return null;
        }
    }

    private static TokenResponse parseTokenResponse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        TokenResponse tr = new TokenResponse();
        tr.accessToken = root.has("accessToken")
                ? root.get("accessToken").getAsString()
                : (root.has("access_token") ? root.get("access_token").getAsString() : "");
        tr.refreshToken = root.has("refreshToken")
                ? root.get("refreshToken").getAsString()
                : (root.has("refresh_token") ? root.get("refresh_token").getAsString() : "");
        tr.expiresInSeconds = root.has("expiresInSeconds")
                ? root.get("expiresInSeconds").getAsLong()
                : (root.has("expires_in") ? root.get("expires_in").getAsLong() : 3600L);
        return tr;
    }

    private static void handleCallback(HttpExchange exchange, ArrayBlockingQueue<OAuthCallback> queue, HttpServer server) throws IOException {
        String query = exchange.getRequestURI().getRawQuery();
        String code = null;
        String error = null;
        if (query != null) {
            for (String part : query.split("&")) {
                int idx = part.indexOf('=');
                if (idx <= 0) continue;
                String key = decode(part.substring(0, idx));
                String value = decode(part.substring(idx + 1));
                if ("code".equals(key)) code = value;
                if ("error".equals(key)) error = value;
            }
        }

        OAuthCallback callback = new OAuthCallback();
        callback.code = code;
        callback.error = error;

        // Send response first so the browser gets the nice HTML message
        String html = "<html>"
                + "<head>"
                + "<meta charset=\"UTF-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">"
                + "<style>"
                + "  body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; background-color: #0a0a0a; color: #ffffff; display: flex; justify-content: center; align-items: center; height: 100vh; margin: 0; }"
                + "  .card { background-color: #171717; padding: 2.5rem; border-radius: 0.75rem; box-shadow: 0 10px 30px rgba(0, 0, 0, 0.5); text-align: center; max-width: 400px; border: 1px solid #262626; }"
                + "  h2 { color: #ffffff; margin-top: 0; margin-bottom: 0.75rem; font-size: 1.5rem; font-weight: 600; letter-spacing: -0.025em; }"
                + "  p { color: #a3a3a3; margin: 0; font-size: 0.95rem; line-height: 1.5; }"
                + "</style>"
                + "</head>"
                + "<body>"
                + "  <div class=\"card\">"
                + "    <h2>MineDrive Connected</h2>"
                + "    <p>You can close this tab and return to Minecraft.</p>"
                + "  </div>"
                + "</body>"
                + "</html>";

        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }

        // Push to queue and gracefully stop the server after a short delay
        queue.offer(callback);

        new Thread(() -> {
            try {
                Thread.sleep(500); // Give the browser time to render
            } catch (InterruptedException ignored) {}
            server.stop(0);
        }).start();
    }

    private static String generateCodeVerifier() {
        byte[] bytes = new byte[64];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String generateCodeChallenge(String verifier) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String decode(String value) {
        return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String compactError(String responseBody) {
        try {
            JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
            String error = root.has("error") ? root.get("error").getAsString() : "";
            String desc = root.has("error_description") ? root.get("error_description").getAsString() : "";
            if (!error.isBlank() && !desc.isBlank()) return error + " - " + desc;
            if (!error.isBlank()) return error;
        } catch (Exception ignored) {}
        return responseBody;
    }

    private static OAuthClientCredentials defaultOAuthClientCredentials() {
        return new OAuthClientCredentials(DEFAULT_PUBLIC_CLIENT_ID, DEFAULT_PUBLIC_BROKER_BASE_URL, DEFAULT_LOOPBACK_PORT);
    }

    private static boolean openAuthUrl(String authUrl) {
        // Try Java Desktop first.
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(authUrl));
                return true;
            }
        } catch (Exception ignored) {}

        // Try Minecraft platform opener via reflection (avoids hard compile dependency differences).
        try {
            Class<?> utilClass = Class.forName("net.minecraft.Util");
            Object platform = utilClass.getMethod("getPlatform").invoke(null);

            try {
                platform.getClass().getMethod("openUri", URI.class).invoke(platform, URI.create(authUrl));
                return true;
            } catch (NoSuchMethodException ignored) {
                platform.getClass().getMethod("openUri", String.class).invoke(platform, authUrl);
                return true;
            }
        } catch (Exception ignored) {}

        // Last-resort OS command.
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            Process process;
            if (os.contains("mac")) {
                process = new ProcessBuilder("open", authUrl).start();
            } else if (os.contains("win")) {
                process = new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", authUrl).start();
            } else {
                process = new ProcessBuilder("xdg-open", authUrl).start();
            }
            return process.isAlive() || process.exitValue() == 0;
        } catch (IllegalThreadStateException e) {
            return true; // process still running -> launched
        } catch (Exception ignored) {}

        return false;
    }

    private static TokenResponse exchangeAuthorizationCodeViaBroker(String code, String codeVerifier, String redirectUri, OAuthClientCredentials credentials) throws IOException, InterruptedException {
        String endpoint = normalizeBrokerEndpoint(credentials.brokerBaseUrl, "/oauth/exchange");
        JsonObject body = new JsonObject();
        body.addProperty("code", code);
        body.addProperty("codeVerifier", codeVerifier);
        body.addProperty("redirectUri", redirectUri);
        body.addProperty("clientId", credentials.clientId);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Broker exchange failed (" + response.statusCode() + "): " + compactError(response.body()));
        }
        return parseTokenResponse(response.body());
    }

    private static TokenResponse refreshAccessTokenViaBroker(String refreshToken, OAuthClientCredentials credentials) throws IOException, InterruptedException {
        String endpoint = normalizeBrokerEndpoint(credentials.brokerBaseUrl, "/oauth/refresh");
        JsonObject body = new JsonObject();
        body.addProperty("refreshToken", refreshToken);
        body.addProperty("clientId", credentials.clientId);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            MineDRIVE.LOGGER.error("OAuth token refresh via broker failed: {} {}", response.statusCode(), response.body());
            return null;
        }
        return parseTokenResponse(response.body());
    }

    private static String normalizeBrokerEndpoint(String baseUrl, String path) {
        String cleanBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return cleanBase + path;
    }

    public static final class AuthResult {
        public final boolean success;
        public final String message;
        public final String email;

        private AuthResult(boolean success, String message, String email) {
            this.success = success;
            this.message = message;
            this.email = email;
        }

        public static AuthResult success(String email) {
            return new AuthResult(true, "Connected", email);
        }

        public static AuthResult error(String message) {
            return new AuthResult(false, message, "");
        }
    }

    private static final class OAuthCallback {
        String code;
        String error;
    }

    private static final class TokenResponse {
        String accessToken;
        String refreshToken;
        long expiresInSeconds;
    }

    private static final class OAuthClientCredentials {
        final String clientId;
        final String brokerBaseUrl;
        final int redirectPort;

        private OAuthClientCredentials(String clientId, String brokerBaseUrl, int redirectPort) {
            this.clientId = clientId;
            this.brokerBaseUrl = brokerBaseUrl;
            this.redirectPort = redirectPort;
        }
    }
}
