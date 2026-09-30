package systems.grebe.devtools.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
import systems.grebe.devtools.mcp.core.ToolInvocationLog;
import systems.grebe.devtools.mcp.core.ToolRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** MCP-Zugriff mit persönlichen Tokens: eigene Runtime je Benutzer, Widerruf und Sperre wirken sofort. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MultiUserMcpIntegrationTest {

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
    ToolInvocationLog log;

    @TempDir
    Path repoDir;

    private final List<McpSyncClient> clients = new java.util.ArrayList<>();

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
                // Session evtl. schon serverseitig geschlossen
            }
        });
    }

    private UserAccount newUser(String email) {
        return accounts.create("user" + USERS.incrementAndGet(), null, email, Role.USER, "passwort-123");
    }

    private McpSyncClient connect(String token) {
        var transport = HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port)
                .endpoint("/mcp")
                .httpRequestCustomizer((builder, method, uri, body, ctx) -> {
                    if (token != null) {
                        builder.header("Authorization", "Bearer " + token);
                    }
                })
                .build();
        McpSyncClient c = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(30)).build();
        c.initialize();
        clients.add(c);
        return c;
    }

    private static List<String> toolNames(McpSyncClient c) {
        return c.listTools().tools().stream().map(McpSchema.Tool::name).toList();
    }

    @Test
    void userTokenGetsOwnRuntimeAndCallsAreLoggedWithUser() {
        UserAccount alice = newUser("alice@example.com");
        McpSyncClient c = connect(tokens.issue(alice, "Test", Duration.ofDays(1)).jwt());

        assertThat(toolNames(c)).contains("git_status", "git_log");
        assertThat(registry.runtimes()).anyMatch(r -> r.scope().userName().orElse("").equals(alice.username()));

        McpSchema.CallToolResult r = c.callTool(McpSchema.CallToolRequest.builder("git_status")
                .arguments(Map.of()).build());
        assertThat(r.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(log.snapshot().getFirst()).satisfies(inv -> {
            assertThat(inv.toolName()).isEqualTo("git_status");
            assertThat(inv.user()).isEqualTo(alice.username());
            assertThat(inv.userId()).isEqualTo(Long.toString(alice.id()));
        });
    }

    @Test
    void moduleChangesReachUserRuntimes() {
        McpSyncClient c = connect(tokens.issue(newUser(null), "Test", null).jwt());
        assertThat(toolNames(c)).noneMatch(n -> n.startsWith("debug_"));
        registry.setModuleEnabled("debug", true);
        try {
            assertThat(toolNames(c)).contains("debug_attach");
        } finally {
            registry.setModuleEnabled("debug", false);
        }
        assertThat(toolNames(c)).noneMatch(n -> n.startsWith("debug_"));
    }

    @Test
    void unknownOrForgedTokensAreRejected() {
        assertThatThrownBy(() -> connect("kein-jwt")).isInstanceOf(RuntimeException.class);
        // gültiges Format, falsche Signatur
        String jwt = tokens.issue(newUser(null), "Test", null).jwt();
        String forged = jwt.substring(0, jwt.lastIndexOf('.') + 1) + "AAAA";
        assertThatThrownBy(() -> connect(forged)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void expiredTokenIsRejected() {
        String jwt = tokens.issue(newUser(null), "Test", Duration.ofSeconds(-1)).jwt();
        assertThatThrownBy(() -> connect(jwt)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void revokedTokenLosesAccessImmediately() {
        UserAccount u = newUser(null);
        TokenService.IssuedToken t = tokens.issue(u, "Test", null);
        connect(t.jwt());
        tokens.revoke(u.id(), t.token().id());
        assertThatThrownBy(() -> connect(t.jwt())).isInstanceOf(RuntimeException.class);
    }

    @Test
    void disablingUserClosesRuntimeAndRejectsToken() {
        UserAccount u = newUser(null);
        String jwt = tokens.issue(u, "Test", null).jwt();
        McpSyncClient c = connect(jwt);
        assertThat(toolNames(c)).contains("git_status");

        accounts.update(u.id(), null, null, Role.USER, false);

        assertThat(registry.runtimes()).noneMatch(r -> r.scope().userName().orElse("").equals(u.username()));
        assertThatThrownBy(() -> c.listTools()).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> connect(jwt)).isInstanceOf(RuntimeException.class);
    }

    /** Skills gehören der Konto-E-Mail – ohne sie gibt es für den Benutzer keine Skill-Tools (nicht die Git-E-Mail des Servers). */
    @Test
    void skillsBelongToTheAccountEmailNotTheServersGitEmail() {
        UserAccount u = newUser(null);
        String jwt = tokens.issue(u, "Test", null).jwt();
        assertThat(toolNames(connect(jwt))).noneMatch(n -> n.startsWith("skills_"));

        accounts.update(u.id(), null, "skills-owner@example.com", Role.USER, true); // schließt die Runtime
        McpSyncClient c = connect(jwt);
        McpSchema.CallToolResult r = c.callTool(McpSchema.CallToolRequest.builder("skills_create")
                .arguments(Map.of("name", "eigener-skill", "description", "Test", "content", "# Inhalt")).build());
        assertThat(r.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(log.snapshot().getFirst().user()).isEqualTo(u.username());
        assertThat(registry.runtimes()).anyMatch(rt -> rt.scope().email().orElse("").equals("skills-owner@example.com"));
    }

    @Test
    void lastAdminCannotBeDisabledOrDemoted() {
        UserAccount admin = accounts.userByName(AccountService.INITIAL_ADMIN).orElseThrow();
        assertThatThrownBy(() -> accounts.update(admin.id(), null, null, Role.USER, true))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> accounts.delete(admin.id())).isInstanceOf(IllegalStateException.class);
    }
}
