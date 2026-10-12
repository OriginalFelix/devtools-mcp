package systems.grebe.devtools.mcp.core;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import systems.grebe.devtools.mcp.api.CredentialInfo;
import systems.grebe.devtools.mcp.oauth.OAuthClient;

import static org.assertj.core.api.Assertions.assertThat;

/** „Im Browser anmelden“ im Modul Zugangsdaten gegen einen nachgebauten OIDC-Server mit Device Flow. */
class CredentialsLoginTest {

    private HttpServer oidc;
    private String issuer;
    private final List<CredentialInfo> stored = new ArrayList<>();
    private final List<URI> opened = new ArrayList<>();
    private CredentialsModule module;

    /** Ablage im Speicher statt im Backend. */
    private final CredentialStore store = new CredentialStore() {
        @Override
        public List<CredentialInfo> credentials() {
            return List.copyOf(stored);
        }

        @Override
        public void save(List<CredentialInfo> entries) {
            stored.clear();
            stored.addAll(entries);
        }

        @Override
        public void refresh(long id) {
            throw new UnsupportedOperationException();
        }
    };

    @BeforeEach
    void setUp() throws IOException {
        oidc = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        issuer = "http://127.0.0.1:" + oidc.getAddress().getPort();
        oidc.createContext("/.well-known/openid-configuration", ex -> json(ex, """
                {"issuer":"%s","token_endpoint":"%s/token","device_authorization_endpoint":"%s/device"}"""
                .formatted(issuer, issuer, issuer)));
        oidc.createContext("/device", ex -> json(ex, """
                {"device_code":"dc","user_code":"WXYZ-0000","verification_uri":"%s/activate","interval":1}"""
                .formatted(issuer)));
        oidc.createContext("/token", ex -> json(ex,
                "{\"access_token\":\"at-neu\",\"refresh_token\":\"rt-neu\",\"expires_in\":7200}"));
        oidc.start();
        StaticListableBeanFactory beans = new StaticListableBeanFactory(Map.of("store", store));
        module = new CredentialsModule(beans.getBeanProvider(CredentialStore.class), new OAuthClient(Duration.ofSeconds(5)),
                opened::add);
        stored.add(new CredentialInfo(1L, "GitHub", "https://github.com", null, "ghp_hand", null));
        stored.add(new CredentialInfo(2L, "GitLab", "https://gitlab.example.com", null, "", null, CredentialInfo.OAUTH,
                issuer, "app-id", null, "api", null, null));
    }

    @AfterEach
    void tearDown() {
        oidc.stop(0);
    }

    private static void json(HttpExchange ex, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(b);
        }
    }

    @Test
    void logsInInTheBrowserAndStoresTheTokens() {
        ModuleAction login = module.actions().getFirst();
        ModuleConfig config = ModuleConfig.of(module.configSchema(), Map.of());
        assertThat(login.targets(config)).containsExactly("GitLab");
        assertThat(login.describe(config, "GitLab")).isEqualTo("Nicht angemeldet");

        List<String> progress = new ArrayList<>();
        ModuleAction.ActionResult result = login.run(config, "GitLab", Set.of(), (m, f) -> progress.add(m));

        assertThat(result.success()).as(result.message()).isTrue();
        assertThat(progress).anyMatch(p -> p.contains("WXYZ-0000") && p.contains(issuer + "/activate"));
        assertThat(opened).containsExactly(URI.create(issuer + "/activate"));
        CredentialInfo gitlab = stored.get(1);
        assertThat(gitlab.secret()).isEqualTo("at-neu");
        assertThat(gitlab.refreshToken()).isEqualTo("rt-neu");
        assertThat(Instant.parse(gitlab.expiresAt())).isAfter(Instant.now().plusSeconds(7000));
        assertThat(stored.getFirst().secret()).isEqualTo("ghp_hand"); // die anderen bleiben, wie sie sind
        assertThat(login.describe(config, "GitLab")).startsWith("Angemeldet, Token gültig bis");
    }

    @Test
    void refusesEntriesThatAreNotOAuth() {
        ModuleAction.ActionResult result = module.actions().getFirst()
                .run(ModuleConfig.of(module.configSchema(), Map.of()), "GitHub", Set.of(), ModuleAction.Progress.NONE);
        assertThat(result.success()).isFalse();
        assertThat(opened).isEmpty();
    }

    @Test
    void statusTellsWhetherTheTokenExpires() {
        Instant now = Instant.parse("2026-10-12T10:00:00Z");
        CredentialInfo base = stored.get(1);
        assertThat(CredentialsModule.status(stored.getFirst(), now)).isEqualTo("Token von Hand");
        assertThat(CredentialsModule.status(base.withTokens("at", null, null), now))
                .isEqualTo("Angemeldet (Token läuft nicht ab)");
        assertThat(CredentialsModule.status(base.withTokens("at", "rt", "2026-10-12T09:00:00Z"), now))
                .startsWith("Token abgelaufen seit");
    }
}
