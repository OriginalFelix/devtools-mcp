package systems.grebe.devtools.mcp.backend.account;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import systems.grebe.devtools.mcp.api.CredentialInfo;
import systems.grebe.devtools.mcp.config.SecretCipher;
import systems.grebe.devtools.mcp.oauth.OAuthClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Zugangsdaten mit Anmeldung per OAuth: speichern, behalten, im Backend erneuern (gegen einen nachgebauten Server). */
class CredentialOAuthTest {

    private static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");

    @TempDir
    Path home;

    private HttpServer oidc;
    private String issuer;
    private final AtomicInteger refreshes = new AtomicInteger();
    private final List<String> refreshTokensSeen = new ArrayList<>();
    private JdbcClient jdbc;
    private CredentialService credentials;
    private long alice;

    @BeforeEach
    void setUp() throws IOException {
        oidc = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        issuer = "http://127.0.0.1:" + oidc.getAddress().getPort();
        oidc.createContext("/.well-known/openid-configuration", ex -> json(ex, 200, """
                {"issuer":"%s","authorization_endpoint":"%s/authorize","token_endpoint":"%s/token",
                 "device_authorization_endpoint":"%s/device"}""".formatted(issuer, issuer, issuer, issuer)));
        oidc.createContext("/token", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String rt = body.replaceAll(".*refresh_token=([^&]*).*", "$1");
            refreshTokensSeen.add(rt);
            if (rt.equals("widerrufen")) {
                json(ex, 400, "{\"error\":\"invalid_grant\",\"error_description\":\"Token widerrufen\"}");
                return;
            }
            int n = refreshes.incrementAndGet(); // GitLab: jedes Erneuern tauscht das Refresh-Token aus
            json(ex, 200, "{\"access_token\":\"at-" + n + "\",\"refresh_token\":\"rt-" + n + "\",\"expires_in\":7200}");
        });
        oidc.start();

        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:oauth-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(ds).locations("classpath:db/core").load().migrate();
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc = JdbcClient.create(ds);
        AccountService accounts = new AccountService(new AccountRepository(jdbc), new Sha3Pbkdf2PasswordEncoder(1_000),
                tx, e -> { }, new LoginThrottle(Clock.systemUTC()), Clock.systemUTC(), "");
        alice = accounts.create("alice", "Alice", "alice@example.com", List.of(), "passwort-123", false).id();
        credentials = new CredentialService(new CredentialRepository(jdbc), new SecretCipher(home.resolve("secret.key")),
                tx, e -> { }, new OAuthClient(Duration.ofSeconds(5), Clock.fixed(NOW, ZoneOffset.UTC)),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() {
        oidc.stop(0);
    }

    private static void json(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(b);
        }
    }

    private CredentialInfo oauthEntry(Long id) {
        return new CredentialInfo(id, "GitLab", "https://gitlab.example.com", null, null, null, CredentialInfo.OAUTH,
                issuer, "app-id", null, "api", null, null);
    }

    /** Wie nach „Im Browser anmelden“ in der Desktop-App. */
    private long loggedIn(String refreshToken, Instant expires) {
        credentials.replace(alice, List.of(oauthEntry(null)));
        CredentialInfo saved = credentials.list(alice).getFirst();
        credentials.replace(alice, List.of(saved.withTokens("at-0", refreshToken, expires.toString())));
        return credentials.list(alice).getFirst().id();
    }

    @Test
    void oauthEntryNeedsNoTokenUntilLoggedIn() {
        credentials.replace(alice, List.of(oauthEntry(null)));

        CredentialInfo c = credentials.list(alice).getFirst();
        assertThat(c.oauth()).isTrue();
        assertThat(c.secret()).isEmpty(); // API-Version 1 verlangt einen Wert
        assertThat(c.clientId()).isEqualTo("app-id");
        assertThat(c.refreshToken()).isNull();
        assertThatThrownBy(() -> credentials.replace(alice, List.of(new CredentialInfo(null, "X", "https://x.example.com",
                null, null, null, CredentialInfo.OAUTH, null, " ", null, null, null, null))))
                .hasMessageContaining("Client-ID fehlt");
    }

    @Test
    void loginTokensAreKeptWhenTheEntryIsSavedAgainWithoutThem() {
        long id = loggedIn("rt-0", NOW.plusSeconds(7200));
        assertThat(jdbc.sql("SELECT refresh_token FROM credential").query(String.class).single())
                .startsWith("enc:v1:").doesNotContain("rt-0");

        // Desktop-Formular bzw. Web-UI speichern ohne Access-Token, Refresh-Token und Ablauf
        CredentialInfo c = credentials.list(alice).getFirst();
        credentials.replace(alice, List.of(new CredentialInfo(id, "GitLab neu", c.url(), null, null, "Arbeit",
                CredentialInfo.OAUTH, c.issuer(), c.clientId(), "", c.scopes(), null, null)));

        CredentialInfo after = credentials.list(alice).getFirst();
        assertThat(after.name()).isEqualTo("GitLab neu");
        assertThat(after.secret()).isEqualTo("at-0");
        assertThat(after.expiresAt()).isEqualTo(NOW.plusSeconds(7200).toString());
        assertThat(credentials.summaries(alice).getFirst().secret()).isNull();
    }

    @Test
    void refreshRotatesTokensOnlyWhenDue() {
        long fresh = loggedIn("rt-0", NOW.plus(Duration.ofHours(1)));
        credentials.refresh(alice, fresh);
        assertThat(refreshes).hasValue(0); // läuft erst in einer Stunde ab

        long due = loggedIn("rt-0", NOW.plus(Duration.ofMinutes(3)));
        credentials.refresh(alice, due);
        credentials.refresh(alice, due); // zweite Desktop-App kurz danach: schon erneuert

        assertThat(refreshes).hasValue(1);
        CredentialInfo c = credentials.list(alice).getFirst();
        assertThat(c.secret()).isEqualTo("at-1");
        assertThat(c.expiresAt()).isEqualTo(NOW.plusSeconds(7200).toString());

        // das neue Refresh-Token gilt beim nächsten Mal
        jdbc.sql("UPDATE credential SET expires_at = ?").param(java.sql.Timestamp.from(NOW)).update();
        credentials.refresh(alice, due);
        assertThat(refreshTokensSeen).containsExactly("rt-0", "rt-1");
    }

    @Test
    void manualEndpointsAreKeptValidatedAndUsedForRefreshWithoutDiscovery() {
        String options = "token_endpoint=" + issuer + "/token; authorization_endpoint=" + issuer + "/authorize";
        credentials.replace(alice, List.of(new CredentialInfo(null, "Jira DC", "https://jira.example.com", null, null,
                null, CredentialInfo.OAUTH, null, "app-id", null, null, null, null, options)));
        CredentialInfo c = credentials.list(alice).getFirst();
        assertThat(c.options()).isEqualTo(options);

        // Anmeldung über die API-Version 2 (kennt options nicht, null): die Angaben bleiben
        credentials.replace(alice, List.of(new CredentialInfo(c.id(), c.name(), c.url(), null, "at-0", null,
                CredentialInfo.OAUTH, null, "app-id", null, null, "rt-0", NOW.plusSeconds(60).toString())));
        long id = credentials.list(alice).getFirst().id();
        assertThat(credentials.list(alice).getFirst().options()).isEqualTo(options);
        // ungültige Angaben werden abgelehnt
        assertThatThrownBy(() -> credentials.replace(alice, List.of(new CredentialInfo(id, "Jira DC",
                "https://jira.example.com", null, null, null, CredentialInfo.OAUTH, null, "app-id", null, null, null,
                null, "redirect_uri=https://evil.example.com/cb"))))
                .hasMessageContaining("Eintrag 1").hasMessageContaining("Loopback");

        // jira.example.com hat keine Discovery – erneuert wird direkt am token_endpoint
        credentials.refresh(alice, id);
        assertThat(credentials.list(alice).getFirst().secret()).isEqualTo("at-1");
        assertThat(refreshTokensSeen).containsExactly("rt-0");
    }

    @Test
    void rejectedRefreshAsksToLogInAgain() {
        long id = loggedIn("widerrufen", NOW.minusSeconds(1));
        assertThatThrownBy(() -> credentials.refresh(alice, id))
                .hasMessageContaining("invalid_grant").hasMessageContaining("neu anmelden");
        assertThatThrownBy(() -> credentials.refresh(alice + 1, id)).hasMessageContaining("Unbekannte Zugangsdaten");
    }
}
