package systems.grebe.devtools.mcp.backend.account;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import systems.grebe.devtools.mcp.api.CredentialInfo;
import systems.grebe.devtools.mcp.backend.BackendChanged;
import systems.grebe.devtools.mcp.config.SecretCipher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/** Zugangsdaten je Benutzer gegen eine H2-Datenbank mit dem Flyway-Schema der Core-Datenbank. */
class CredentialServiceTest {

    @TempDir
    Path home;

    private final List<Object> events = new ArrayList<>();
    private JdbcClient jdbc;
    private CredentialService credentials;
    private long alice;
    private long bob;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:credentials-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(ds).locations("classpath:db/core").load().migrate();
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc = JdbcClient.create(ds);
        AccountService accounts = new AccountService(new AccountRepository(jdbc), new Sha3Pbkdf2PasswordEncoder(1_000),
                tx, events::add, new LoginThrottle(Clock.systemUTC()), Clock.systemUTC(), "");
        alice = accounts.create("alice", "Alice", "alice@example.com", List.of(), "passwort-123", false).id();
        bob = accounts.create("bob", "Bob", "bob@example.com", List.of(), "passwort-123", false).id();
        credentials = new CredentialService(new CredentialRepository(jdbc), new SecretCipher(home.resolve("secret.key")),
                tx, events::add);
        events.clear();
    }

    private static CredentialInfo entry(Long id, String name, String url, String secret) {
        return new CredentialInfo(id, name, url, null, secret, null);
    }

    @Test
    void storesSecretsEncryptedPerUserInOrder() {
        credentials.replace(alice, List.of(entry(null, "GitHub", "https://github.com", "gh-geheim"),
                new CredentialInfo(null, " Jira ", "https://firma.atlassian.net", "me@example.com", "jira-geheim",
                        "Cloud")));

        assertThat(credentials.list(alice)).extracting(CredentialInfo::name, CredentialInfo::secret,
                        CredentialInfo::username)
                .containsExactly(tuple("GitHub", "gh-geheim", null),
                        tuple("Jira", "jira-geheim", "me@example.com"));
        assertThat(credentials.list(bob)).isEmpty();
        List<String> stored = jdbc.sql("SELECT secret FROM credential").query(String.class).list();
        assertThat(stored).hasSize(2).allMatch(s -> s.startsWith("enc:v1:"))
                .noneMatch(s -> s.contains("geheim"));
        assertThat(events).containsExactly(BackendChanged.of(BackendChanged.Topic.SETTINGS, alice));
    }

    @Test
    void keepsSecretOfExistingEntryWhenNoneIsGiven() {
        credentials.replace(alice, List.of(entry(null, "GitHub", "https://github.com", "gh-geheim")));
        long id = credentials.list(alice).getFirst().id();

        credentials.replace(alice, List.of(entry(id, "GitHub Firma", "https://github.com/firma", null),
                entry(null, "Sonar", "https://sonar.example.com", "sonar")));

        assertThat(credentials.list(alice)).extracting(CredentialInfo::name, CredentialInfo::secret)
                .containsExactly(tuple("GitHub Firma", "gh-geheim"),
                        tuple("Sonar", "sonar"));
        // fremde IDs helfen nicht: Bob bekommt Alices Geheimnis nicht
        long aliceId = credentials.list(alice).getFirst().id();
        assertThatThrownBy(() -> credentials.replace(bob, List.of(entry(aliceId, "Klau", "https://github.com", null))))
                .hasMessageContaining("Token/Passwort fehlt");
    }

    @Test
    void summariesLeaveOutSecrets() {
        credentials.replace(alice, List.of(entry(null, "GitHub", "https://github.com", "gh-geheim")));

        assertThat(credentials.summaries(alice)).singleElement()
                .satisfies(c -> assertThat(c.secret()).isNull())
                .satisfies(c -> assertThat(c.name()).isEqualTo("GitHub"));
        // so speichert die Web-UI: ohne Geheimnis, das bisherige bleibt
        credentials.replace(alice, credentials.summaries(alice));
        assertThat(credentials.list(alice).getFirst().secret()).isEqualTo("gh-geheim");
    }

    @Test
    void rejectsInvalidEntriesWithoutChangingAnything() {
        credentials.replace(alice, List.of(entry(null, "GitHub", "https://github.com", "gh")));

        assertThatThrownBy(() -> credentials.replace(alice, List.of(entry(null, "A", "https://a.example.com", "x"),
                entry(null, "a", "https://b.example.com", "y")))).hasMessageContaining("Eintrag 2").hasMessageContaining("doppelt");
        assertThatThrownBy(() -> credentials.replace(alice, List.of(entry(null, "A", "ftp://a.example.com", "x"))))
                .hasMessageContaining("http(s)-URL");
        assertThatThrownBy(() -> credentials.replace(alice, List.of(entry(null, " ", "https://a.example.com", "x"))))
                .hasMessageContaining("Name fehlt");

        assertThat(credentials.list(alice)).extracting(CredentialInfo::name).containsExactly("GitHub");
    }

    @Test
    void deletingTheUserDeletesItsCredentials() {
        credentials.replace(alice, List.of(entry(null, "GitHub", "https://github.com", "gh")));
        jdbc.sql("DELETE FROM app_user WHERE id = ?").param(alice).update();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM credential").query(Long.class).single()).isZero();
    }
}
