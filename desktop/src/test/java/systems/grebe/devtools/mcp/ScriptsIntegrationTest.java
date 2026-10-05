package systems.grebe.devtools.mcp;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
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
import systems.grebe.devtools.mcp.config.SettingsStore;
import systems.grebe.devtools.mcp.core.ToolRegistry;
import systems.grebe.devtools.mcp.modules.scripts.ScriptBackend;
import systems.grebe.devtools.mcp.modules.scripts.ScriptManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Groovy-Skripte End-to-End: Anlegen über MCP (Schalter), Tools erscheinen und verschwinden zur Laufzeit, Einstellungen
 * aus dem Modul-Formular, Fehler mit Zeile, Namenskonflikte, Änderungen „von woanders“ per Subscription und
 * Instructions für neue Sessions.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "devtools.local-user.email=scripts@example.com")
class ScriptsIntegrationTest {

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

    private static final String DEMO = """
            module {
                name 'Demo'
                description 'Begrüßungen für den Test'
                instructions 'Zum Begrüßen `demo_hello` verwenden.'
                setting 'greeting', 'Gruß', STRING, defaultValue: 'Hallo'
            }

            tool('hello') {
                description 'Begrüßt jemanden'
                param 'who', String, 'Wen'
                readOnly true
                run { args, cfg -> "${cfg.greeting} ${args.who}" }
            }
            """;

    @LocalServerPort
    int port;

    @Autowired
    ToolRegistry registry;

    @Autowired
    ScriptManager scripts;

    @Autowired
    ScriptBackend backend;

    McpSyncClient client;

    @BeforeEach
    void setUp() {
        registry.updateConfig("skills", Map.of("reviewNudgeInterval", "0")); // keine Skill-Hinweise in Ergebnissen
        client = connect();
    }

    @AfterEach
    void tearDown() {
        client.closeGracefully();
        registry.updateConfig("scripts", Map.of());
        registry.updateConfig("skills", Map.of());
    }

    private McpSyncClient connect() {
        var transport = HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port).endpoint("/mcp").build();
        McpSyncClient c = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(30)).build();
        c.initialize();
        return c;
    }

    private List<String> toolNames() {
        return client.listTools().tools().stream().map(McpSchema.Tool::name).toList();
    }

    private McpSchema.CallToolResult call(String name, Map<String, Object> args) {
        return client.callTool(McpSchema.CallToolRequest.builder(name).arguments(args).build());
    }

    private static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().getFirst()).text();
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
    void scriptsAddAndRemoveToolsAtRuntime() throws Exception {
        // Standard: nur lesen, das LLM darf nichts anlegen
        assertThat(toolNames()).contains("scripts_list", "scripts_view").doesNotContain("scripts_save", "scripts_delete");
        assertThat(text(call("scripts_list", Map.of()))).contains("Noch keine Skripte");
        assertThat(text(call("scripts_view", Map.of()))).contains("DSL", "tool('open_issues')");

        registry.updateConfig("scripts", Map.of("allowWrite", "true", "allowDelete", "true"));
        assertThat(toolNames()).contains("scripts_save", "scripts_delete");

        // Anlegen → Modul mit Tool sofort da
        McpSchema.CallToolResult saved = call("scripts_save", Map.of("name", "demo", "content", DEMO));
        assertThat(saved.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(text(saved)).contains("angelegt (Revision 1)", "demo_hello");
        assertThat(toolNames()).contains("demo_hello");
        McpSchema.Tool hello = client.listTools().tools().stream().filter(t -> t.name().equals("demo_hello"))
                .findFirst().orElseThrow();
        assertThat(hello.annotations().readOnlyHint()).isTrue();
        assertThat(text(call("demo_hello", Map.of("who", "Welt")))).isEqualTo("Hallo Welt");

        // Einstellungen aus dem Modul-Formular
        assertThat(registry.modules()).anySatisfy(m -> assertThat(m.id()).isEqualTo("demo"));
        registry.updateConfig("demo", Map.of("greeting", "Moin"));
        assertThat(text(call("demo_hello", Map.of("who", "Welt")))).isEqualTo("Moin Welt");

        // Instructions neuer Sessions enthalten das Skript-Modul
        McpSyncClient second = connect();
        try {
            assertThat(second.getServerInstructions()).contains("## Demo – Tools `demo_*`", "`demo_hello` verwenden");
        } finally {
            second.closeGracefully();
        }

        // Fehler: Zeile, nichts gespeichert, altes Tool bleibt
        McpSchema.CallToolResult broken = call("scripts_save", Map.of("name", "demo",
                "content", "module { description 'x' }\ntool('a') {\n descripton 'y'\n run { 1 }\n}"));
        assertThat(broken.isError()).isTrue();
        assertThat(text(broken)).contains("descripton", "Zeile 3");
        assertThat(text(call("demo_hello", Map.of("who", "Welt")))).isEqualTo("Moin Welt");

        // Name eines eingebauten Moduls
        McpSchema.CallToolResult conflict = call("scripts_save", Map.of("name", "git", "content", DEMO));
        assertThat(conflict.isError()).isTrue();
        assertThat(text(conflict)).contains("eingebauten Moduls");

        // Ändern: neue Revision, neues Tool, altes weg
        String v2 = DEMO.replace("tool('hello')", "tool('hi')");
        assertThat(text(call("scripts_save", Map.of("name", "demo", "content", v2, "note", "umbenannt",
                "expected_revision", 1)))).contains("Revision 2", "demo_hi");
        assertThat(toolNames()).contains("demo_hi").doesNotContain("demo_hello");
        assertThat(text(call("scripts_view", Map.of("name", "demo", "revision", 1)))).contains("tool('hello')");

        // Änderung von woanders (andere Desktop-App, direkt im Backend) kommt per Subscription an
        backend.save("other", "Von woanders", "module { description 'Von woanders' }\n"
                + "tool('ping') { description 'Ping'; run { 'pong' } }", null, null);
        await(() -> registry.hasModule("other"), "Skript per Subscription geladen");
        assertThat(text(call("other_ping", Map.of()))).isEqualTo("pong");

        // Kaputtes Skript im Backend: Modul mit Fehler statt Tools
        backend.save("kaputt", "Kaputt", "tool(", null, null);
        await(() -> registry.hasModule("kaputt"), "kaputtes Skript geladen");
        assertThat(registry.moduleError("kaputt")).hasValueSatisfying(e -> assertThat(e).contains("Zeile"));
        assertThat(text(call("scripts_list", Map.of()))).contains("demo:", "aktiv – Tools: demo_hi", "kaputt:",
                "Fehler:");

        // Löschen: Tools weg
        assertThat(text(call("scripts_delete", Map.of("name", "demo")))).contains("gelöscht");
        assertThat(toolNames()).doesNotContain("demo_hi");
        assertThat(registry.hasModule("demo")).isFalse();
        backend.delete("other");
        backend.delete("kaputt");
        await(() -> !registry.hasModule("other") && !registry.hasModule("kaputt"), "per Subscription entfernt");
        assertThat(scripts.statuses()).isEmpty();
    }
}
