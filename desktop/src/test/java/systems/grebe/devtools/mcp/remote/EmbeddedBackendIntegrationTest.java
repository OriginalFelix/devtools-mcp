package systems.grebe.devtools.mcp.remote;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
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
import org.springframework.test.annotation.DirtiesContext;
import systems.grebe.devtools.mcp.api.ProjectInfo;
import systems.grebe.devtools.mcp.backend.account.AccountService;
import systems.grebe.devtools.mcp.backend.account.Role;
import systems.grebe.devtools.mcp.backend.account.RoleService;
import systems.grebe.devtools.mcp.backend.account.UserAccount;
import systems.grebe.devtools.mcp.backend.profile.Profile;
import systems.grebe.devtools.mcp.backend.profile.ProfileRepository;
import systems.grebe.devtools.mcp.backend.profile.ProfileService;
import systems.grebe.devtools.mcp.config.ModuleSettings;
import systems.grebe.devtools.mcp.config.SettingsStore;
import systems.grebe.devtools.mcp.core.ToolRegistry;
import systems.grebe.devtools.mcp.core.ToolScope;
import systems.grebe.devtools.mcp.core.Workspaces;
import systems.grebe.devtools.mcp.modules.memories.MemoryBackend;
import systems.grebe.devtools.mcp.modules.memories.MemoryViews;
import systems.grebe.devtools.mcp.modules.skills.SkillBackend;
import systems.grebe.devtools.mcp.modules.skills.SkillViews;
import systems.grebe.devtools.mcp.profile.Overrides;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Desktop-App mit eingebettetem Backend: erstes Konto aus den Anmeldedaten, Übernahme der alten Einstellungen,
 * Speichern ins aktive Profil über GraphQL, Subscriptions (Änderung wie aus der Web-UI kommt sofort an), Profilwechsel,
 * Projekte mit lokalem Verzeichnis, Skills des angemeldeten Benutzers, Abmelden und Rechte auf Module und Tools.
 */
// Kontext nach der Klasse schließen: die Graph-Datenbank des Backends hält sonst Dateien im temporären Ordner offen
@DirtiesContext
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"devtools.local-user.email=local@example.com", "devtools.login.username=tester",
                "devtools.login.password=tester-passwort"})
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
    MemoryBackend memories;

    @Autowired
    BackendFiles files;

    @Autowired
    ProfileService profiles;

    @Autowired
    AccountService accounts;

    @Autowired
    RoleService roles;

    @Autowired
    EmbeddedAccounts embeddedAccounts;

    static final String USER = "tester";
    static final String PASSWORD = "tester-passwort";

    private long localUserId() {
        return accounts.userByName(USER).orElseThrow().id();
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
        // Start: eingebettet, erstes Konto aus den Anmeldedaten (Administrator), alte Einstellungen global
        assertThat(backend.embedded()).isTrue();
        assertThat(backend.status()).isEqualTo(BackendConnection.Status.ONLINE);
        assertThat(backend.me().orElseThrow().username()).isEqualTo(USER);
        assertThat(backend.me().orElseThrow().email()).isEqualTo("local@example.com");
        assertThat(backend.me().orElseThrow().admin()).isTrue();
        assertThat(embeddedAccounts.setupRequired()).isFalse();
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
    void attachmentsTravelOverHttpInParts(@TempDir Path dir) throws Exception {
        // größer als ein Teil, damit der Upload über mehrere Anfragen läuft
        byte[] data = new byte[BackendFiles.CHUNK * 2 + 123];
        new java.util.Random(7).nextBytes(data);
        Path big = Files.write(dir.resolve("dump.bin"), data);

        skills.create("mit-anhang", "Mit Anhang.", "Siehe assets/dump.bin", null, null, 5_000);
        assertThat(skills.attachFile("mit-anhang", "assets/dump.bin", big, null, null))
                .contains("application/octet-stream", "angelegt");
        SkillViews.File f = skills.file("mit-anhang", "assets/dump.bin").orElseThrow();
        assertThat(f.size()).isEqualTo(data.length);
        assertThat(store.dir().resolve("blobs/local@example.com").resolve(f.blob())).hasBinaryContent(data);
        skills.exportFile("mit-anhang", "assets/dump.bin", dir.resolve("skill.bin"));
        assertThat(dir.resolve("skill.bin")).hasBinaryContent(data);
        skills.writeFile("mit-anhang", "references/a.md", "# A", null, 5_000);
        skills.exportFile("mit-anhang", "references/a.md", dir.resolve("a.md"));
        assertThat(dir.resolve("a.md")).hasContent("# A");

        String saved = memories.save("Mit Datei", "Siehe Anhang.", null, null, null, null, List.of(), 5_000);
        long id = Long.parseLong(saved.replaceAll("^Memory #(\\d+).*$", "$1"));
        assertThat(memories.attachFile(id, null, big, null, false)).contains("'dump.bin'", "angehängt");
        assertThat(memories.details(id).orElseThrow().files()).extracting(MemoryViews.File::path)
                .containsExactly("dump.bin");
        memories.exportFile(id, "dump.bin", dir.resolve("memory.bin"));
        assertThat(dir.resolve("memory.bin")).hasBinaryContent(data);
        assertThat(memories.removeFile(id, "dump.bin", false)).contains("entfernt");

        assertThatThrownBy(() -> files.download("0".repeat(64), dir.resolve("x")))
                .hasMessageContaining("gibt es nicht");
        // ohne Token kein Zugriff auf die Ablage
        var anonymous = java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(
                java.net.URI.create(backend.url() + "/blobs/" + f.blob())).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(anonymous.statusCode()).isEqualTo(401);
    }

    @Test
    void aFailingListenerDoesNotEndTheSkillSubscription() throws InterruptedException {
        java.util.concurrent.atomic.AtomicInteger good = new java.util.concurrent.atomic.AtomicInteger();
        backend.addSkillListener(() -> {
            throw new IllegalStateException("Listener kaputt");
        });
        backend.addSkillListener(good::incrementAndGet);
        skills.create("listener-a", "Erstes Ereignis.", "x", null, null, 5_000);
        await(() -> good.get() >= 1, "erstes Ereignis trotz werfendem Listener");
        int after = good.get();
        skills.create("listener-b", "Zweites Ereignis.", "x", null, null, 5_000);
        await(() -> good.get() > after, "zweites Ereignis: Subscription lebt noch");
        assertThat(backend.status()).isEqualTo(BackendConnection.Status.ONLINE);
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
        var other = accounts.create("bob", null, "bob@example.com", List.of(Role.USER), "passwort-123", false);
        var projects = registryBean(systems.grebe.devtools.mcp.backend.project.ProjectService.class);
        var p = projects.create(other.id(), "lib", null, null, null);
        projects.share(other.id(), p.id(), USER, systems.grebe.devtools.mcp.backend.project.Project.Access.READ);
        try {
            awaitQuietly(() -> backend.projects().stream().anyMatch(i -> i.id() == p.id()));
            backend.setProjectPath(p.id(), dir.toString());
            assertThatThrownBy(() -> Workspaces.requireWritable(dir.toAbsolutePath().normalize()))
                    .hasMessageContaining("nur lesend");
        } finally {
            backend.setProjectPath(p.id(), null);
        }
    }

    @Test
    void withoutLoginThereAreNoTools() {
        assertThat(registry.activeToolCount()).isPositive();
        try {
            assertThatThrownBy(() -> backend.login(USER, "falsch")).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("falsch");
            assertThat(backend.signedIn()).isTrue(); // falsches Passwort ändert nichts

            backend.logout();
            assertThat(backend.signedIn()).isFalse();
            assertThat(backend.status()).isEqualTo(BackendConnection.Status.SIGNED_OUT);
            assertThat(registry.activeToolCount()).isZero();
            assertThat(registry.settings("git").enabled()).isFalse();
            assertThatThrownBy(() -> registry.updateConfig("sonar", Map.of("organization", "x")))
                    .hasMessageContaining("Nicht angemeldet");
        } finally {
            backend.login(USER, PASSWORD);
        }
        assertThat(registry.activeToolCount()).isPositive();
    }

    @Test
    void rolesLimitModulesAndToolsAndChangesApplyImmediately() throws InterruptedException {
        List<String> gitTools = registry.availableTools("git").stream().map(t -> t.name()).toList();
        assertThat(gitTools).hasSizeGreaterThan(1);
        String allowed = gitTools.stream().filter(t -> registry.isToolActive("git", t)).findFirst().orElseThrow();
        String other = gitTools.stream().filter(t -> !t.equals(allowed)).findFirst().orElseThrow();
        Role role = roles.create("Nur " + allowed, null, List.of("tool:" + allowed));
        UserAccount carol = accounts.create("carol", null, "carol@example.com", List.of(role.name()),
                "carol-passwort", false);
        try {
            assertThat(backend.login("carol", "carol-passwort")).isEqualTo(BackendConnection.LoginOutcome.SIGNED_IN);
            assertThat(backend.me().orElseThrow().roles()).containsExactly(role.name());
            assertThat(registry.toolPermitted("git", allowed)).isTrue();
            assertThat(registry.toolPermitted("git", other)).isFalse();
            assertThat(registry.modulePermitted("git")).isTrue();
            assertThat(registry.modulePermitted("sonar")).isFalse();
            assertThat(registry.lockedKeys("sonar")).contains("@enabled", "@tools");
            assertThat(registry.activeToolNames()).containsExactly(allowed);
            // keine Rechte auf die Skill-Tools: auch das Backend lehnt ab
            assertThatThrownBy(() -> skills.create("x", "d", "c", null, null, 5_000))
                    .hasMessageContaining("skills_create");

            // Rolle ändern (wie in der Web-UI): kommt per Subscription an, die Tools werden neu aufgebaut
            roles.update(role.id(), role.name(), null, List.of("tool:" + allowed, "module:skills"));
            await(() -> registry.modulePermitted("skills"), "Recht per Subscription");
            assertThat(registry.toolPermitted("skills", "skills_create")).isTrue();
            assertThat(registry.activeToolNames()).contains(allowed);
        } finally {
            backend.login(USER, PASSWORD);
            accounts.delete(carol.id());
            roles.delete(role.id());
        }
        assertThat(registry.modulePermitted("sonar")).isTrue();
    }

    @Test
    void setupTakesOverTheFormerLocalAccount() throws IOException {
        // Stand vor der Anmeldung: Konto „local“ ohne bekanntes Passwort, noch nicht eingerichtet
        UserAccount local = accounts.create(EmbeddedAccounts.LEGACY_USERNAME, "Lokal", "alt@example.com",
                List.of(Role.USER), AccountService.randomPassword(), false);
        Path marker = store.dir().resolve("account-setup.done");
        Files.deleteIfExists(marker);
        try {
            assertThat(embeddedAccounts.setupRequired()).isTrue();
            assertThat(embeddedAccounts.legacyAccount()).isPresent();
            assertThat(embeddedAccounts.suggestedEmail()).isEqualTo("local@example.com");

            UserAccount lena = embeddedAccounts.setup("Lena", "Lena L.", "", "lenas-passwort");
            assertThat(lena.id()).isEqualTo(local.id()); // Profile, Projekte, Skills hängen an ID bzw. E-Mail
            assertThat(lena.username()).isEqualTo("lena");
            assertThat(lena.email()).isEqualTo("local@example.com");
            assertThat(lena.roles()).contains(Role.ADMINISTRATOR);
            assertThat(embeddedAccounts.setupRequired()).isFalse();
            assertThatThrownBy(() -> embeddedAccounts.setup("x", null, null, "noch-ein-passwort"))
                    .hasMessageContaining("schon ein Konto");
            assertThat(accounts.authenticate("lena", "lenas-passwort").id()).isEqualTo(local.id());
        } finally {
            Files.writeString(marker, "test");
            accounts.delete(local.id());
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

    @Autowired
    ProfileRepository profileRepository;

    /** Überschreibungen entfallener Felder (z.B. aus der Neo4j-Zeit) dürfen das Speichern nicht blockieren. */
    @Test
    void staleOverridesOfRemovedFieldsDoNotBlockSaving() {
        long profileId = profiles.activeProfile(localUserId()).id();
        profileRepository.replaceOverrides(Overrides.Level.PROFILE, profileId, "graph", List.of(
                new ProfileRepository.OverrideRow("neo4jUser", "neo4j", false),
                new ProfileRepository.OverrideRow("storage", "neo4j", false),
                new ProfileRepository.OverrideRow("includeTests", "false", false)));
        registry.updateConfig("graph", Map.of("maxFiles", "1234"));
        assertThat(profiles.overrides(Overrides.Level.PROFILE, profileId, "graph").values())
                .containsEntry("maxFiles", "1234").containsEntry("includeTests", "false")
                .doesNotContainKeys("neo4jUser", "storage");
    }
}
