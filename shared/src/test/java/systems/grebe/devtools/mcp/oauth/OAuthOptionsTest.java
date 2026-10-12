package systems.grebe.devtools.mcp.oauth;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Angaben von Hand: Endpunkte ohne Discovery, feste Rückleitung, weitere Parameter; Atlassian fest vorgegeben. */
class OAuthOptionsTest {

    private final OAuthClient client = new OAuthClient(Duration.ofSeconds(5));

    @Test
    void parsesEndpointsRedirectAndParameters() {
        OAuthOptions o = OAuthOptions.parse("""
                authorization_endpoint=https://jira.example.com/rest/oauth2/latest/authorize;
                token_endpoint = https://jira.example.com/rest/oauth2/latest/token
                redirect_uri=http://127.0.0.1:53682/callback; audience=api.example.com""");

        assertThat(o.manualEndpoints()).isTrue();
        assertThat(o.tokenEndpoint()).isEqualTo("https://jira.example.com/rest/oauth2/latest/token");
        assertThat(o.deviceAuthorizationEndpoint()).isNull();
        assertThat(o.redirectUri()).isEqualTo("http://127.0.0.1:53682/callback");
        assertThat(o.parameters()).containsExactly(Map.entry("audience", "api.example.com"));
        assertThat(OAuthOptions.parse(" ")).isSameAs(OAuthOptions.NONE);
    }

    @Test
    void rejectsWhatCannotWork() {
        assertThatThrownBy(() -> OAuthOptions.parse("audience")).hasMessageContaining("name=wert");
        assertThatThrownBy(() -> OAuthOptions.parse("token_endpoint=ftp://x")).hasMessageContaining("keine http(s)-URL");
        assertThatThrownBy(() -> OAuthOptions.parse("redirect_uri=https://app.example.com/cb"))
                .hasMessageContaining("Loopback");
        assertThatThrownBy(() -> OAuthOptions.parse("redirect_uri=http://127.0.0.1/callback"))
                .hasMessageContaining("Loopback"); // ohne Port kann die App nicht lauschen
    }

    @Test
    void manualEndpointsSkipDiscoveryAndAtlassianIsBuiltIn() {
        OAuthServer manual = client.resolve(null, "https://jira.example.com",
                OAuthOptions.parse("token_endpoint=https://jira.example.com/t; authorization_endpoint=https://jira.example.com/a"));
        assertThat(manual.issuer()).isEqualTo("https://jira.example.com");
        assertThat(manual.tokenEndpoint()).isEqualTo("https://jira.example.com/t");
        assertThat(manual.supportsDeviceFlow()).isFalse();

        OAuthServer atlassian = client.resolve(null, "https://firma.atlassian.net", OAuthOptions.NONE);
        assertThat(atlassian.tokenEndpoint()).isEqualTo("https://auth.atlassian.com/oauth/token");
        assertThat(atlassian.authorizationParameters()).containsEntry("audience", "api.atlassian.com");
        assertThat(atlassian.defaultScopes()).contains("read:jira-work").contains("offline_access");
        assertThat(OAuthServer.isAtlassian("https://auth.atlassian.com", "https://x")).isTrue();
        assertThat(OAuthServer.isAtlassian(null, "https://gitlab.com")).isFalse();
    }

    @Test
    void fixedRedirectAndParametersGoIntoTheAuthorizationRequest() throws IOException {
        int port;
        try (ServerSocket free = new ServerSocket(0)) {
            port = free.getLocalPort();
        }
        HttpServer token = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> forms = new ArrayList<>();
        token.createContext("/token", ex -> {
            forms.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] b = "{\"access_token\":\"at\",\"refresh_token\":\"rt\",\"expires_in\":3600}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(b);
            }
        });
        token.start();
        String base = "http://127.0.0.1:" + token.getAddress().getPort();
        try {
            // Server mit Device Flow – eine feste redirect_uri heißt trotzdem: Anmeldung mit Rückleitung
            OAuthServer server = new OAuthServer(base, base + "/authorize", base + "/token", base + "/device", List.of(),
                    Map.of("audience", "api.example.com", "prompt", "consent"));
            OAuthOptions options = OAuthOptions.parse("redirect_uri=http://127.0.0.1:" + port + "/cb; prompt=login");
            List<URI> opened = new ArrayList<>();

            OAuthTokens t = client.login(server, "app", "geheim", "read", options, p -> { }, authorize -> {
                opened.add(authorize);
                Map<String, String> q = OAuthClient.query(authorize.getRawQuery());
                Thread.ofVirtual().start(() -> {
                    try {
                        HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(q.get("redirect_uri")
                                + "?code=c1&state=" + q.get("state"))).build(), HttpResponse.BodyHandlers.discarding());
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
            });

            assertThat(t.accessToken()).isEqualTo("at");
            Map<String, String> q = OAuthClient.query(opened.getFirst().getRawQuery());
            assertThat(q).containsEntry("redirect_uri", "http://127.0.0.1:" + port + "/cb")
                    .containsEntry("audience", "api.example.com").containsEntry("prompt", "login")
                    .containsEntry("client_id", "app").containsEntry("response_type", "code");
            assertThat(OAuthClient.query(forms.getFirst())).containsEntry("redirect_uri", "http://127.0.0.1:" + port + "/cb")
                    .containsEntry("code", "c1");
        } finally {
            token.stop(0);
        }
    }
}
