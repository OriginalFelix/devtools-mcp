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
import systems.grebe.devtools.mcp.core.ShellHints;
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

        /** Fester Benutzer statt ~/.gitconfig des Entwicklers. */
        @Bean
        @Primary
        systems.grebe.devtools.mcp.modules.skills.SkillUser testSkillUser(SettingsStore settingsStore) {
            return systems.grebe.devtools.mcp.modules.skills.SkillTestContext.user(settingsStore, "mcp@example.com");
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
    void containerModuleOffersReadToolsByDefault() {
        assertThat(toolNames()).contains("container_runtimes", "container_list", "container_inspect", "container_logs")
                .doesNotContain("container_exec", "container_run", "container_rm", "container_stop");
        registry.updateConfig("container", Map.of("allowExec", "true", "allowLifecycle", "true"));
        assertThat(toolNames()).contains("container_exec", "container_start", "container_stop").doesNotContain("container_run");
        registry.updateConfig("container", Map.of());
        assertThat(toolNames()).doesNotContain("container_exec");
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
    void initializeSendsInstructionsPreferringMcpToolsOverShell() {
        String instructions = client.getServerInstructions();
        assertThat(instructions)
                .startsWith("# DevTools MCP")
                .contains("statt eines Shell-/Terminal-Befehls")
                // Git-Abschnitt mit Zuordnung Shell-Befehl -> Tool
                .contains("## Git – Tools `git_*`", "NICHT `git` im Terminal", "`git_status` (statt `git status`)",
                        "`git_commit` (statt `git commit`)", "`git_list_repositories`")
                // alle Module mit Hinweisen, auch standardmäßig deaktivierte (Instructions stehen ab Start fest)
                .contains("Tools `build_*`", "Tools `container_*`", "Tools `sonar_*`", "Tools `jvm_*`",
                        "Tools `jfr_*`", "Tools `asprof_*`", "Tools `visualvm_*`", "Tools `debug_*`",
                        "## Skills – Tools `skills_*`", "`skills_list`", "`skills_create`", "`skills_patch`")
                .doesNotContain("Java-Grundeinstellungen"); // reines Einstellungsmodul ohne Instructions
        // Reihenfolge wie in der Modulliste: order, dann Anzeigename – Skills zuerst, damit sie vor jeder Aufgabe greifen
        assertThat(instructions.indexOf("Tools `skills_*`")).isLessThan(instructions.indexOf("Tools `git_*`"));
        assertThat(instructions.indexOf("Tools `git_*`")).isLessThan(instructions.indexOf("Tools `container_*`"));
        assertThat(instructions.indexOf("Tools `container_*`")).isLessThan(instructions.indexOf("Tools `jvm_*`"));
        assertThat(instructions.indexOf("Tools `jvm_*`")).isLessThan(instructions.indexOf("Tools `debug_*`"));
    }

    @Test
    void everyToolDescriptionPointsAwayFromShell() throws Exception {
        // Fallback für Clients, die die Server-Instructions nicht übernehmen (z.B. Hermes): jede Tool-Beschreibung
        // trägt die Grundregel ihres Moduls. Alle Module und schreibenden Container-Tools einschalten, damit nichts
        // ungeprüft bleibt.
        Path composeDir = Files.createDirectories(repoDir.resolve("compose-app"));
        Files.writeString(composeDir.resolve("compose.yaml"), "services: {}\n");
        List.of("sonar", "debug", "asprof", "build").forEach(id -> registry.setModuleEnabled(id, true));
        registry.updateConfig("container", Map.of("allowExec", "true", "allowLifecycle", "true", "allowCopy", "true",
                "allowCreate", "true", "allowRemove", "true", "allowCompose", "true",
                "composeProjects", composeDir.toString()));
        registry.updateConfig("skills", Map.of("allowDelete", "true"));
        try {
            Map<String, String> hintByPrefix = Map.of(
                    "git_", ShellHints.GIT, "build_", ShellHints.BUILD, "container_", ShellHints.CONTAINER,
                    "sonar_", ShellHints.SONAR, "jvm_", ShellHints.JVM, "jfr_", ShellHints.JFR,
                    "asprof_", ShellHints.ASPROF, "visualvm_", ShellHints.VISUALVM, "debug_", ShellHints.DEBUG,
                    "skills_", ShellHints.SKILLS);
            List<McpSchema.Tool> tools = client.listTools().tools();
            assertThat(tools).hasSize(96); // alle @Tool-Methoden aller Module
            assertThat(tools).allSatisfy(t -> {
                String hint = hintByPrefix.entrySet().stream().filter(e -> t.name().startsWith(e.getKey()))
                        .map(Map.Entry::getValue).findFirst().orElse(null);
                assertThat(hint).as("Präfix von " + t.name()).isNotBlank();
                assertThat(t.description()).as(t.name()).endsWith(hint);
            });
            assertThat(tools).filteredOn(t -> t.name().equals("git_status")).singleElement()
                    .extracting(McpSchema.Tool::description).asString().contains("Statt `git status` in der Shell verwenden.");
            assertThat(tools).filteredOn(t -> t.name().equals("container_list")).singleElement()
                    .extracting(McpSchema.Tool::description).asString().contains("Statt `podman ps -a` verwenden.");
        } finally {
            List.of("sonar", "debug", "asprof", "build").forEach(id -> registry.setModuleEnabled(id, false));
            registry.updateConfig("container", Map.of());
            registry.updateConfig("skills", Map.of());
        }
    }

    @Test
    void skillsRoundTripOverMcp() {
        // Standard: H2-Datei im (hier temporären) Einstellungsordner
        assertThat(toolNames()).contains("skills_list", "skills_view", "skills_history", "skills_create",
                "skills_patch", "skills_update", "skills_write_file", "skills_remove_file")
                .doesNotContain("skills_delete");

        McpSchema.CallToolResult created = client.callTool(callRequest("skills_create", Map.of(
                "name", "mcp-roundtrip", "description", "Verwenden, wenn der Roundtrip geprüft wird.",
                "content", "## Schritte\n1. anlegen\n", "category", "testing", "tags", List.of("mcp", "h2"))));
        assertThat(created.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(text(created)).contains("angelegt (Revision 1)");

        assertThat(text(client.callTool(callRequest("skills_patch", Map.of("name", "mcp-roundtrip",
                "old_string", "1. anlegen\n", "new_string", "1. anlegen\n2. patchen\n", "note", "Schritt 2")))))
                .contains("Revision 2");
        assertThat(text(client.callTool(callRequest("skills_list", Map.of("query", "roundtrip")))))
                .contains("testing:", "mcp-roundtrip: Verwenden, wenn der Roundtrip geprüft wird.", "[mcp, h2]");
        assertThat(text(client.callTool(callRequest("skills_view", Map.of("name", "mcp-roundtrip")))))
                .contains("revision: 2", "2. patchen");

        // Fachlicher Fehler kommt als isError-Ergebnis mit Hinweis auf den nächsten Schritt beim LLM an
        McpSchema.CallToolResult duplicate = client.callTool(callRequest("skills_create", Map.of(
                "name", "mcp-roundtrip", "description", "x", "content", "y")));
        assertThat(duplicate.isError()).isTrue();
        assertThat(text(duplicate)).contains("existiert bereits", "skills_patch");

        registry.updateConfig("skills", Map.of("allowWrite", "false"));
        try {
            assertThat(toolNames()).contains("skills_list", "skills_view").doesNotContain("skills_create", "skills_patch");
        } finally {
            registry.updateConfig("skills", Map.of());
        }
        assertThat(Files.exists(home.resolve("skills.mv.db"))).isTrue();
    }

    @Test
    void selfImprovementNudgeReviewToolAndPromptOverMcp() {
        registry.updateConfig("skills", Map.of("reviewNudgeInterval", "3"));
        McpSyncClient second = connect(null);
        try {
            // B: Erinnerung nach 3 Aufrufen ohne Skill-Pflege – je Session gezählt
            assertThat(text(client.callTool(callRequest("git_status", Map.of())))).doesNotContain("[DevTools-Skills]");
            assertThat(text(client.callTool(callRequest("git_log", Map.of())))).doesNotContain("[DevTools-Skills]");
            assertThat(text(second.callTool(callRequest("git_log", Map.of())))).doesNotContain("[DevTools-Skills]");
            assertThat(text(client.callTool(callRequest("git_log", Map.of()))))
                    .contains("Initialer Commit", "[DevTools-Skills] 3 Tool-Aufrufe", "skills_review");

            // Skill-Pflege setzt zurück und wird für den Review gemerkt
            client.callTool(callRequest("skills_create", Map.of("name", "review-demo",
                    "description", "Verwenden für den Review-Test.", "content", "## Schritte\n1. a\n")));
            client.callTool(callRequest("skills_view", Map.of("name", "review-demo")));
            assertThat(text(client.callTool(callRequest("git_log", Map.of())))).doesNotContain("[DevTools-Skills]");

            // A: Review-Tool mit Checkliste und Session-Kontext
            McpSchema.CallToolResult reviewed = client.callTool(callRequest("skills_review",
                    Map.of("focus", "Korrektur zur Formatierung")));
            assertThat(reviewed.isError()).isNotEqualTo(Boolean.TRUE);
            assertThat(text(reviewed)).startsWith("# Skill-Review")
                    .contains("Geladenen Skill patchen", "Nicht festhalten", "Korrektur zur Formatierung",
                            "Geladen (skills_view): review-demo", "Bereits geändert: review-demo",
                            "review-demo: Verwenden für den Review-Test.");
            // die zweite Session hat nichts geladen
            assertThat(text(second.callTool(callRequest("skills_review", Map.of()))))
                    .contains("Geladen (skills_view): keine", "Bereits geändert: keine");

            // C: derselbe Review als MCP-Prompt
            assertThat(client.listPrompts().prompts()).extracting(McpSchema.Prompt::name).contains("skills_review");
            McpSchema.GetPromptResult prompt = client.getPrompt(
                    new McpSchema.GetPromptRequest("skills_review", Map.of("focus", "neuer Workaround")));
            String promptText = ((McpSchema.TextContent) prompt.messages().getFirst().content()).text();
            assertThat(promptText).startsWith("# Skill-Review")
                    .contains("neuer Workaround", "Geladen (skills_view): review-demo", "skills_*-Tools ab");

            // ohne Schreibrecht: kein Review-Tool, kein Prompt, keine Erinnerung
            registry.updateConfig("skills", Map.of("allowWrite", "false", "reviewNudgeInterval", "1"));
            assertThat(toolNames()).doesNotContain("skills_review");
            assertThat(client.listPrompts().prompts()).extracting(McpSchema.Prompt::name).doesNotContain("skills_review");
            assertThat(text(client.callTool(callRequest("git_log", Map.of())))).doesNotContain("[DevTools-Skills]");
        } finally {
            second.closeGracefully();
            registry.updateConfig("skills", Map.of());
        }
        assertThat(client.listPrompts().prompts()).extracting(McpSchema.Prompt::name).contains("skills_review");
    }

    private static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().getFirst()).text();
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
