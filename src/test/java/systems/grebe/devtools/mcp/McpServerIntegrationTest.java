package systems.grebe.devtools.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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
import systems.grebe.devtools.mcp.config.ServerSettings;
import systems.grebe.devtools.mcp.config.SettingsStore;
import systems.grebe.devtools.mcp.core.ToolInvocationLog;
import systems.grebe.devtools.mcp.core.ToolRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** End-to-End: echter MCP-Client über Streamable HTTP gegen den eingebetteten Server. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpServerIntegrationTest {

    @TempDir
    static Path home;

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
    SettingsStore store;

    @Autowired
    ToolInvocationLog log;

    @TempDir
    Path repoDir;

    McpSyncClient client;

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
        client = connect(null);
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.closeGracefully();
        }
        store.saveServer(ServerSettings.defaults());
    }

    private McpSyncClient connect(String token) {
        var transport = HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port)
                .endpoint("/mcp")
                .customizeClient(b -> { })
                .httpRequestCustomizer((builder, method, uri, body, ctx) -> {
                    if (token != null) {
                        builder.header("Authorization", "Bearer " + token);
                    }
                })
                .build();
        McpSyncClient c = McpClient.sync(transport).requestTimeout(java.time.Duration.ofSeconds(30)).build();
        c.initialize();
        return c;
    }

    private List<String> toolNames() {
        return client.listTools().tools().stream().map(McpSchema.Tool::name).toList();
    }

    private static McpSchema.CallToolRequest callRequest(String name, Map<String, Object> args) {
        return McpSchema.CallToolRequest.builder(name).arguments(args).build();
    }

    @Test
    void listsPrefixedToolsOfEnabledModules() {
        assertThat(toolNames()).contains("git_status", "git_log", "git_diff", "git_commit")
                .noneMatch(n -> n.startsWith("sonar_")); // Sonar standardmäßig aus
    }

    @Test
    void performanceModulesAreRegistered() {
        // jvm, jfr, visualvm standardmäßig an; asprof und debug aus; 'java' ist reines Einstellungsmodul
        assertThat(toolNames()).contains("jvm_processes", "jvm_threads", "jvm_heap_dump", "jfr_record", "jfr_analyze",
                        "jfr_flamegraph", "visualvm_heap_analyze", "visualvm_sample_cpu")
                .noneMatch(n -> n.startsWith("asprof_") || n.startsWith("debug_") || n.startsWith("java_"));

        registry.setModuleEnabled("debug", true);
        registry.setModuleEnabled("asprof", true);
        assertThat(toolNames()).contains("debug_attach", "debug_set_breakpoint", "debug_variables", "asprof_profile");
        registry.setModuleEnabled("debug", false);
        registry.setModuleEnabled("asprof", false);
        assertThat(toolNames()).noneMatch(n -> n.startsWith("debug_"));

        McpSchema.CallToolResult result = client.callTool(callRequest("jvm_processes", Map.of()));
        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(((McpSchema.TextContent) result.content().getFirst()).text()).contains("Lokale JVMs");
    }

    @Test
    void callsGitToolAndLogsInvocation() {
        McpSchema.CallToolResult result = client.callTool(callRequest("git_log", Map.of()));
        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        String text = ((McpSchema.TextContent) result.content().getFirst()).text();
        assertThat(text).contains("Initialer Commit").doesNotStartWith("\"");
        assertThat(log.snapshot().getFirst().toolName()).isEqualTo("git_log");
        assertThat(log.snapshot().getFirst().success()).isTrue();
    }

    @Test
    void togglingModuleAndToolUpdatesToolListAtRuntime() {
        registry.setModuleEnabled("sonar", true);
        assertThat(toolNames()).contains("sonar_issues", "sonar_quality_gate");

        registry.setToolEnabled("git", "git_commit", false);
        assertThat(toolNames()).doesNotContain("git_commit").contains("git_status");

        registry.updateConfig("git", Map.of("repositories", repoDir.toString(), "allowWrite", "false"));
        assertThat(toolNames()).doesNotContain("git_stage", "git_create_branch");

        registry.setModuleEnabled("sonar", false);
        registry.setToolEnabled("git", "git_commit", true);
        registry.updateConfig("git", Map.of("repositories", repoDir.toString()));
        assertThat(toolNames()).noneMatch(n -> n.startsWith("sonar_")).contains("git_commit");
    }

    @Test
    void toolErrorsAreReportedAsErrorResult() {
        McpSchema.CallToolResult result = client.callTool(
                callRequest("git_status", Map.of("repository", "gibt-es-nicht")));
        assertThat(result.isError()).isTrue();
        assertThat(log.snapshot().getFirst().success()).isFalse();
        assertThat(log.snapshot().getFirst().result()).contains("nicht freigegeben");
    }

    @Test
    void bearerTokenIsEnforcedWhenConfigured() {
        store.saveServer(new ServerSettings(ServerSettings.DEFAULT_PORT, "geheim", true, false));
        assertThatThrownBy(() -> connect("falsch")).isInstanceOf(RuntimeException.class);
        McpSyncClient ok = connect("geheim");
        assertThat(ok.listTools().tools()).isNotEmpty();
        ok.closeGracefully();
    }
}
