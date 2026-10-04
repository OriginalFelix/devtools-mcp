package systems.grebe.devtools.mcp.remote;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import systems.grebe.devtools.mcp.api.ProjectInfo;
import systems.grebe.devtools.mcp.backend.account.AccountService;
import systems.grebe.devtools.mcp.backend.profile.Profile;
import systems.grebe.devtools.mcp.backend.profile.ProfileService;
import systems.grebe.devtools.mcp.config.ModuleSettings;
import systems.grebe.devtools.mcp.config.SettingsStore;
import systems.grebe.devtools.mcp.core.ToolRegistry;
import systems.grebe.devtools.mcp.core.ToolScope;
import systems.grebe.devtools.mcp.core.Workspaces;
import systems.grebe.devtools.mcp.modules.skills.SkillBackend;
import systems.grebe.devtools.mcp.profile.Overrides;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Desktop-App mit eingebettetem Backend: Übernahme der alten Einstellungen, Speichern ins aktive Profil über GraphQL,
 * Subscriptions (Änderung wie aus der Web-UI kommt sofort an), Profilwechsel, Projekte mit lokalem Verzeichnis und
 * Skills des lokalen Benutzers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "devtools.local-user.email=local@example.com")
class EmbeddedBackendIntegrationTest {

    @TempDir
    static Path home;

    @TestConfiguration
    static class Config {
        /** Einstellungen wie vor dem Backend: werden beim ersten Start übernommen. */
        @Bean
        @Primary
        SettingsStore settingsStore() {
            SettingsStore store = new SettingsStore(home);
            store.saveModule("sonar", new ModuleSettings(true, Set.of("sonar_hotspots"),
                    Map.of("organization", "legacy-org", "gibtsnicht", "x")), Set.of());
            return store;
        }
    }

    @Autowired
    BackendConnection backend;

    @Autowired
    ToolRegistry registry;

    @Autowired
    SettingsStore store;

    @Autowired
    SkillBackend skills;

    @Autowired
    ProfileService profiles;

    @Autowired
    AccountService accounts;

    private long localUserId() {
        return accounts.userByName(LocalUser.USERNAME).orElseThrow().id();
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("nicht eingetreten: " + what);
            }
            Thread.sleep(50);
        }
    }

    @Test
    void embeddedBackendWithLegacyImportProfilesProjectsAndSkills(@TempDir Path repo) throws Exception {
        // Start: eingebettet, lokaler Benutzer, alte Einstellungen als globale Vorgaben
        assertThat(backend.embedded()).isTrue();
        assertThat(backend.status()).isEqualTo(BackendConnection.Status.ONLINE);
        assertThat(backend.me().orElseThrow().username()).isEqualTo(LocalUser.USERNAME);
        assertThat(backend.me().orElseThrow().email()).isEqualTo("local@example.com");
        assertThat(registry.settings("sonar").enabled()).isTrue();
        assertThat(registry.settings("sonar").values()).containsEntry("organization", "legacy-org")
                .doesNotContainKey("gibtsnicht");
        assertThat(registry.settings("sonar").disabledTools()).contains("sonar_hotspots");
        assertThat(profiles.overrides(Overrides.Level.GLOBAL, 0, "sonar").values())
                .containsEntry("organization", "legacy-org");

        // Speichern aus der App → Überschreibung im aktiven Profil, settings.json bleibt
        registry.updateConfig("sonar", Map.of("organization", "neu-org"));
        assertThat(registry.settings("sonar").values()).containsEntry("organization", "neu-org");
        Profile work = profiles.activeProfile(localUserId());
        assertThat(profiles.overrides(Overrides.Level.PROFILE, work.id(), "sonar").values())
                .containsOnly(Map.entry("organization", "neu-org"));
        assertThat(store.module("sonar").orElseThrow().values()).containsEntry("organization", "legacy-org");
        registry.setToolEnabled("sonar", "sonar_hotspots", true);
        assertThat(registry.isToolActive("sonar", "sonar_hotspots")).isTrue();

        // Änderung „aus der Web-UI“ (direkt im Backend) kommt per Subscription an
        profiles.saveOverrides(localUserId(), Overrides.Level.USER, localUserId(), "sonar",
                new Overrides(null, Map.of(), Map.of("timeoutSeconds", "42")));
        await(() -> "42".equals(registry.settings("sonar").values().get("timeoutSeconds")), "Subscription");

        // Profilwechsel: neues Profil ohne Profil-Überschreibungen
        Profile homeProfile = profiles.create(localUserId(), "Home", null);
        backend.activateProfile(homeProfile.id());
        assertThat(backend.settings().orElseThrow().profileName()).isEqualTo("Home");
        assertThat(registry.settings("sonar").values()).containsEntry("organization", "legacy-org")
                .containsEntry("timeoutSeconds", "42");
        backend.activateProfile(work.id());

        // Projekt anlegen, Verzeichnis zuordnen → Git kennt es unter seinem Namen
        Files.createDirectories(repo.resolve(".git"));
        ProjectInfo shop = backend.createProject("shop", "Webshop");
        backend.setProjectPath(shop.id(), repo.toString());
        // Die Projektliste kommt zusätzlich per Subscription: eine ältere Nachricht (ohne „shop“) kann die Antwort von
        // createProject kurz überholen, bis die zur Anlage gehörende Nachricht eintrifft
        String shopRepo = "shop=" + repo.toAbsolutePath().normalize();
        await(() -> String.valueOf(registry.settings("git").values().get("repositories")).contains(shopRepo),
                "Projekt in den Git-Einstellungen");
        assertThat(ToolScope.LOCAL.canWrite(repo)).isTrue();

        // Skills gehören dem lokalen Benutzer
        assertThat(skills.create("deploy", "Deployment-Ablauf.", "1. bauen", null, null, 5_000)).contains("angelegt");
        assertThat(skills.list(null, null)).contains("deploy");
        assertThatThrownBy(() -> skills.create("deploy", "x", "y", null, null, 5_000))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("existiert bereits");
        assertThat(skills.details("deploy")).get().satisfies(d -> assertThat(d.content()).isEqualTo("1. bauen"));
    }

    @Test
    void lockedFieldsAreRejectedWhenSaving() throws InterruptedException {
        profiles.saveGlobal("sonar", new Overrides(null, Map.of(), Map.of("organization", "fest")));
        profiles.setLocks("sonar", Set.of("organization"));
        try {
            await(() -> registry.lockedKeys("sonar").contains("organization"), "Sperre per Subscription");
            assertThatThrownBy(() -> registry.updateConfig("sonar", Map.of("organization", "anders")))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("gesperrt");
        } finally {
            profiles.setLocks("sonar", Set.of());
        }
    }

    @Test
    void readOnlyProjectsBlockWrites(@TempDir Path dir) throws IOException {
        // Freigabe „nur lesen“ lässt sich eingebettet nur mit einem zweiten Benutzer nachstellen
        var other = accounts.create("bob", null, "bob@example.com", systems.grebe.devtools.mcp.backend.account.Role.USER,
                "passwort-123");
        var projects = registryBean(systems.grebe.devtools.mcp.backend.project.ProjectService.class);
        var p = projects.create(other.id(), "lib", null, null, null);
        projects.share(other.id(), p.id(), LocalUser.USERNAME, systems.grebe.devtools.mcp.backend.project.Project.Access.READ);
        try {
            awaitQuietly(() -> backend.projects().stream().anyMatch(i -> i.id() == p.id()));
            backend.setProjectPath(p.id(), dir.toString());
            assertThatThrownBy(() -> Workspaces.requireWritable(dir.toAbsolutePath().normalize()))
                    .hasMessageContaining("nur lesend");
        } finally {
            backend.setProjectPath(p.id(), null);
        }
    }

    @Autowired
    org.springframework.context.ApplicationContext context;

    private <T> T registryBean(Class<T> type) {
        return context.getBean(type);
    }

    private static void awaitQuietly(BooleanSupplier condition) {
        try {
            await(condition, "Projekt per Subscription");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
