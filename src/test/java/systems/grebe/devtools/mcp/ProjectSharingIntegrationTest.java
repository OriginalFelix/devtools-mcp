package systems.grebe.devtools.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import systems.grebe.devtools.mcp.account.AccountService;
import systems.grebe.devtools.mcp.account.Role;
import systems.grebe.devtools.mcp.account.TokenService;
import systems.grebe.devtools.mcp.account.UserAccount;
import systems.grebe.devtools.mcp.config.SettingsStore;
import systems.grebe.devtools.mcp.core.ToolRegistry;
import systems.grebe.devtools.mcp.project.Project;
import systems.grebe.devtools.mcp.project.ProjectService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Projekte je Benutzer, Freigaben lesen/schreiben und die Wurzel-Policy über MCP. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProjectSharingIntegrationTest {

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
    ProjectService projects;

    @TempDir
    Path workspace;

    Path repoDir;
    Path globalRepo;

    private final List<McpSyncClient> clients = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        repoDir = gitRepo(workspace.resolve("allowed/app"));
        globalRepo = gitRepo(workspace.resolve("global-only"));
        // global freigegeben – gilt nur lokal, nicht für angemeldete Benutzer
        registry.updateConfig("git", Map.of("repositories", globalRepo.toString()));
        registry.setModuleEnabled("git", true);
        registry.updateConfig("projects", Map.of("allowedRoots", workspace.resolve("allowed").toString()));
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
        registry.updateConfig("projects", Map.of());
    }

    private static Path gitRepo(Path dir) throws Exception {
        Files.createDirectories(dir);
        try (Git git = Git.init().setDirectory(dir.toFile()).setInitialBranch("main").call()) {
            Files.writeString(dir.resolve("README.md"), "hallo\n");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("Initialer Commit").setAuthor("Test", "t@example.com")
                    .setCommitter("Test", "t@example.com").setSign(false).call();
        }
        return dir;
    }

    private UserAccount newUser() {
        return accounts.create("pr" + USERS.incrementAndGet(), null, null, Role.USER, "passwort-123");
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

    private static McpSchema.CallToolResult call(McpSyncClient c, String tool, Map<String, Object> args) {
        return c.callTool(McpSchema.CallToolRequest.builder(tool).arguments(args).build());
    }

    private static String text(McpSchema.CallToolResult r) {
        return ((McpSchema.TextContent) r.content().getFirst()).text();
    }

    @Test
    void usersSeeOnlyTheirProjectsNotGlobalDirectories() {
        UserAccount u = newUser();
        McpSyncClient c = connect(u);
        assertThat(text(call(c, "git_list_repositories", Map.of()))).doesNotContain(globalRepo.toString());
        assertThat(text(call(c, "projects_list", Map.of()))).contains("Keine Projekte");

        projects.create(u.id(), "app", repoDir.toString(), "Die App", "acme:app", "APP");
        assertThat(text(call(c, "git_list_repositories", Map.of()))).contains("app").contains(repoDir.toString());
        assertThat(text(call(c, "projects_list", Map.of())))
                .contains("app  [Eigentümer]").contains("Git").contains("Sonar: acme:app").contains("Tickets: APP");
        assertThat(call(c, "git_status", Map.of("repository", "app")).isError()).isNotEqualTo(Boolean.TRUE);
        // lokal (Einzelplatz) bleibt es beim globalen Repository
        assertThat(registry.config("git").getList("repositories")).containsExactly(globalRepo.toString());
    }

    @Test
    void rootPolicyForNonAdmins() {
        UserAccount u = newUser();
        assertThatThrownBy(() -> projects.create(u.id(), "fremd", globalRepo.toString(), null, null, null))
                .hasMessageContaining("außerhalb");
        registry.updateConfig("projects", Map.of());
        assertThatThrownBy(() -> projects.create(u.id(), "app", repoDir.toString(), null, null, null))
                .hasMessageContaining("nur Administratoren");
        UserAccount admin = accounts.userByName(AccountService.INITIAL_ADMIN).orElseThrow();
        Project p = projects.create(admin.id(), "global-pr" + USERS.incrementAndGet(), globalRepo.toString(), null,
                null, null);
        projects.delete(admin.id(), p.id());
    }

    @Test
    void readShareAllowsReadingButNotWritingWriteShareAllowsBoth() {
        UserAccount owner = newUser();
        UserAccount other = newUser();
        Project p = projects.create(owner.id(), "shared", repoDir.toString(), null, null, null);
        McpSyncClient c = connect(other);
        String name = "shared@" + owner.username();

        projects.share(owner.id(), p.id(), other.username(), Project.Access.READ);
        assertThat(text(call(c, "projects_list", Map.of()))).contains(name + "  [nur lesen]");
        assertThat(call(c, "git_status", Map.of("repository", name)).isError()).isNotEqualTo(Boolean.TRUE);
        McpSchema.CallToolResult denied = call(c, "git_create_branch", Map.of("repository", name, "name", "x1"));
        assertThat(denied.isError()).isTrue();
        assertThat(text(denied)).contains("nur lesend");

        projects.share(owner.id(), p.id(), other.username(), Project.Access.WRITE);
        assertThat(call(c, "git_create_branch", Map.of("repository", name, "name", "x2")).isError())
                .isNotEqualTo(Boolean.TRUE);

        projects.unshare(owner.id(), p.id(), other.id());
        assertThat(text(call(c, "git_list_repositories", Map.of()))).doesNotContain(name);
        assertThat(call(c, "git_status", Map.of("repository", name)).isError()).isTrue();
    }

    @Test
    void onlyOwnerOrAdminManagesProjects() {
        UserAccount owner = newUser();
        UserAccount other = newUser();
        Project p = projects.create(owner.id(), "mine", repoDir.toString(), null, null, null);
        assertThatThrownBy(() -> projects.share(other.id(), p.id(), other.username(), Project.Access.WRITE))
                .hasMessageContaining("Eigentümer");
        assertThatThrownBy(() -> projects.delete(other.id(), p.id())).hasMessageContaining("Eigentümer");
        assertThatThrownBy(() -> projects.share(owner.id(), p.id(), owner.username(), Project.Access.READ))
                .hasMessageContaining("Eigentümer");
        UserAccount admin = accounts.userByName(AccountService.INITIAL_ADMIN).orElseThrow();
        projects.delete(admin.id(), p.id());
        assertThat(projects.visible(owner.id())).isEmpty();
    }
}
