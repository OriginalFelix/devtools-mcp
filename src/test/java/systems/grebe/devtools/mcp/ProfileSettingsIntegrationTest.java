package systems.grebe.devtools.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import systems.grebe.devtools.mcp.account.AccountService;
import systems.grebe.devtools.mcp.account.Role;
import systems.grebe.devtools.mcp.account.TokenService;
import systems.grebe.devtools.mcp.account.UserAccount;
import systems.grebe.devtools.mcp.config.SettingsStore;
import systems.grebe.devtools.mcp.core.ToolModule;
import systems.grebe.devtools.mcp.core.ToolRegistry;
import systems.grebe.devtools.mcp.core.ToolScope;
import systems.grebe.devtools.mcp.profile.Overrides;
import systems.grebe.devtools.mcp.profile.Profile;
import systems.grebe.devtools.mcp.profile.ProfileService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Einstellungs-Ebenen Global → Benutzer → Profil, Profilwechsel und Sperren über MCP. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProfileSettingsIntegrationTest {

    @TempDir
    static Path home;

    private static final AtomicInteger USERS = new AtomicInteger();

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        SettingsStore settingsStore() {
            return new SettingsStore(home);
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    ToolRegistry registry;

    @Autowired
    AccountService accounts;

    @Autowired
    TokenService tokens;

    @Autowired
    ProfileService profiles;

    @Autowired
    @Qualifier("coreJdbc")
    JdbcClient jdbc;

    @TempDir
    Path repoDir;

    private final List<McpSyncClient> clients = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        try (Git git = Git.init().setDirectory(repoDir.toFile()).setInitialBranch("main").call()) {
            Files.writeString(repoDir.resolve("README.md"), "hallo\n");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("Initialer Commit").setAuthor("Test", "t@example.com")
                    .setCommitter("Test", "t@example.com").setSign(false).call();
        }
        registry.updateConfig("git", Map.of("repositories", repoDir.toString()));
        registry.setModuleEnabled("git", true);
    }

    @AfterEach
    void tearDown() {
        clients.forEach(c -> {
            try {
                c.closeGracefully();
            } catch (RuntimeException ignored) {
                // schon zu
            }
        });
        profiles.setLocks("git", Set.of());
    }

    private UserAccount newUser() {
        return accounts.create("puser" + USERS.incrementAndGet(), null, null, Role.USER, "passwort-123");
    }

    private McpSyncClient connect(UserAccount u) {
        String jwt = tokens.issue(u, "Test", null).jwt();
        var transport = HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port).endpoint("/mcp")
                .httpRequestCustomizer((b, m, uri, body, ctx) -> b.header("Authorization", "Bearer " + jwt)).build();
        McpSyncClient c = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(30)).build();
        c.initialize();
        clients.add(c);
        return c;
    }

    private static List<String> toolNames(McpSyncClient c) {
        return c.listTools().tools().stream().map(McpSchema.Tool::name).toList();
    }

    private ToolModule module(String id) {
        return registry.modules().stream().filter(m -> m.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void newUserGetsDefaultProfile() {
        UserAccount u = newUser();
        assertThat(profiles.profiles(u.id())).extracting(Profile::name).containsExactly(ProfileService.DEFAULT_PROFILE);
        assertThat(profiles.activeProfile(u.id()).name()).isEqualTo(ProfileService.DEFAULT_PROFILE);
    }

    @Test
    void switchingProfileChangesToolsOfTheSameSession() {
        UserAccount u = newUser();
        Profile work = profiles.activeProfile(u.id());
        Profile home = profiles.create(u.id(), "Home", null);
        profiles.saveOverrides(u.id(), Overrides.Level.PROFILE, home.id(), module("git"),
                new Overrides(false, Map.of(), Map.of()));

        McpSyncClient c = connect(u);
        assertThat(toolNames(c)).contains("git_status");

        profiles.activate(u.id(), home.id());
        assertThat(toolNames(c)).noneMatch(n -> n.startsWith("git_"));

        profiles.activate(u.id(), work.id());
        assertThat(toolNames(c)).contains("git_status");
        // die lokale Runtime ist davon unberührt
        assertThat(registry.isToolActive("git", "git_status")).isTrue();
    }

    @Test
    void profileOverridesUserOverridesGlobal() {
        UserAccount u = newUser();
        Profile p = profiles.activeProfile(u.id());
        ToolModule sonar = module("sonar");
        profiles.saveOverrides(u.id(), Overrides.Level.USER, u.id(), sonar,
                new Overrides(true, Map.of(), Map.of("organization", "user-org", "timeoutSeconds", "11")));
        profiles.saveOverrides(u.id(), Overrides.Level.PROFILE, p.id(), sonar,
                new Overrides(null, Map.of("sonar_hotspots", false), Map.of("organization", "profile-org")));

        ToolScope scope = new ToolScope("user:" + u.id(), Long.toString(u.id()), u.username(), null,
                Long.toString(p.id()), false);
        var eff = registry.effectiveSettings(scope, "sonar");
        assertThat(eff.enabled()).isTrue();
        assertThat(eff.values()).containsEntry("organization", "profile-org").containsEntry("timeoutSeconds", "11");
        assertThat(eff.disabledTools()).contains("sonar_hotspots");
        assertThat(registry.settings("sonar").enabled()).isFalse(); // global unverändert

        McpSyncClient c = connect(u);
        assertThat(toolNames(c)).contains("sonar_issues").doesNotContain("sonar_hotspots");
    }

    @Test
    void secretOverridesAreStoredEncrypted() {
        UserAccount u = newUser();
        profiles.saveOverrides(u.id(), Overrides.Level.USER, u.id(), module("sonar"),
                new Overrides(null, Map.of(), Map.of("token", "squ_geheim")));
        String raw = jdbc.sql("SELECT setting_value FROM module_override WHERE level_id = ? AND setting_key = 'token'")
                .param(u.id()).query(String.class).single();
        assertThat(raw).startsWith("enc:v1:").doesNotContain("squ_geheim");
        assertThat(profiles.overrides(Overrides.Level.USER, u.id(), "sonar").values())
                .containsEntry("token", "squ_geheim");
    }

    @Test
    void lockedFieldsCannotBeOverriddenAndOldOverridesAreIgnored() {
        UserAccount u = newUser();
        profiles.saveOverrides(u.id(), Overrides.Level.USER, u.id(), module("git"),
                new Overrides(false, Map.of(), Map.of()));
        McpSyncClient c = connect(u);
        assertThat(toolNames(c)).noneMatch(n -> n.startsWith("git_"));

        profiles.setLocks("git", Set.of(Overrides.ENABLED));
        assertThat(toolNames(c)).contains("git_status"); // Sperre gilt sofort, alte Überschreibung wirkt nicht mehr
        assertThatThrownBy(() -> profiles.saveOverrides(u.id(), Overrides.Level.USER, u.id(), module("git"),
                new Overrides(false, Map.of(), Map.of()))).hasMessageContaining("gesperrt");
    }

    @Test
    void foreignProfilesAreRejectedAndLastProfileStays() {
        UserAccount a = newUser();
        UserAccount b = newUser();
        Profile bp = profiles.activeProfile(b.id());
        assertThatThrownBy(() -> profiles.activate(a.id(), bp.id())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> profiles.saveOverrides(a.id(), Overrides.Level.PROFILE, bp.id(), module("git"),
                Overrides.NONE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> profiles.delete(b.id(), bp.id())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void deletingActiveProfileActivatesAnother() {
        UserAccount u = newUser();
        Profile first = profiles.activeProfile(u.id());
        Profile copy = profiles.copy(u.id(), first.id(), "Kopie");
        profiles.activate(u.id(), copy.id());
        profiles.delete(u.id(), copy.id());
        assertThat(profiles.activeProfile(u.id()).id()).isEqualTo(first.id());
    }
}
