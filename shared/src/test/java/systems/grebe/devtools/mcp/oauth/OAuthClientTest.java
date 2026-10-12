package systems.grebe.devtools.mcp.oauth;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** OAuth-Abläufe gegen einen nachgebauten Autorisierungsserver (JDK-HttpServer). */
class OAuthClientTest {

    private HttpServer server;
    private String base;
    private final OAuthClient client = new OAuthClient(Duration.ofSeconds(5));
    /** Formulare, die beim Token-Endpunkt ankamen, und deren Authorization-Header. */
    private final List<Map<String, String>> tokenRequests = new ArrayList<>();
    private final List<String> tokenAuthHeaders = new ArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void json(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(b);
        }
    }

    private Map<String, String> form(HttpExchange ex) throws IOException {
        Map<String, String> f = OAuthClient.query(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        return f;
    }

    /** OIDC-Discovery unter {@code /.well-known/openid-configuration} mit den angegebenen Endpunkten. */
    private void oidc(String issuer, boolean device, String authMethods) {
        server.createContext("/.well-known/openid-configuration", ex -> json(ex, 200, """
                {"issuer":"%s","authorization_endpoint":"%s/authorize","token_endpoint":"%s/token"%s%s}"""
                .formatted(issuer, base, base, device ? ",\"device_authorization_endpoint\":\"" + base + "/device\"" : "",
                        authMethods == null ? "" : ",\"token_endpoint_auth_methods_supported\":[" + authMethods + "]")));
    }

    @Test
    void discoversRfc8414BeforeOpenIdConnect() {
        server.createContext("/.well-known/oauth-authorization-server", ex -> json(ex, 200, """
                {"issuer":"%s","token_endpoint":"%s/as-token","device_authorization_endpoint":"%s/as-device"}"""
                .formatted(base, base, base)));
        oidc(base, false, null);

        OAuthServer s = client.discover(base + "/");
        assertThat(s.tokenEndpoint()).isEqualTo(base + "/as-token");
        assertThat(s.supportsDeviceFlow()).isTrue();
        assertThat(s.supportsAuthorizationCode()).isFalse();
    }

    @Test
    void fallsBackToOpenIdConnectAndChecksTheIssuer() {
        oidc(base, true, "\"client_secret_basic\"");
        OAuthServer s = client.discover(base);
        assertThat(s.authorizationEndpoint()).isEqualTo(base + "/authorize");
        assertThat(s.deviceAuthorizationEndpoint()).isEqualTo(base + "/device");
        assertThat(s.secretInBody()).isFalse();

        stop();
        assertThatThrownBy(() -> client.discover(base)).hasMessageContaining("Keine OAuth-/OIDC-Metadaten");
    }

    @Test
    void rejectsMetadataOfAnotherIssuer() {
        oidc("https://evil.example.com", true, null);
        assertThatThrownBy(() -> client.discover(base)).hasMessageContaining("https://evil.example.com");
    }

    @Test
    void knowsGitHubWithoutDiscovery() {
        assertThat(OAuthServer.githubWebBase("https://api.github.com")).isEqualTo("https://github.com");
        assertThat(OAuthServer.githubWebBase("https://ghe.example.com/api/v3")).isEqualTo("https://ghe.example.com");
        assertThat(OAuthServer.githubWebBase("https://gitlab.com")).isNull();
        OAuthServer gh = OAuthServer.github("https://github.com");
        assertThat(gh.deviceAuthorizationEndpoint()).isEqualTo("https://github.com/login/device/code");
        assertThat(gh.defaultScopes()).isEqualTo("repo read:org");
        assertThat(OAuthServer.issuerFor(null, "https://gitlab.example.com:8443/api/v4"))
                .isEqualTo("https://gitlab.example.com:8443");
        assertThat(OAuthServer.issuerFor(" https://sso.example.com/realms/dev ", "https://x"))
                .isEqualTo("https://sso.example.com/realms/dev");
    }

    @Test
    void deviceFlowPollsUntilTheUserConfirms() {
        oidc(base, true, null);
        server.createContext("/device", ex -> {
            assertThat(form(ex)).containsEntry("client_id", "app").containsEntry("scope", "api");
            json(ex, 200, """
                    {"device_code":"dev-1","user_code":"ABCD-1234","verification_uri":"%s/activate",
                     "verification_uri_complete":"%s/activate?code=ABCD-1234","expires_in":60,"interval":1}"""
                    .formatted(base, base));
        });
        AtomicInteger polls = new AtomicInteger();
        server.createContext("/token", ex -> {
            Map<String, String> f = form(ex);
            tokenRequests.add(f);
            if (polls.incrementAndGet() == 1) {
                json(ex, 400, "{\"error\":\"authorization_pending\"}");
            } else {
                json(ex, 200, "{\"access_token\":\"at-1\",\"refresh_token\":\"rt-1\",\"expires_in\":7200,"
                        + "\"token_type\":\"Bearer\"}");
            }
        });
        List<String> prompts = new ArrayList<>();
        List<URI> opened = new ArrayList<>();

        OAuthTokens t = client.login(client.discover(base), "app", null, "api", prompts::add, opened::add);

        assertThat(t.accessToken()).isEqualTo("at-1");
        assertThat(t.refreshToken()).isEqualTo("rt-1");
        assertThat(t.expiresAt()).isAfter(java.time.Instant.now().plusSeconds(7000));
        assertThat(prompts).singleElement().asString().contains("ABCD-1234").contains(base + "/activate");
        assertThat(opened).containsExactly(URI.create(base + "/activate?code=ABCD-1234"));
        assertThat(tokenRequests).hasSize(2).allSatisfy(f -> assertThat(f)
                .containsEntry("grant_type", OAuthClient.DEVICE_CODE_GRANT).containsEntry("device_code", "dev-1")
                .containsEntry("client_id", "app"));
    }

    @Test
    void errorsSentWithStatus200AreErrorsToo() {
        // GitHub antwortet beim Abfragen mit HTTP 200 und {"error": …}
        OAuthServer s = new OAuthServer(base, null, base + "/token", base + "/device", List.of());
        server.createContext("/token", ex -> json(ex, 200, "{\"error\":\"access_denied\"}"));
        OAuthClient.DeviceAuthorization d = new OAuthClient.DeviceAuthorization("dev", "CODE", base, null,
                java.time.Instant.now().plusSeconds(60), 1);
        assertThatThrownBy(() -> client.pollDevice(s, "app", null, d)).hasMessageContaining("abgelehnt");
    }

    @Test
    void authorizationCodeWithPkceViaLoopback() {
        oidc(base, false, null);
        server.createContext("/token", ex -> {
            Map<String, String> f = form(ex);
            tokenRequests.add(f);
            tokenAuthHeaders.add(ex.getRequestHeaders().getFirst("Authorization"));
            json(ex, 200, "{\"access_token\":\"at-code\"}");
        });
        List<String> challenges = new ArrayList<>();
        // „Browser“: Anmeldeseite bestätigt sofort und leitet mit Code und state an die Loopback-Adresse zurück
        java.util.function.Consumer<URI> browser = authorize -> {
            Map<String, String> q = OAuthClient.query(authorize.getRawQuery());
            assertThat(q).containsEntry("response_type", "code").containsEntry("client_id", "app")
                    .containsEntry("code_challenge_method", "S256").containsKey("state");
            assertThat(q.get("redirect_uri")).startsWith("http://127.0.0.1:").endsWith("/callback");
            challenges.add(q.get("code_challenge"));
            Thread.ofVirtual().start(() -> {
                try {
                    // fremder Aufruf ohne passendes state ändert nichts
                    get(q.get("redirect_uri") + "?code=evil&state=falsch");
                    get(q.get("redirect_uri") + "?code=good&state=" + q.get("state"));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        };

        OAuthTokens t = client.authorizationCode(client.discover(base), "app", "geheim", "read", Duration.ofSeconds(10),
                p -> { }, browser);

        assertThat(t.accessToken()).isEqualTo("at-code");
        assertThat(t.refreshToken()).isNull();
        assertThat(t.expiresAt()).isNull();
        Map<String, String> f = tokenRequests.getFirst();
        assertThat(f).containsEntry("grant_type", "authorization_code").containsEntry("code", "good")
                .containsEntry("client_id", "app").containsEntry("client_secret", "geheim");
        assertThat(OAuthClient.challenge(f.get("code_verifier"))).isEqualTo(challenges.getFirst());
        assertThat(tokenAuthHeaders.getFirst()).isNull();
    }

    @Test
    void refreshKeepsTheOldRefreshTokenAndUsesBasicAuthWhenRequired() {
        oidc(base, false, "\"client_secret_basic\"");
        server.createContext("/token", ex -> {
            tokenRequests.add(form(ex));
            tokenAuthHeaders.add(ex.getRequestHeaders().getFirst("Authorization"));
            json(ex, 200, "{\"access_token\":\"at-2\",\"expires_in\":60}");
        });

        OAuthTokens t = client.refresh(client.discover(base), "app", "geheim", "rt-1");

        assertThat(t.accessToken()).isEqualTo("at-2");
        assertThat(t.refreshToken()).isEqualTo("rt-1");
        assertThat(tokenRequests.getFirst()).containsEntry("grant_type", "refresh_token")
                .containsEntry("refresh_token", "rt-1").doesNotContainKeys("client_id", "client_secret");
        assertThat(tokenAuthHeaders.getFirst()).isEqualTo("Basic " + java.util.Base64.getEncoder()
                .encodeToString("app:geheim".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void invalidGrantComesAsOAuthException() {
        oidc(base, false, null);
        server.createContext("/token", ex -> json(ex, 400,
                "{\"error\":\"invalid_grant\",\"error_description\":\"Token widerrufen\"}"));
        assertThatThrownBy(() -> client.refresh(client.discover(base), "app", null, "rt"))
                .isInstanceOf(OAuthException.class).hasMessage("Token widerrufen")
                .satisfies(e -> assertThat(((OAuthException) e).code()).isEqualTo("invalid_grant"));
    }

    private static void get(String url) throws IOException, InterruptedException {
        HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.discarding());
    }
}
