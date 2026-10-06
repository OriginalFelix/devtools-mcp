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
import systems.grebe.devtools.mcp.core.UserConfirmation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** End-to-End: echter MCP-Client über Streamable HTTP gegen den eingebetteten Server. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"devtools.local-user.email=mcp@example.com", "devtools.login.username=tester",
                "devtools.login.password=tester-passwort"})
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

    @Autowired
    UserConfirmation confirmation;

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
                        "Tools `jfr_*`", "Tools `asprof_*`", "Tools `visualvm_*`", "Tools `debug_*`", "Tools `graph_*`",
                        "`graph_report`", "`graph_neighbors`", "Tools `ticket_*`", "`ticket_get`", "`ticket_board`",
                        "## Skills – Tools `skills_*`", "`skills_list`", "Tools `maven_*`", "`maven_breaking_changes`", "`skills_create`", "`skills_patch`",
                        "## Memories – Tools `memories_*`", "`memories_search`", "`memories_save`",
                        "## Berechtigungen – Tools `permissions_*`", "`permissions_request`")
                .doesNotContain("Java-Grundeinstellungen"); // reines Einstellungsmodul ohne Instructions
        // Reihenfolge wie in der Modulliste: order, dann Anzeigename – Skills zuerst, damit sie vor jeder Aufgabe greifen
        assertThat(instructions.indexOf("Tools `skills_*`")).isLessThan(instructions.indexOf("Tools `memories_*`"));
        assertThat(instructions.indexOf("Tools `memories_*`")).isLessThan(instructions.indexOf("Tools `git_*`"));
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
        List.of("sonar", "debug", "asprof", "build", "graph", "ticket", "pr").forEach(id -> registry.setModuleEnabled(id, true));
        registry.updateConfig("git", Map.of("repositories", repoDir.toString(), "allowSync", "true",
                "allowIntegrate", "true", "allowDiscard", "true"));
        registry.updateConfig("pr", Map.of("allowCreate", "true", "allowComment", "true", "allowResolve", "true",
                "allowMerge", "true", "allowPush", "true"));
        registry.updateConfig("container", Map.of("allowExec", "true", "allowLifecycle", "true", "allowCopy", "true",
                "allowCreate", "true", "allowRemove", "true", "allowCompose", "true",
                "composeProjects", composeDir.toString()));
        registry.updateConfig("skills", Map.of("allowDelete", "true"));
        registry.updateConfig("memories", Map.of("allowDelete", "true"));
        registry.updateConfig("scripts", Map.of("allowWrite", "true", "allowDelete", "true"));
        registry.updateConfig("ticket", Map.of("allowComment", "true", "allowTransition", "true", "allowAssign", "true",
                "allowEdit", "true", "allowCreate", "true", "allowDelete", "true"));
        try {
            Map<String, String> hintByPrefix = Map.ofEntries(
                    Map.entry("git_", ShellHints.GIT), Map.entry("build_", ShellHints.BUILD),
                    Map.entry("container_", ShellHints.CONTAINER), Map.entry("sonar_", ShellHints.SONAR),
                    Map.entry("jvm_", ShellHints.JVM), Map.entry("jfr_", ShellHints.JFR),
                    Map.entry("asprof_", ShellHints.ASPROF), Map.entry("visualvm_", ShellHints.VISUALVM),
                    Map.entry("debug_", ShellHints.DEBUG), Map.entry("skills_", ShellHints.SKILLS),
                    Map.entry("memories_", ShellHints.MEMORIES),
                    Map.entry("graph_", ShellHints.GRAPH), Map.entry("ticket_", ShellHints.TICKET),
                    Map.entry("projects_", ShellHints.PROJECTS), Map.entry("maven_", ShellHints.MAVEN),
                    Map.entry("decompile_", ShellHints.DECOMPILE), Map.entry("pr_", ShellHints.PR),
                    Map.entry("scripts_", ShellHints.SCRIPTS), Map.entry("permissions_", ShellHints.PERMISSIONS));
            List<McpSchema.Tool> tools = client.listTools().tools();
            assertThat(tools).hasSize(175); // alle @Tool-Methoden aller Module
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
            List.of("sonar", "debug", "asprof", "build", "graph", "ticket", "pr").forEach(id -> registry.setModuleEnabled(id, false));
            registry.updateConfig("git", Map.of("repositories", repoDir.toString()));
            registry.updateConfig("pr", Map.of());
            registry.updateConfig("container", Map.of());
            registry.updateConfig("skills", Map.of());
            registry.updateConfig("memories", Map.of());
            registry.updateConfig("scripts", Map.of());
            registry.updateConfig("ticket", Map.of());
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
                .contains("direkt geladen", "# mcp-roundtrip · Revision 2", "2. patchen");
        assertThat(text(client.callTool(callRequest("skills_view", Map.of("name", "mcp-roundtrip")))))
                .contains("Revision 2", "2. patchen");

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
    void memoriesRoundTripOverMcp() {
        assertThat(toolNames()).contains("memories_search", "memories_view", "memories_save", "memories_update")
                .doesNotContain("memories_delete");

        McpSchema.CallToolResult saved = client.callTool(callRequest("memories_save", Map.of(
                "title", "Ticket MCP-7 reviewt: Akzeptanzkriterien fehlen",
                "content", "Zurück an den PO.", "project", "devtools", "skill", "ticket-review",
                "reference", "MCP-7", "tags", List.of("review"))));
        assertThat(saved.isError()).isNotEqualTo(Boolean.TRUE);
        String id = text(saved).replaceAll("(?s)^Memory #(\\d+) gespeichert\\..*$", "$1");
        assertThat(id).matches("\\d+");

        assertThat(text(client.callTool(callRequest("memories_update", Map.of("id", Long.parseLong(id),
                "append", "PO hat nachgebessert, freigegeben.")))))
                .contains("Memory #" + id + " aktualisiert (Nachtrag)");
        assertThat(text(client.callTool(callRequest("memories_search", Map.of("query", "mcp-7")))))
                .contains("1 Memory für 'mcp-7'", "#" + id, "Skill ticket-review");
        assertThat(text(client.callTool(callRequest("memories_search", Map.of("skill", "ticket-review")))))
                .contains("#" + id);
        assertThat(text(client.callTool(callRequest("memories_view", Map.of("id", Long.parseLong(id))))))
                .contains("Bezug MCP-7", "Zurück an den PO.", "**Nachtrag ", "freigegeben.");

        McpSchema.CallToolResult missing = client.callTool(callRequest("memories_view", Map.of("id", 999_999)));
        assertThat(missing.isError()).isTrue();
        assertThat(text(missing)).contains("#999999 gibt es nicht", "memories_search");

        registry.updateConfig("memories", Map.of("allowWrite", "false", "allowDelete", "true"));
        try {
            assertThat(toolNames()).contains("memories_search", "memories_delete")
                    .doesNotContain("memories_save", "memories_update");
            assertThat(text(client.callTool(callRequest("memories_delete", Map.of("id", Long.parseLong(id))))))
                    .contains("gelöscht");
        } finally {
            registry.updateConfig("memories", Map.of());
        }
    }

    @Test
    void serverPointsToRegisteredSkillsAndEarlierActions() {
        assertThat(text(client.callTool(callRequest("skills_create", Map.of("name", "hint-demo",
                "description", "Verwenden beim Blick auf die Remotes.", "content", "1. git_remotes",
                "triggers", List.of("git_remotes")))))).contains("registriert für git_remotes");
        String saved = text(client.callTool(callRequest("memories_save", Map.of("title", "Hotfix HINT-42 geprüft",
                "content", "Alles gut.", "skill", "hint-demo", "reference", "HINT-42"))));
        String id = saved.replaceAll("(?s)^Memory #(\\d+) gespeichert.*$", "$1");
        McpSyncClient fresh = connect(null);
        try {
            // Aufruf eines registrierten Tools nennt den Skill – je Session einmal
            assertThat(text(fresh.callTool(callRequest("git_remotes", Map.of()))))
                    .contains("[DevTools] Registrierter Skill für git_remotes: hint-demo – Verwenden beim Blick auf "
                            + "die Remotes. (per skills_view ladbar)");
            assertThat(text(fresh.callTool(callRequest("git_remotes", Map.of()))))
                    .doesNotContain("Registrierter Skill");
            // bekannter Bezug in den Argumenten nennt die frühere Aktion
            assertThat(text(fresh.callTool(callRequest("git_grep", Map.of("pattern", "HINT-42")))))
                    .contains("[DevTools] Frühere Aktionen zu hint-42: #" + id, "Hotfix HINT-42 geprüft");
            // skills_view nennt frühere Durchläufe des Skills (in der ersten Session noch nicht genannt)
            assertThat(text(client.callTool(callRequest("skills_view", Map.of("name", "hint-demo")))))
                    .contains("# hint-demo · Revision 1", "Registriert für: git_remotes",
                            "[DevTools] Frühere Durchläufe von hint-demo: #" + id);
        } finally {
            fresh.closeGracefully();
            registry.updateConfig("skills", Map.of("allowDelete", "true"));
            registry.updateConfig("memories", Map.of("allowDelete", "true"));
            client.callTool(callRequest("skills_delete", Map.of("name", "hint-demo")));
            client.callTool(callRequest("memories_delete", Map.of("id", Long.parseLong(id))));
            registry.updateConfig("skills", Map.of());
            registry.updateConfig("memories", Map.of());
        }
    }

    @Test
    void selfImprovementNudgeReviewToolAndPromptOverMcp() {
        registry.updateConfig("skills", Map.of("reviewNudgeInterval", "3"));
        McpSyncClient second = connect(null);
        try {
            // Erster Aufruf je Session: Hinweis auf die Bibliothek – für Clients ohne Server-Instructions
            // (Anzahl hängt von anderen Tests auf derselben Datenbank ab; „noch leer“ prüft der Unit-Test)
            assertThat(text(client.callTool(callRequest("git_status", Map.of()))))
                    .contains("[DevTools-Skills] Skill-Bibliothek des Nutzers auf diesem Server: ",
                            "skills_create").doesNotContain("Tool-Aufrufe");
            // B: Erinnerung nach 3 Aufrufen ohne Skill-Pflege – je Session gezählt
            assertThat(text(client.callTool(callRequest("git_log", Map.of())))).doesNotContain("[DevTools-Skills]");
            assertThat(text(second.callTool(callRequest("git_log", Map.of())))).contains("Skill-Bibliothek")
                    .doesNotContain("Tool-Aufrufe");
            assertThat(text(client.callTool(callRequest("git_log", Map.of()))))
                    .contains("Initialer Commit", "[DevTools-Skills] 3 Tool-Aufrufe", "skills_review")
                    .doesNotContain("Skill-Bibliothek");

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
                            "review-demo – Verwenden für den Review-Test.");
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
            McpSyncClient third = connect(null);
            try {
                assertThat(text(third.callTool(callRequest("git_log", Map.of())))).doesNotContain("[DevTools-Skills]");
            } finally {
                third.closeGracefully();
            }
            assertThat(toolNames()).doesNotContain("skills_review");
            assertThat(client.listPrompts().prompts()).extracting(McpSchema.Prompt::name).doesNotContain("skills_review");
            assertThat(text(client.callTool(callRequest("git_log", Map.of())))).doesNotContain("[DevTools-Skills]");
        } finally {
            second.closeGracefully();
            registry.updateConfig("skills", Map.of());
        }
        assertThat(client.listPrompts().prompts()).extracting(McpSchema.Prompt::name).contains("skills_review");
    }

    @Test
    void skillAndMemoryToolsStateTheirTriggerInTheFirstSentence() {
        // Hermes zeigt ausgelagerte MCP-Tools nur mit dem ersten Satz (max. 60 Zeichen) – der muss den Auslöser nennen.
        Map<String, String> expected = Map.of(
                "skills_list", "VOR einer Aufgabe: gespeicherte Skills des Nutzers suchen.",
                "skills_view", "Lädt einen gespeicherten Skill (erprobter Ablauf).",
                "skills_create", "Speichert neu Gelerntes dauerhaft als Skill (Ablauf, Fix).",
                "skills_patch", "Ergänzt einen Skill um Korrekturen und Workarounds.",
                "skills_review", "Nach mehrstufiger Aufgabe: prüfen, was als Skill bleibt.",
                "memories_search", "VOR einer Aufgabe: frühere Aktionen des Nutzers suchen.",
                "memories_save", "Hält eine abgeschlossene Aktion als Memory fest.",
                "memories_update", "Ergänzt eine Memory um Nachtrag oder Korrektur.");
        List<McpSchema.Tool> tools = client.listTools().tools();
        expected.forEach((name, sentence) -> {
            assertThat(sentence).hasSizeLessThanOrEqualTo(60);
            assertThat(tools).filteredOn(t -> t.name().equals(name)).singleElement()
                    .extracting(McpSchema.Tool::description).asString().startsWith(sentence + " ");
        });
    }

    private static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().getFirst()).text();
    }

    @Test
    void ticketModuleOffGivesNoToolsAndReportsMissingSystemAsToolError() {
        assertThat(toolNames()).noneMatch(n -> n.startsWith("ticket_")); // Standard: aus
        registry.setModuleEnabled("ticket", true);
        try {
            assertThat(toolNames()).contains("ticket_providers", "ticket_boards", "ticket_board", "ticket_search",
                    "ticket_get", "ticket_status", "ticket_links", "ticket_transitions")
                    .doesNotContain("ticket_comment", "ticket_transition", "ticket_assign", "ticket_update", "ticket_create",
                            "ticket_delete", "ticket_delete_comment");
            // Schalter wirken live auf tools/list
            registry.updateConfig("ticket", Map.of("allowComment", "true"));
            assertThat(toolNames()).contains("ticket_comment").doesNotContain("ticket_transition", "ticket_create");
            // kein System aktiv: fachlicher Fehler mit nächstem Schritt, als isError beim LLM
            McpSchema.CallToolResult result = client.callTool(callRequest("ticket_get", Map.of("key", "ABC-1")));
            assertThat(result.isError()).isTrue();
            assertThat(text(result)).contains("Kein Ticket-System aktiviert", "Module → Tickets");
        } finally {
            registry.setModuleEnabled("ticket", false);
            registry.updateConfig("ticket", Map.of());
        }
    }

    @Test
    void graphBuildAndQueryOverMcp() throws Exception {
        Path src = Files.createDirectories(repoDir.resolve("src/main/java/demo"));
        Files.writeString(src.resolve("Greeter.java"), """
                package demo;
                public class Greeter {
                    String greet(String name) { return helper(name); }
                    private String helper(String n) { return n; }
                }
                """);
        registry.updateConfig("graph", Map.of("projects", repoDir.toString(), "storage", "file"));
        registry.setModuleEnabled("graph", true);
        try {
            assertThat(toolNames()).contains("graph_build", "graph_report", "graph_find", "graph_explain",
                    "graph_neighbors", "graph_path", "graph_query", "graph_branches", "graph_cypher");
            McpSchema.CallToolResult built = client.callTool(callRequest("graph_build", Map.of()));
            assertThat(built.isError()).isNotEqualTo(Boolean.TRUE);
            // Graph je Branch: das Test-Repository steht auf "main"
            assertThat(text(built)).startsWith("Graph gebaut").contains("Branch main", "devtools-fileinfo@main.graph");
            assertThat(Files.exists(repoDir.resolve("devtools-fileinfo@main.graph"))).isTrue();
            assertThat(text(client.callTool(callRequest("graph_branches", Map.of())))).contains("ausgecheckt: main",
                    "- main * @ ");

            assertThat(text(client.callTool(callRequest("graph_neighbors",
                    Map.of("node", "Greeter#helper", "direction", "in", "relations", List.of("calls"))))))
                    .contains("<-- calls demo.Greeter#greet(String)");

            McpSchema.CallToolResult unknown = client.callTool(callRequest("graph_explain", Map.of("node", "Nix")));
            assertThat(unknown.isError()).isTrue();
            assertThat(text(unknown)).contains("Kein Knoten", "graph_find");
        } finally {
            registry.setModuleEnabled("graph", false);
            registry.updateConfig("graph", Map.of());
        }
    }

    @Test
    void graphIndexActionRunsFromRegistryWhileModuleIsDisabled() throws Exception {
        Path src = Files.createDirectories(repoDir.resolve("src/main/java/demo"));
        Files.writeString(src.resolve("A.java"), "package demo;\nclass A { void a() { } }\n");
        registry.updateConfig("graph", Map.of("projects", repoDir.toString(), "storage", "file"));
        int[] changes = {0};
        registry.addChangeListener(() -> changes[0]++);
        try {
            assertThat(registry.settings("graph").enabled()).isFalse();
            assertThat(registry.actions("graph")).extracting(a -> a.id()).containsExactly("index");
            String project = repoDir.getFileName().toString();
            assertThat(registry.actionTargets("graph", "index")).containsExactly(project);
            int before = changes[0];
            var result = registry.runAction("graph", "index", project, java.util.Set.of(), null);
            assertThat(result.success()).as(result.message()).isTrue();
            assertThat(Files.exists(repoDir.resolve("devtools-fileinfo@main.graph"))).isTrue();
            assertThat(changes[0]).isGreaterThan(before);
            assertThat(registry.describeActionTarget("graph", "index", project)).contains("Graph vom", "1 Dateien");
            assertThat(registry.runAction("graph", "index", "gibt-es-nicht", java.util.Set.of(), null).message())
                    .contains("nicht freigegeben");
            assertThat(registry.actions("git")).isEmpty();
        } finally {
            registry.updateConfig("graph", Map.of());
        }
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
    void globalSharesAndLiftedRestrictionApplyToModuleTools(@TempDir Path other) throws Exception {
        Git.init().setDirectory(other.toFile()).setInitialBranch("main").call().close();
        registry.updateConfig("git", Map.of()); // keine eigenen Repositories im Modul
        try {
            registry.updateConfig("access", Map.of("directories", repoDir.toString()));
            String list = ((McpSchema.TextContent) client.callTool(callRequest("git_list_repositories", Map.of()))
                    .content().getFirst()).text();
            assertThat(list).contains(repoDir.toString());
            assertThat(registry.settings("git").values().getOrDefault("repositories", "")).isEmpty(); // Formular unverändert

            McpSchema.CallToolResult denied = client.callTool(
                    callRequest("git_status", Map.of("repository", other.toString())));
            assertThat(denied.isError()).isTrue();

            registry.updateConfig("access", Map.of("directories", repoDir.toString(), "unrestricted", "true"));
            McpSchema.CallToolResult allowed = client.callTool(
                    callRequest("git_status", Map.of("repository", other.toString())));
            assertThat(allowed.isError()).isNotEqualTo(Boolean.TRUE);
            assertThat(((McpSchema.TextContent) allowed.content().getFirst()).text()).contains("main");
        } finally {
            registry.updateConfig("access", Map.of());
            registry.updateConfig("git", Map.of("repositories", repoDir.toString()));
        }
        McpSchema.CallToolResult again = client.callTool(
                callRequest("git_status", Map.of("repository", other.toString())));
        assertThat(again.isError()).isTrue();
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

    @Test
    void classifierAsksTheCallingClientsLlmViaSamplingAndFallsBackToPrompt() {
        registry.setModuleEnabled("classify", true);
        try {
            // Client mit Sampling: der Server fragt dessen LLM mitten im Tool-Aufruf an (sampling/createMessage)
            List<McpSchema.CreateMessageRequest> asked = new java.util.concurrent.CopyOnWriteArrayList<>();
            var transport = HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port).endpoint("/mcp").build();
            McpSyncClient sampling = McpClient.sync(transport).requestTimeout(java.time.Duration.ofSeconds(30))
                    .clientInfo(new McpSchema.Implementation("test-client", "1.0"))
                    .capabilities(McpSchema.ClientCapabilities.builder().sampling().build())
                    .sampling(req -> {
                        asked.add(req);
                        return McpSchema.CreateMessageResult.builder().role(McpSchema.Role.ASSISTANT).model("claude-opus-5-5")
                                .content(new McpSchema.TextContent("{\"complexity\":\"normal\",\"confidence\":\"high\","
                                        + "\"summary\":\"Ein Modul.\",\"factors\":[],\"risks\":[],\"openQuestions\":[]}"))
                                .build();
                    })
                    .build();
            try {
                sampling.initialize();
                McpSchema.CallToolResult r = sampling.callTool(callRequest("classify_task",
                        Map.of("task", "CSV-Export für Berichte ergänzen", "title", "CSV-Export")));
                String text = ((McpSchema.TextContent) r.content().getFirst()).text();
                assertThat(r.isError()).isNotEqualTo(Boolean.TRUE);
                assertThat(text).contains("Komplexität: normal", "Empfohlenes Modell: claude-sonnet-4-5",
                        "über Client test-client");
                assertThat(asked).hasSize(1);
                assertThat(asked.getFirst().modelPreferences().hints().getFirst().name()).isEqualTo("claude-opus-5-5");
            } finally {
                sampling.closeGracefully();
            }

            // Client ohne Sampling (wie Claude Code): der Classifier-Prompt geht an das aufrufende LLM zurück
            McpSchema.CallToolResult r = client.callTool(callRequest("classify_task", Map.of("task", "CSV-Export")));
            assertThat(((McpSchema.TextContent) r.content().getFirst()).text())
                    .contains("bietet kein Sampling an", "<system-prompt>", "<anfrage>");
        } finally {
            registry.setModuleEnabled("classify", false);
        }
    }
    @Test
    void permissionsShowWhatIsMissingAndOnlyTheUserGrantsIt() throws Exception {
        McpSchema.CallToolRequest pushRequest = callRequest("permissions_request",
                Map.of("tool", "git_push", "reason", "Feature-Branch pushen"));
        // nur lesend: zeigt, was fehlt, ohne etwas zu ändern
        assertThat(toolNames()).contains("permissions_overview", "permissions_check", "permissions_request")
                .doesNotContain("git_push");
        assertThat(text(client.callTool(callRequest("permissions_overview", Map.of()))))
                .contains("git – Git [an]", "Remote-Abgleich erlauben (fetch, pull, push) = aus");
        assertThat(text(client.callTool(callRequest("permissions_overview", Map.of("module", "git")))))
                .contains("(allowSync): aus – würde freischalten: git_fetch, git_pull, git_push",
                        "(allowWrite): an – bietet: ", "Repositories (repositories): ");
        assertThat(text(client.callTool(callRequest("permissions_check",
                Map.of("tool", "git_push", "path", repoDir.toString())))))
                .contains("git_push: nicht verfügbar", "den Schalter „Remote-Abgleich erlauben (fetch, pull, push)“ "
                        + "(allowSync) einschalten", "Freigegeben in:", "Git – Repositories");

        // Client ohne Rückfrage und keine Oberfläche: nichts wird geändert
        assertThat(text(client.callTool(pushRequest))).contains("Keine Rückfrage möglich");
        assertThat(toolNames()).doesNotContain("git_push");

        // Client mit Elicitation: der Nutzer entscheidet
        List<McpSchema.ElicitFormRequest> asked = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicBoolean grant = new java.util.concurrent.atomic.AtomicBoolean();
        var transport = HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port).endpoint("/mcp").build();
        McpSyncClient eliciting = McpClient.sync(transport).requestTimeout(java.time.Duration.ofSeconds(30))
                .clientInfo(new McpSchema.Implementation("test-client", "1.0"))
                .capabilities(McpSchema.ClientCapabilities.builder().elicitation().build())
                .elicitation(req -> {
                    asked.add(req);
                    return new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.ACCEPT, Map.of("grant", grant.get()));
                })
                .build();
        Path shared = Files.createTempDirectory("freigabe");
        try {
            eliciting.initialize();
            assertThat(text(eliciting.callTool(pushRequest))).contains("Vom Nutzer abgelehnt (test-client)");
            assertThat(asked.getFirst().message()).contains("„Remote-Abgleich erlauben (fetch, pull, push)“",
                    "Begründung: Feature-Branch pushen");
            assertThat(toolNames()).doesNotContain("git_push");

            grant.set(true);
            assertThat(text(eliciting.callTool(pushRequest)))
                    .contains("Vom Nutzer erteilt (test-client)", "Neu verfügbar: git_fetch, git_pull, git_push");
            assertThat(toolNames()).contains("git_push");
            assertThat(text(eliciting.callTool(pushRequest))).contains("Bereits erlaubt");

            // Rückfrage über die App (Einstellung „Rückfrage über“ = app): Verzeichnis unter „Freigaben“
            registry.updateValues("permissions", v -> {
                v.put("promptVia", "app");
                return v;
            });
            List<String> dialogs = new java.util.concurrent.CopyOnWriteArrayList<>();
            confirmation.setDesktopHandler((title, message) -> {
                dialogs.add(message);
                return java.util.concurrent.CompletableFuture.completedFuture(true);
            });
            assertThat(text(eliciting.callTool(callRequest("permissions_request",
                    Map.of("path", shared.toString(), "reason", "Projekt bauen")))))
                    .contains("Vom Nutzer erteilt (DevTools-App)", "unter „Freigaben“ für alle Tools freigeben");
            assertThat(dialogs).singleElement().asString().contains(shared.toString(), "Projekt bauen");
            assertThat(asked).hasSize(2); // nicht im Client gefragt
            assertThat(registry.config("access").getList("directories")).contains(shared.toString());
            assertThat(text(client.callTool(callRequest("permissions_check", Map.of("path", shared.toString())))))
                    .contains("Freigaben – Für alle Tools freigegeben");
        } finally {
            eliciting.closeGracefully();
            confirmation.setDesktopHandler(null);
            registry.updateValues("permissions", v -> {
                v.remove("promptVia");
                return v;
            });
            registry.updateValues("access", v -> {
                v.remove("directories");
                return v;
            });
            registry.updateValues("git", v -> {
                v.remove("allowSync");
                return v;
            });
        }
    }

    @Test
    void jdbcSwitchesArePermissionsTheUserGrants() throws Exception {
        String url = "jdbc:h2:mem:mcp" + java.util.UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1";
        try (java.sql.Connection keep = java.sql.DriverManager.getConnection(url, "sa", "pw-4711");
             java.sql.Statement st = keep.createStatement()) {
            st.execute("CREATE TABLE notiz (id INT PRIMARY KEY, text VARCHAR(100))");
            st.execute("INSERT INTO notiz VALUES (1, 'eins'), (2, 'zwei')");

            assertThat(toolNames()).noneMatch(n -> n.startsWith("jdbc_")); // Standard: aus
            assertThat(text(client.callTool(callRequest("permissions_check", Map.of("tool", "jdbc_delete")))))
                    .contains("jdbc_delete: nicht verfügbar",
                            "den Schalter „Datensätze löschen (DELETE)“ (allowDelete) einschalten",
                            "Modul „Datenbanken (JDBC)“ (jdbc) einschalten");

            registry.updateConfig("jdbc", Map.of("connections", systems.grebe.devtools.mcp.core.ModuleConfig.formatRecords(
                    List.of(Map.of("name", "notizen", "url", url, "username", "sa", "password", "pw-4711")))));
            registry.setModuleEnabled("jdbc", true);
            var transport = HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port).endpoint("/mcp").build();
            McpSyncClient eliciting = McpClient.sync(transport).requestTimeout(java.time.Duration.ofSeconds(30))
                    .clientInfo(new McpSchema.Implementation("test-client", "1.0"))
                    .capabilities(McpSchema.ClientCapabilities.builder().elicitation().build())
                    .elicitation(req -> new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.ACCEPT,
                            Map.of("grant", true)))
                    .build();
            try {
                assertThat(toolNames()).contains("jdbc_connections", "jdbc_databases", "jdbc_tables", "jdbc_describe",
                        "jdbc_query").doesNotContain("jdbc_insert", "jdbc_update", "jdbc_delete", "jdbc_ddl", "jdbc_execute");
                assertThat(client.listTools().tools()).filteredOn(t -> t.name().startsWith("jdbc_"))
                        .allSatisfy(t -> assertThat(t.description()).endsWith(ShellHints.JDBC));
                assertThat(text(client.callTool(callRequest("permissions_overview", Map.of("module", "jdbc")))))
                        .contains("(allowQuery): an – bietet: jdbc_query",
                                "(allowDelete): aus – würde freischalten: jdbc_delete",
                                "(allowExecute): aus – würde freischalten: jdbc_execute")
                        .doesNotContain("pw-4711");
                assertThat(text(client.callTool(callRequest("jdbc_query",
                        Map.of("sql", "SELECT text FROM notiz WHERE id = ?", "params", List.of(2))))))
                        .contains("1 Zeile (notizen,", "zwei");

                eliciting.initialize();
                assertThat(text(eliciting.callTool(callRequest("permissions_request",
                        Map.of("tool", "jdbc_delete", "reason", "Veraltete Notiz löschen")))))
                        .contains("Vom Nutzer erteilt (test-client)", "Neu verfügbar: jdbc_delete");
                assertThat(text(eliciting.callTool(callRequest("jdbc_delete",
                        Map.of("connection", "notizen", "sql", "DELETE FROM notiz WHERE id = ?", "params", List.of(1))))))
                        .startsWith("1 Zeile betroffen (notizen,");
                McpSchema.CallToolResult all = eliciting.callTool(callRequest("jdbc_delete",
                        Map.of("sql", "DELETE FROM notiz")));
                assertThat(all.isError()).isTrue();
                assertThat(text(all)).contains("ohne WHERE", "allRows=true");
            } finally {
                eliciting.closeGracefully();
                registry.setModuleEnabled("jdbc", false);
                registry.updateConfig("jdbc", Map.of());
            }
        }
    }
}
