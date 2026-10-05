package systems.grebe.devtools.mcp.modules.scripts;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolBeans;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DSL ohne Spring: Übersetzen, Definition, Tool-Aufruf, Fehlermeldungen mit Zeile und Zeitlimit. */
class ScriptCompilerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    static final String JIRA = """
            module {
                name 'Jira-Helfer'
                description 'Eigene Jira-Abfragen'
                instructions 'Für Jira diese Tools verwenden.'
                setting 'baseUrl', 'Basis-URL', URL, required: true
                setting 'token', 'API-Token', SECRET
                setting 'limit', 'Standard-Limit', INT, defaultValue: '20'
                setting 'verbose', 'Ausführlich', Boolean
            }

            tool('open_issues') {
                description 'Offene Issues eines Projekts'
                param 'project', String, 'Projektschlüssel'
                param 'limit', Integer, 'Höchstens so viele', required: false
                param 'state', String, 'Status', options: ['open', 'closed'], required: false
                readOnly true
                run { args, cfg ->
                    progress "Frage ${cfg.baseUrl} ab"
                    [project: args.project, limit: args.limit ?: cfg.limit, state: args.state, url: "${cfg.baseUrl}/x"]
                }
            }

            tool('hello') {
                description 'Begrüßt'
                param 'who', 'Wen'
                run { args -> "Hallo ${args.who}" }
            }

            tool('nothing') {
                description 'Gibt nichts zurück'
                run { -> null }
            }
            """;

    private final ScriptCompiler compiler = new ScriptCompiler();

    private static ScriptViews.Summary summary(String name) {
        return new ScriptViews.Summary(name, "d", ScriptViews.Scope.OWN, 1, Instant.now(), null);
    }

    private static ToolCallback tool(List<ToolCallback> tools, String name) {
        return tools.stream().filter(t -> t.getToolDefinition().name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void definesModuleSettingsAndTools() {
        try (ScriptCompiler.Compiled c = compiler.compile("jira", JIRA)) {
            ScriptDefinition d = c.definition();
            assertThat(d.displayName()).isEqualTo("Jira-Helfer");
            assertThat(d.description()).isEqualTo("Eigene Jira-Abfragen");
            assertThat(d.instructions()).isEqualTo("Für Jira diese Tools verwenden.");
            assertThat(d.settings()).extracting(ConfigField::key, ConfigField::type).containsExactly(
                    org.assertj.core.groups.Tuple.tuple("baseUrl", FieldType.URL),
                    org.assertj.core.groups.Tuple.tuple("token", FieldType.SECRET),
                    org.assertj.core.groups.Tuple.tuple("limit", FieldType.INT),
                    org.assertj.core.groups.Tuple.tuple("verbose", FieldType.BOOLEAN));
            assertThat(d.settings().getFirst().required()).isTrue();
            assertThat(d.settings().get(2).defaultValue()).isEqualTo("20");
            assertThat(d.tools()).extracting(ScriptDefinition.Tool::name).containsExactly("open_issues", "hello", "nothing");
            assertThat(d.tools().getFirst().annotations().readOnlyHint()).isTrue();
            assertThat(d.tools().get(1).annotations()).isNull();
        }
    }

    @Test
    void toolsRunWithArgumentsAndSettings() throws Exception {
        try (ScriptCompiler.Compiled c = compiler.compile("jira", JIRA)) {
            ScriptToolModule module = ScriptToolModule.of(summary("jira"), c, () -> Duration.ofSeconds(5));
            List<ToolCallback> tools = module.createTools(ModuleConfig.of(module.configSchema(),
                    Map.of("baseUrl", "https://jira.example.com")));
            assertThat(tools).hasSize(3);
            assertThat(ToolBeans.annotations(tool(tools, "open_issues")).readOnlyHint()).isTrue();

            JsonNode schema = JSON.readTree(tool(tools, "open_issues").getToolDefinition().inputSchema());
            assertThat(schema.get("required").toString()).isEqualTo("[\"project\"]");
            assertThat(schema.at("/properties/limit/type").asString()).isEqualTo("integer");
            assertThat(schema.at("/properties/state/enum").toString()).isEqualTo("[\"open\",\"closed\"]");

            JsonNode result = JSON.readTree(tool(tools, "open_issues").call("{\"project\":\"ABC\"}"));
            assertThat(result.get("project").asString()).isEqualTo("ABC");
            assertThat(result.get("limit").asLong()).isEqualTo(20); // Default der Einstellung, typgerecht
            assertThat(result.get("url").asString()).isEqualTo("https://jira.example.com/x"); // GString → Text
            assertThat(JSON.readTree(tool(tools, "open_issues").call("{\"project\":\"A\",\"limit\":\"3\"}"))
                    .get("limit").asLong()).isEqualTo(3);

            assertThat(tool(tools, "hello").call("{\"who\":\"Welt\"}")).isEqualTo("Hallo Welt");
            assertThat(tool(tools, "nothing").call("{}")).isEqualTo("OK");

            assertThatThrownBy(() -> tool(tools, "open_issues").call("{}"))
                    .hasMessageContaining("Pflichtparameter fehlt: project");
            assertThatThrownBy(() -> tool(tools, "open_issues").call("{\"project\":\"A\",\"state\":\"weg\"}"))
                    .hasMessageContaining("open, closed");
        }
    }

    @Test
    void errorsNameTheLine() {
        assertThatThrownBy(() -> compiler.compile("bad", "module {\n description 'x'\n\ntool('a') {"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nicht übersetzen")
                .hasMessageContaining("Zeile");
        assertThatThrownBy(() -> compiler.compile("bad", "module { description 'x' }\ntool('a') {\n descripton 'y'\n run { 1 } }"))
                .hasMessageContaining("descripton").hasMessageContaining("Zeile 3");
        assertThatThrownBy(() -> compiler.compile("bad", "tool('a') { description 'y'; run { 1 } }"))
                .hasMessageContaining("Beschreibung fehlt");
        assertThatThrownBy(() -> compiler.compile("bad", "module { description 'x' }"))
                .hasMessageContaining("keine Tools");
        assertThatThrownBy(() -> compiler.compile("bad", "module { description 'x' }\ntool('A-b') { }"))
                .hasMessageContaining("Ungültiger Tool-Name");
        assertThatThrownBy(() -> compiler.compile("bad", "module { description 'x' }\ntool('a') { run { 1 } }"))
                .hasMessageContaining("braucht eine description");

        try (ScriptCompiler.Compiled c = compiler.compile("boom", """
                module { description 'x' }
                tool('fail') {
                    description 'wirft'
                    run {
                        throw new IllegalStateException('kaputt')
                    }
                }""")) {
            ToolCallback t = ScriptToolModule.of(summary("boom"), c, () -> Duration.ofSeconds(5))
                    .createTools(ModuleConfig.of(List.of(), Map.of())).getFirst();
            assertThatThrownBy(() -> t.call("{}")).hasMessageContaining("kaputt").hasMessageContaining("Zeile 5");
        }
    }

    @Test
    void endlessLoopsAreStoppedByTheTimeout() {
        assertThatThrownBy(() -> compiler.compile("loop", "while (true) { }"))
                .hasMessageContaining("Zeitlimit");
        try (ScriptCompiler.Compiled c = compiler.compile("loop", """
                module { description 'x' }
                tool('spin') { description 'dreht'; run { while (true) { } } }""")) {
            ToolCallback t = ScriptToolModule.of(summary("loop"), c, () -> Duration.ofMillis(300))
                    .createTools(ModuleConfig.of(List.of(), Map.of())).getFirst();
            long start = System.nanoTime();
            assertThatThrownBy(() -> t.call("{}")).hasMessageContaining("Zeitlimit von 0 s");
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
        }
    }

    @Test
    void brokenModuleReportsItsError() {
        ScriptToolModule m = ScriptToolModule.broken(summary("x"), "Zeile 1: kaputt");
        assertThat(m.error()).contains("Zeile 1: kaputt");
        assertThatThrownBy(() -> m.createTools(ModuleConfig.of(List.of(), Map.of()))).hasMessage("Zeile 1: kaputt");
    }
}
