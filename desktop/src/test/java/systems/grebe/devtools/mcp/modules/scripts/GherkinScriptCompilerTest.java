package systems.grebe.devtools.mcp.modules.scripts;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolBeans;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Gherkin-Skripte ohne Spring: Zuordnung der Schritte beim Übersetzen, Ablauf, Prüfungen, Variablen, Fehler. */
class GherkinScriptCompilerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Merkt sich die Aufrufe; Ergebnis je Tool aus {@link #answers}. */
    static final class FakeTools implements ToolCaller {
        final List<String> calls = new ArrayList<>();
        final Map<String, Function<Map<String, Object>, String>> answers = new LinkedHashMap<>();

        FakeTools answer(String tool, Function<Map<String, Object>, String> answer) {
            answers.put(tool, answer);
            return this;
        }

        @Override
        public String call(String name, Map<String, Object> args) {
            calls.add(name + " " + args);
            Function<Map<String, Object>, String> a = answers.get(name);
            if (a == null) {
                throw new IllegalArgumentException("Tool '" + name + "' gibt es nicht.");
            }
            return a.apply(args);
        }

        @Override
        public boolean isActive(String name) {
            return answers.containsKey(name);
        }
    }

    static final String CHECK = """
            # language: de
            Funktionalität: Schnellcheck
              Prüft ein Repository.

              @readOnly
              Szenario: Branch prüfen
                Prüft Status und Tests.
                <repo>: Repository-Name
                <limit>: optional – Höchstzahl

                Wenn ich das Tool "git_status" aufrufe:
                  | repository | <repo>  |
                  | limit      | <limit> |
                Dann enthält das Ergebnis "sauber"
                Und das Ergebnis enthält "FEHLER" nicht
                Und ich merke mir "Branch: (\\S+)" aus dem Ergebnis als branch
                Wenn ich das Tool "build_test" aufrufe:
                  \"""
                  {"project": "<repo>", "filter": "${branch}"}
                  \"""
                Dann passt das Ergebnis zu "Tests: \\d+ gesamt"
                Und ich gebe "<repo> auf ${branch}: alles grün" aus
            """;

    private static List<ToolCallback> tools(CompiledScript c) {
        return c.tools(ModuleConfig.of(List.of(), Map.of()), () -> Duration.ofSeconds(5));
    }

    private static ToolCallback tool(CompiledScript c, String name) {
        return tools(c).stream().filter(t -> t.getToolDefinition().name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void scenarioBecomesToolWithParamsAndHints() {
        FakeTools fake = new FakeTools().answer("git_status", a -> "ok").answer("build_test", a -> "ok");
        CompiledScript c = new GherkinScriptCompiler(fake).compile("check", CHECK);
        assertThat(c.displayName()).isEqualTo("Schnellcheck");
        assertThat(c.description()).isEqualTo("Prüft ein Repository.");
        assertThat(c.toolNames()).containsExactly("branch_pruefen");
        assertThat(c.warnings()).isEmpty();

        ToolCallback t = tool(c, "branch_pruefen");
        assertThat(t.getToolDefinition().description()).isEqualTo("Prüft Status und Tests.");
        JsonNode schema = JSON.readTree(t.getToolDefinition().inputSchema());
        assertThat(schema.get("properties").get("repo").get("description").asString()).isEqualTo("Repository-Name");
        assertThat(schema.get("properties").get("branch")).isNull(); // Variable, kein Parameter
        assertThat(schema.get("required").values()).extracting(JsonNode::asString).containsExactly("repo");
        McpSchema.ToolAnnotations hints = ToolBeans.annotations(t);
        assertThat(hints.readOnlyHint()).isTrue();
    }

    @Test
    void runCallsToolsChecksResultsAndReports() {
        FakeTools fake = new FakeTools()
                .answer("git_status", a -> "Branch: feature/x\nArbeitsverzeichnis sauber.")
                .answer("build_test", a -> "BUILD ERFOLGREICH\nTests: 12 gesamt, 0 fehlgeschlagen");
        CompiledScript c = new GherkinScriptCompiler(fake).compile("check", CHECK);

        String result = tool(c, "branch_pruefen").call("{\"repo\": \"web-core\"}");

        // leerer optionaler Parameter wird weggelassen; DocString-JSON mit eingesetzten Werten
        assertThat(fake.calls).containsExactly("git_status {repository=web-core}",
                "build_test {project=web-core, filter=feature/x}");
        assertThat(result).startsWith("Szenario „Branch prüfen“ erfolgreich (7 Schritte).")
                .contains("Ausgabe:\nweb-core auf feature/x: alles grün")
                .contains("✓ Zeile 11: Wenn ich das Tool \"git_status\" aufrufe:", "→ git_status: Branch: feature/x",
                        "→ ${branch} = feature/x", "✓ Zeile 21: Dann passt das Ergebnis zu \"Tests: \\d+ gesamt\"");

        tool(c, "branch_pruefen").call("{\"repo\": \"web-core\", \"limit\": \"5\"}");
        assertThat(fake.calls.get(2)).isEqualTo("git_status {repository=web-core, limit=5}");
    }

    @Test
    void failingCheckStopsWithLineAndProtocol() {
        FakeTools fake = new FakeTools().answer("git_status", a -> "Branch: x\nFEHLER: unversionierte Dateien")
                .answer("build_test", a -> "nie aufgerufen");
        CompiledScript c = new GherkinScriptCompiler(fake).compile("check", CHECK);

        assertThatThrownBy(() -> tool(c, "branch_pruefen").call("{\"repo\": \"web-core\"}"))
                .hasMessageContaining("Szenario „Branch prüfen“ in Zeile 14 fehlgeschlagen")
                .hasMessageContaining("Das Ergebnis von git_status enthält „sauber“ nicht.")
                .hasMessageContaining("✓ Zeile 11")
                .hasMessageContaining("→ git_status: Branch: x")
                .hasMessageContaining("✗ Zeile 14: Dann enthält das Ergebnis \"sauber\"");
        assertThat(fake.calls).hasSize(1);

        // Fehler eines aufgerufenen Tools: Meldung des Tools
        FakeTools broken = new FakeTools().answer("git_status", a -> {
            throw new IllegalStateException("Repository 'nix' ist nicht freigegeben");
        }).answer("build_test", a -> "");
        CompiledScript c2 = new GherkinScriptCompiler(broken).compile("check", CHECK);
        assertThatThrownBy(() -> tool(c2, "branch_pruefen").call("{\"repo\": \"nix\"}"))
                .hasMessageContaining("in Zeile 11 fehlgeschlagen")
                .hasMessageContaining("Repository 'nix' ist nicht freigegeben");
        // Pflichtparameter fehlt
        assertThatThrownBy(() -> tool(c2, "branch_pruefen").call("{}")).hasMessageContaining("repo");
    }

    @Test
    void errorsInTheScriptComeWithLineBeforeAnythingRuns() {
        GherkinScriptCompiler compiler = new GherkinScriptCompiler(new FakeTools().answer("a_b", x -> ""));
        assertThatThrownBy(() -> compiler.compile("d", """
                Funktionalität: D
                  Szenario: S
                    Wenn ich das Tool "a_b" aufrufe
                    Dann sollte alles gut sein
                """)).hasMessageContaining("Zeile 4").hasMessageContaining("Unbekannter Schritt")
                .hasMessageContaining("ich rufe das Tool {string} auf");
        assertThatThrownBy(() -> compiler.compile("d", """
                Funktionalität: D
                  Szenario: S
                    Wenn ich das Tool <tool> aufrufe
                """)).hasMessageContaining("Anführungszeichen");
        assertThatThrownBy(() -> compiler.compile("d", """
                Funktionalität: D
                  Szenario: S
                    Dann enthält das Ergebnis "x"
                """)).hasMessageContaining("Zeile 3").hasMessageContaining("kein Tool aufgerufen");
        assertThatThrownBy(() -> compiler.compile("d", """
                Funktionalität: D
                  Szenario: S
                    Wenn ich das Tool "a_b" aufrufe
                    Und ich gebe "${wert}" aus
                """)).hasMessageContaining("Zeile 4").hasMessageContaining("${wert}");
        assertThatThrownBy(() -> compiler.compile("d", """
                Funktionalität: D
                  Szenario: S
                    Wenn ich das Tool "a_b" aufrufe:
                      | nur eine Spalte |
                """)).hasMessageContaining("zwei Spalten");
        assertThatThrownBy(() -> compiler.compile("d", """
                Funktionalität: D
                  Szenario: S
                    Wenn ich das Tool "a_b" aufrufe:
                      \"""
                      {"limit": <n>}
                      \"""
                """)).hasMessageContaining("JSON-Objekt");
        assertThatThrownBy(() -> compiler.compile("d", """
                Funktionalität: D
                  Szenario: S
                    Wenn ich das Tool "a_b" aufrufe
                    Dann passt das Ergebnis zu "(offen"
                """)).hasMessageContaining("regulärer Ausdruck");
        assertThatThrownBy(() -> compiler.compile("d", """
                Funktionalität: D
                  Szenario: S
                    Angenommen ich setze repo auf "<repo>"
                """)).hasMessageContaining("Parameter <repo>");
        assertThatThrownBy(() -> compiler.compile("d", """
                Funktionalität: D
                  Szenario: S
                    Angenommen ich gebe "x" aus:
                      | a | b |
                """)).hasMessageContaining("keine Tabelle");
        assertThatThrownBy(() -> compiler.compile("d", """
                Funktionalität: D
                  Szenario: S
                    Wenn ich das Tool "Git Status" aufrufe
                """)).hasMessageContaining("kein Tool-Name");
    }

    @Test
    void inactiveToolsOnlyWarn() {
        CompiledScript c = new GherkinScriptCompiler(new FakeTools()).compile("d", """
                Funktionalität: D
                  Szenario: Eins
                    Wenn ich das Tool "fehlt_noch" aufrufe
                    Und ich rufe das Tool "d_zwei" auf
                  Szenario: Zwei
                    Wenn ich rufe das Tool "<tool>" auf
                """);
        // eigene Tools (d_zwei) und Platzhalter zählen nicht
        assertThat(c.warnings()).singleElement().asString().contains("fehlt_noch", "Zeile 3", "nicht aktiv");
    }

    @Test
    void variablesPollingAndEquality() {
        int[] polls = {0};
        FakeTools fake = new FakeTools()
                .answer("job_start", a -> "Job-ID: 42")
                .answer("job_status", a -> ++polls[0] < 3 ? "läuft" : "fertig für " + a.get("job"));
        CompiledScript c = new GherkinScriptCompiler(fake).compile("job", """
                Funktionalität: Jobs
                  Szenario: Warten
                    Angenommen ich setze art auf "voll"
                    Wenn ich rufe das Tool "job_start" auf
                    Und ich merke mir "Job-ID: (\\d+)" aus dem Ergebnis als job
                    Und ich rufe das Tool "job_status" alle 1 Sekunde auf, bis das Ergebnis "fertig" enthält:
                      | job | ${job} |
                    Dann ist das Ergebnis "fertig für 42"
                    Und ich merke das Ergebnis als status
                    Und ich gebe "${art}: ${status}" aus
                """);
        String result = tool(c, "warten").call("{}");
        assertThat(polls[0]).isEqualTo(3);
        assertThat(result).contains("Ausgabe:\nvoll: fertig für 42", "→ job_status (3 Aufrufe): fertig für 42");
    }

    @Test
    void placeholderValuesAreLiteralInRegularExpressions() {
        FakeTools fake = new FakeTools().answer("a_b", a -> "Version 1x2");
        CompiledScript c = new GherkinScriptCompiler(fake).compile("d", """
                Funktionalität: D
                  Szenario: S
                    Wenn ich das Tool "a_b" aufrufe
                    Dann passt das Ergebnis zu "Version <v>$"
                """);
        assertThatThrownBy(() -> tool(c, "s").call("{\"v\": \"1.2\"}")).hasMessageContaining("passt nicht");
        assertThat(tool(c, "s").call("{\"v\": \"1x2\"}")).contains("erfolgreich");
    }

    @Test
    void timeoutStopsWaitingScenario() {
        FakeTools fake = new FakeTools().answer("a_b", a -> "läuft");
        CompiledScript c = new GherkinScriptCompiler(fake).compile("d", """
                Funktionalität: D
                  Szenario: S
                    Wenn ich rufe das Tool "a_b" alle 1 Sekunde auf, bis das Ergebnis "fertig" enthält
                """);
        ToolCallback t = c.tools(ModuleConfig.of(List.of(), Map.of()), () -> Duration.ofMillis(300)).getFirst();
        assertThatThrownBy(() -> t.call("{}")).hasMessageContaining("Zeitlimit");
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }
}
