package systems.grebe.devtools.mcp.backend.account;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import systems.grebe.devtools.mcp.api.Grants;
import systems.grebe.devtools.mcp.api.Permission;
import systems.grebe.devtools.mcp.backend.BackendChanged;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Benutzer, Rollen und Anmeldung gegen eine H2-Datenbank mit dem Flyway-Schema der Core-Datenbank. */
class AccountServiceTest {

    private static final String WRONG = "Benutzername oder Passwort falsch.";

    private final List<Object> events = new ArrayList<>();
    private AccountRepository repo;
    private AccountService accounts;
    private RoleService roles;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:accounts-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(ds).locations("classpath:db/core").load().migrate();
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        repo = new AccountRepository(JdbcClient.create(ds));
        clock = new MutableClock(Instant.parse("2026-10-05T10:00:00Z"));
        accounts = new AccountService(repo, new Sha3Pbkdf2PasswordEncoder(1_000), tx, events::add,
                new LoginThrottle(clock), clock, "");
        roles = new RoleService(repo, accounts, tx, events::add);
    }

    @Test
    void migrationCreatesAdministratorAndUserRoles() {
        assertThat(roles.roles()).extracting(Role::name).containsExactly(Role.ADMINISTRATOR, Role.USER);
        Role admin = roles.roles().getFirst();
        assertThat(admin.builtin()).isTrue();
        assertThat(admin.grants().all()).isTrue();
        Role user = roles.roles().get(1);
        assertThat(user.permissions()).containsExactlyInAnyOrder(Grants.ALL_MODULES, Permission.SETTINGS_OWN.key(),
                Permission.PROJECTS_CREATE.key(), Permission.TOKENS_CREATE.key(), Permission.SHARES_ALL.key());
    }

    @Test
    void rightsOfAllRolesAddUp() {
        Role reviewer = roles.create("Reviewer", "nur lesen", List.of("module:git", "tool:ticket_get",
                Permission.TEMPLATES_PUBLISH.key()));
        Role builder = roles.create("Builder", null, List.of("module:build"));
        UserAccount u = accounts.create("Bob", "Bob", "Bob@Example.com", List.of("reviewer", "Builder"),
                "passwort-123", false);

        assertThat(u.username()).isEqualTo("bob");
        assertThat(u.email()).isEqualTo("bob@example.com");
        assertThat(u.roles()).containsExactly("Builder", "Reviewer");
        assertThat(u.has(Permission.TEMPLATES_PUBLISH)).isTrue();
        assertThat(u.has(Permission.USERS_MANAGE)).isFalse();
        assertThat(u.grants().moduleFull("git")).isTrue();
        assertThat(u.grants().tool("build", "build_run")).isTrue();
        assertThat(u.grants().tool("ticket", "ticket_get")).isTrue();
        assertThat(u.grants().tool("ticket", "ticket_comment")).isFalse();
        assertThat(u.grants().moduleUsable("ticket", List.of("ticket_get", "ticket_comment"))).isTrue();
        assertThat(u.grants().moduleUsable("ssh", List.of("ssh_exec"))).isFalse();
        assertThat(u.grants().moduleUsable("java", List.of())).isTrue();

        roles.update(builder.id(), "Builder", null, List.of());
        assertThat(accounts.user(u.id()).orElseThrow().grants().moduleFull("build")).isFalse();
        roles.delete(reviewer.id());
        assertThat(accounts.user(u.id()).orElseThrow().roles()).containsExactly("Builder");
        assertThat(events).contains(new AccountService.AccountChangedEvent(null));
        assertThat(events).anySatisfy(e -> assertThat(e).isInstanceOfSatisfying(BackendChanged.class,
                b -> assertThat(b.concerns(u.id())).isTrue()));
    }

    @Test
    void rolesAreValidated() {
        assertThatThrownBy(() -> roles.create("benutzer", null, List.of())).hasMessageContaining("gibt es schon");
        assertThatThrownBy(() -> roles.create("X", null, List.of("*"))).hasMessageContaining("nur die Rolle");
        assertThatThrownBy(() -> roles.create("X", null, List.of("root"))).hasMessageContaining("Unbekanntes Recht");
        assertThatThrownBy(() -> roles.create(" ", null, List.of())).hasMessageContaining("Rollenname");
        Role admin = roles.roles().getFirst();
        assertThatThrownBy(() -> roles.update(admin.id(), "Admin", null, List.of()))
                .hasMessageContaining("eingebaut");
        assertThatThrownBy(() -> roles.delete(admin.id())).hasMessageContaining("eingebaut");
        assertThatThrownBy(() -> accounts.create("eve", null, null, List.of("Gibtsnicht"), "passwort-123", false))
                .hasMessageContaining("Unbekannte Rolle");
        assertThat(accounts.userByName("eve")).isEmpty();
    }

    @Test
    void someoneMustStayAbleToManageUsers() {
        UserAccount admin = accounts.create("admin", null, null, List.of(Role.ADMINISTRATOR), "passwort-123", false);
        Role managers = roles.create("Verwalter", null, List.of(Permission.USERS_MANAGE.key()));
        UserAccount other = accounts.create("carl", null, null, List.of(Role.USER), "passwort-123", false);

        assertThatThrownBy(() -> accounts.update(admin.id(), null, null, List.of(Role.USER), true))
                .hasMessageContaining("mindestens ein aktiver Benutzer");
        assertThatThrownBy(() -> accounts.update(admin.id(), null, null, null, false))
                .hasMessageContaining("mindestens ein aktiver Benutzer");
        assertThatThrownBy(() -> accounts.delete(admin.id())).hasMessageContaining("mindestens ein aktiver Benutzer");
        assertThat(accounts.user(admin.id()).orElseThrow().admin()).isTrue(); // zurückgerollt

        accounts.update(other.id(), null, null, List.of("Verwalter"), true);
        accounts.update(admin.id(), null, null, List.of(Role.USER), true); // jetzt geht es
        assertThatThrownBy(() -> roles.update(managers.id(), "Verwalter", null, List.of()))
                .hasMessageContaining("mindestens ein aktiver Benutzer");
        assertThatThrownBy(() -> roles.delete(managers.id())).hasMessageContaining("mindestens ein aktiver Benutzer");
        assertThat(roles.role(managers.id())).isPresent();
    }

    @Test
    void authenticateChecksPasswordStatusAndThrottlesGuessing() {
        UserAccount u = accounts.create("dora", null, null, List.of(Role.USER), "passwort-123", false);
        assertThat(accounts.authenticate("Dora", "passwort-123").id()).isEqualTo(u.id());
        assertThat(accounts.user(u.id()).orElseThrow().lastLoginAt()).isEqualTo(clock.instant());
        assertThatThrownBy(() -> accounts.authenticate("dora", "falsch")).hasMessage(WRONG);
        assertThatThrownBy(() -> accounts.authenticate("niemand", "x")).hasMessage(WRONG);

        for (int i = 0; i < LoginThrottle.FREE_ATTEMPTS - 1; i++) {
            assertThatThrownBy(() -> accounts.authenticate("dora", "falsch")).hasMessageContaining("falsch");
        }
        // gesperrt – auch das richtige Passwort hilft erst nach Ablauf
        assertThatThrownBy(() -> accounts.authenticate("dora", "passwort-123"))
                .hasMessageContaining("Zu viele Fehlversuche");
        clock.advance(Duration.ofMinutes(2));
        assertThat(accounts.authenticate("dora", "passwort-123").id()).isEqualTo(u.id());

        accounts.update(u.id(), null, null, null, false);
        assertThatThrownBy(() -> accounts.authenticate("dora", "passwort-123")).hasMessageContaining("gesperrt");
    }

    @Test
    void passwordChangeRequirementIsClearedByChangingIt() {
        UserAccount u = accounts.create("emil", null, null, List.of(Role.USER), "start-passwort", true);
        assertThat(accounts.authenticate("emil", "start-passwort").passwordChangeRequired()).isTrue();
        assertThatThrownBy(() -> accounts.changePassword(u.id(), "falsch", "neues-passwort"))
                .hasMessageContaining("stimmt nicht");
        assertThatThrownBy(() -> accounts.changePassword(u.id(), "start-passwort", "start-passwort"))
                .hasMessageContaining("unterscheiden");
        accounts.changePassword(u.id(), "start-passwort", "neues-passwort");
        assertThat(accounts.user(u.id()).orElseThrow().passwordChangeRequired()).isFalse();

        accounts.resetPassword(u.id(), "vom-admin-1", true);
        assertThat(accounts.authenticate("emil", "vom-admin-1").passwordChangeRequired()).isTrue();
    }

    @Test
    void renameKeepsIdAndRoles() {
        UserAccount u = accounts.create("local", "Lokal", null, List.of(Role.ADMINISTRATOR), "passwort-123", false);
        accounts.create("fritz", null, null, List.of(), "passwort-123", false);
        assertThatThrownBy(() -> accounts.rename(u.id(), "Fritz")).hasMessageContaining("gibt es schon");
        UserAccount renamed = accounts.rename(u.id(), "Felix");
        assertThat(renamed.id()).isEqualTo(u.id());
        assertThat(renamed.username()).isEqualTo("felix");
        assertThat(renamed.roles()).containsExactly(Role.ADMINISTRATOR);
        assertThat(Set.copyOf(accounts.users().stream().map(UserAccount::username).toList()))
                .containsExactlyInAnyOrder("felix", "fritz");
    }

    /** Uhr, die der Test vorstellen kann. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
