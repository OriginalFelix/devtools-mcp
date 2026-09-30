package systems.grebe.devtools.mcp.remote;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import systems.grebe.devtools.mcp.api.Catalog;
import systems.grebe.devtools.mcp.api.Me;
import systems.grebe.devtools.mcp.api.ModuleDescriptor;
import systems.grebe.devtools.mcp.api.ModuleOverlay;
import systems.grebe.devtools.mcp.api.ProjectInfo;
import systems.grebe.devtools.mcp.api.SettingsSnapshot;
import systems.grebe.devtools.mcp.config.SettingsStore;
import systems.grebe.devtools.mcp.core.ToolRegistry;
import systems.grebe.devtools.mcp.core.ToolScope;
import systems.grebe.devtools.mcp.core.Workspaces;
import systems.grebe.devtools.mcp.modules.skills.SkillStore;
import systems.grebe.devtools.mcp.modules.skills.SkillTestContext;
import systems.grebe.devtools.mcp.modules.skills.SkillUser;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Desktop-App gegen einen nachgebauten Team-Server: Katalog melden, Vorgaben und Projekte übernehmen, Profilwechsel,
 * nur lesend freigegebene Projekte, Skills auf dem Server, Offline-Cache und Trennen.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "devtools.team.sync-seconds=3600")
class TeamServerIntegrationTest {

    static final String TOKEN = "test-token";

    @TempDir
    static Path home;

    static HttpServer fake;
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final AtomicReference<SettingsSnapshot> SETTINGS = new AtomicReference<>();
    static final AtomicReference<List<ProjectInfo>> PROJECTS = new AtomicReference<>(List.of());
    static final AtomicReference<Catalog> CATALOG = new AtomicReference<>();
    static final AtomicInteger SETTINGS_FULL = new AtomicInteger();
    static final List<String> SKILL_CALLS = new CopyOnWriteArrayList<>();
    static volatile long activeProfile = 1;

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        SettingsStore settingsStore() {
            return new SettingsStore(home);
        }

        @Bean
        @Primary
        SkillUser testSkillUser(SettingsStore settingsStore) {
            return SkillTestContext.user(settingsStore, "lokal@example.com");
        }
    }

    @BeforeAll
    static void startFake() throws IOException {
        fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fake.createContext("/api/", TeamServerIntegrationTest::handle);
        fake.start();
        setProfile(1);
    }

    /** Neuer Fake-Server auf demselben Port (nach {@code stop}). */
    static void restartFake() throws IOException {
        int port = fake.getAddress().getPort();
        fake = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        fake.createContext("/api/", TeamServerIntegrationTest::handle);
        fake.start();
    }

    @AfterAll
    static void stopFake() {
        fake.stop(0);
    }

    static String url() {
        return "http://127.0.0.1:" + fake.getAddress().getPort();
    }

    static void setProfile(long id) {
        activeProfile = id;
        SETTINGS.set(id == 1
                ? new SettingsSnapshot(1, "Work", Map.of("sonar", new ModuleOverlay(true, Map.of("sonar_hotspots", false),
                        Map.of("organization", "team-org"), Set.of("organization"))))
                : new SettingsSnapshot(2, "Home", Map.of("git", new ModuleOverlay(false, Map.of(), Map.of(), Set.of()))));
    }

    static void handle(HttpExchange ex) throws IOException {
        try (ex) {
            if (!("Bearer " + TOKEN).equals(ex.getRequestHeaders().getFirst("Authorization"))) {
                respond(ex, 401, "{\"error\":\"nein\"}", null);
                return;
            }
            String path = ex.getRequestURI().getPath();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            switch (ex.getRequestMethod() + " " + path) {
                case "GET /api/me" -> respond(ex, 200, JSON.writeValueAsString(new Me(7, "anna", "Anna",
                        "anna@example.com", false, List.of(new Me.ProfileInfo(1, "Work", null),
                        new Me.ProfileInfo(2, "Home", null)), activeProfile)), null);
                case "PUT /api/catalog" -> {
                    CATALOG.set(JSON.readValue(body, Catalog.class));
                    respond(ex, 204, null, null);
                }
                case "GET /api/settings" -> etagged(ex, JSON.writeValueAsString(SETTINGS.get()), true);
                case "GET /api/projects" -> etagged(ex, JSON.writeValueAsString(PROJECTS.get()), false);
                case "PUT /api/profile/active" -> {
                    setProfile(JSON.readTree(body).get("profileId").asLong());
                    respond(ex, 204, null, null);
                }
                case "POST /api/skills/list" -> {
                    SKILL_CALLS.add("list");
                    respond(ex, 200, "{\"text\":\"Skills vom Server\"}", null);
                }
                case "POST /api/skills/create" -> {
                    SKILL_CALLS.add("create");
                    respond(ex, 400, "{\"error\":\"Skill 'x' existiert bereits.\"}", null);
                }
                default -> respond(ex, 404, "{}", null);
            }
        }
    }

    static void etagged(HttpExchange ex, String body, boolean countFull) throws IOException {
        String etag = "\"" + Integer.toHexString(body.hashCode()) + "\"";
        if (etag.equals(ex.getRequestHeaders().getFirst("If-None-Match"))) {
            respond(ex, 304, null, etag);
            return;
        }
        if (countFull) {
            SETTINGS_FULL.incrementAndGet();
        }
        respond(ex, 200, body, etag);
    }

    static void respond(HttpExchange ex, int status, String body, String etag) throws IOException {
        if (etag != null) {
            ex.getResponseHeaders().add("ETag", etag);
        }
        ex.getResponseHeaders().add("Content-Type", "application/json");
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, status == 204 || status == 304 ? -1 : bytes.length);
        if (bytes.length > 0) {
            ex.getResponseBody().write(bytes);
        }
    }

    @Autowired
    TeamServer team;

    @Autowired
    ToolRegistry registry;

    @Autowired
    SkillStore skills;

    @Autowired
    SettingsStore store;

    @AfterEach
    void disconnect() {
        team.disconnect();
        setProfile(1);
        PROJECTS.set(List.of());
    }

    @Test
    void wrongTokenIsRejectedAndNothingChanges() {
        assertThatThrownBy(() -> team.connect(url(), "falsch")).isInstanceOf(TeamServerException.class)
                .hasMessageContaining("Desktop-Token");
        assertThat(store.team().configured()).isFalse();
        assertThat(team.active()).isFalse();
    }

    @Test
    void connectReportsCatalogAndAppliesServerSettings() {
        registry.updateConfig("sonar", Map.of("organization", "lokal-org", "timeoutSeconds", "7"));
        assertThat(registry.effectiveSettings("sonar").values()).containsEntry("organization", "lokal-org");

        Me me = team.connect(url(), TOKEN);
        assertThat(me.username()).isEqualTo("anna");
        assertThat(team.status()).isEqualTo(TeamServer.Status.ONLINE);
        assertThat(CATALOG.get().modules()).extracting(ModuleDescriptor::id).contains("git", "sonar", "skills");
        assertThat(CATALOG.get().modules().stream().filter(m -> m.id().equals("git")).findFirst().orElseThrow()
                .tools()).extracting(ModuleDescriptor.ToolDescriptor::name).contains("git_status");

        var sonar = registry.effectiveSettings("sonar");
        assertThat(sonar.enabled()).isTrue();
        assertThat(sonar.values()).containsEntry("organization", "team-org").containsEntry("timeoutSeconds", "7");
        assertThat(sonar.disabledTools()).contains("sonar_hotspots");
        assertThat(registry.settings("sonar").values()).containsEntry("organization", "lokal-org"); // lokal bleibt

        // unverändert: 304, kein neuer Voll-Abruf
        int before = SETTINGS_FULL.get();
        team.sync();
        assertThat(SETTINGS_FULL.get()).isEqualTo(before);

        team.disconnect();
        assertThat(registry.effectiveSettings("sonar").values()).containsEntry("organization", "lokal-org");
    }

    @Test
    void profileSwitchChangesTools() {
        registry.setModuleEnabled("git", true);
        team.connect(url(), TOKEN);
        assertThat(registry.effectiveSettings("git").enabled()).isTrue();

        team.activateProfile(2);
        assertThat(team.settings().orElseThrow().profileName()).isEqualTo("Home");
        assertThat(registry.effectiveSettings("git").enabled()).isFalse();
        assertThat(registry.activeToolNames()).noneMatch(n -> n.startsWith("git_"));
    }

    @Test
    void projectsGetLocalDirectoriesAndReadOnlySharesBlockWrites(@TempDir Path own, @TempDir Path shared)
            throws IOException {
        Files.createDirectories(own.resolve(".git"));
        PROJECTS.set(List.of(
                new ProjectInfo(1, "shop", "anna", "shop", true, "OWNER", null, "shop-key", null),
                new ProjectInfo(2, "lib", "bob", "lib@bob", false, "READ", null, null, null)));
        team.connect(url(), TOKEN);
        team.setProjectPath(1, own.toString());
        team.setProjectPath(2, shared.toString());

        String repos = registry.effectiveSettings("git").values().get("repositories");
        assertThat(repos).contains("shop=" + own.toAbsolutePath().normalize())
                .contains("lib@bob=" + shared.toAbsolutePath().normalize());
        assertThat(ToolScope.LOCAL.canWrite(own)).isTrue();
        assertThatThrownBy(() -> Workspaces.requireWritable(shared.toAbsolutePath().normalize()))
                .hasMessageContaining("nur lesend");

        team.disconnect();
        assertThat(ToolScope.LOCAL.canWrite(shared)).isTrue();
    }

    @Test
    void skillsGoToTheServerWhileConnected() {
        assertThat(skills.remote()).isFalse();
        team.connect(url(), TOKEN);
        assertThat(skills.remote()).isTrue();
        assertThat(skills.list(null, null)).isEqualTo("Skills vom Server");
        assertThatThrownBy(() -> skills.create("x", "d", "c", null, null, 1000))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Skill 'x' existiert bereits.");
        assertThat(SKILL_CALLS).contains("list", "create");
    }

    @Test
    void cacheKeepsServerSettingsWhenServerIsDown() throws IOException {
        team.connect(url(), TOKEN);
        assertThat(home.resolve("team-cache.json")).exists();
        assertThat(Files.readString(home.resolve("team-cache.json"))).doesNotContain("team-org"); // verschlüsselt

        // Server weg: letzter Stand bleibt
        fake.stop(0);
        try {
            assertThatThrownBy(() -> team.sync()).isInstanceOf(TeamServerException.class);
            assertThat(team.status()).isEqualTo(TeamServer.Status.OFFLINE);
            assertThat(registry.effectiveSettings("sonar").values()).containsEntry("organization", "team-org");
        } finally {
            restartFake();
        }
    }
}
