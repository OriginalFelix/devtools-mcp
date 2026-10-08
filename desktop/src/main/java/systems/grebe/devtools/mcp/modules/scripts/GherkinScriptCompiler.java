package systems.grebe.devtools.mcp.modules.scripts;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import systems.grebe.devtools.mcp.backend.scripts.GherkinScripts;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.ManagedToolCallback;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.McpToolHints;
import tools.jackson.databind.json.JsonMapper;

/**
 * Macht aus einem Gherkin-Skript ein Modul: jedes Szenario ein Tool, das die Schritte mit den eingebauten Schritten
 * ({@link GherkinSteps}) ausführt – vor allem vorhandene Tools aufrufen und ihre Ergebnisse prüfen. Gelesen wird mit
 * {@link GherkinScripts} (dieselben Regeln wie die Syntaxprüfung im Backend).
 *
 * <p>Beim Übersetzen wird alles geprüft, was ohne Ausführung geht: jeder Schritt muss zu genau einem eingebauten
 * Schritt passen, Variablen müssen vorher gesetzt, reguläre Ausdrücke gültig, Tabellen zweispaltig und DocStrings
 * JSON-Objekte sein, Ergebnis-Prüfungen brauchen einen Tool-Aufruf davor. Tools, die gerade nicht aktiv sind, ergeben
 * nur einen Hinweis – sie können später kommen (andere Skripte, Schalter in der App).
 */
public final class GherkinScriptCompiler {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ToolCaller tools;

    GherkinScriptCompiler(ToolCaller tools) {
        this.tools = tools;
    }

    /**
     * Liest, ordnet die Schritte zu und prüft.
     *
     * @throws IllegalArgumentException mit Zeile bei Fehlern im Skript
     */
    public CompiledScript compile(String scriptName, String source) {
        GherkinScripts.Script script;
        try {
            script = GherkinScripts.parse(scriptName, source);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Skript '" + scriptName + "': " + e.getMessage(), e);
        }
        Set<String> own = script.scenarios().stream()
                .map(s -> ManagedToolCallback.prefixed(scriptName, s.toolName())).collect(Collectors.toSet());
        Set<String> warnings = new LinkedHashSet<>();
        List<Scenario> scenarios = new ArrayList<>();
        for (GherkinScripts.Scenario s : script.scenarios()) {
            try {
                List<GherkinSteps.Bound> steps = s.steps().stream().map(GherkinSteps::bind).toList();
                check(s, steps, own, warnings);
                scenarios.add(new Scenario(s, steps));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Skript '" + scriptName + "', Szenario „" + s.name() + "“: "
                        + e.getMessage(), e);
            }
        }
        return new Compiled(scriptName, script, scenarios, List.copyOf(warnings), tools);
    }

    /** Prüft den Ablauf eines Szenarios ohne ihn auszuführen. */
    private void check(GherkinScripts.Scenario scenario, List<GherkinSteps.Bound> steps, Set<String> own,
                       Set<String> warnings) {
        Set<String> params = scenario.params().stream().map(GherkinScripts.Param::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> defined = new LinkedHashSet<>(params);
        boolean hasResult = false;
        for (GherkinSteps.Bound b : steps) {
            GherkinScripts.Step step = b.step();
            GherkinSteps.Definition d = b.definition();
            String at = "Zeile " + step.line() + ": ";

            List<String> texts = new ArrayList<>();
            for (int i = 0; i < b.args().size(); i++) {
                if (i != d.variableArg() && b.args().get(i) instanceof String s) {
                    texts.add(s);
                }
            }
            if (step.table() != null) {
                step.table().forEach(texts::addAll);
            }
            texts.add(step.docString());
            for (String text : texts) {
                for (String v : GherkinSteps.variablesIn(text)) {
                    if (!defined.contains(v)) {
                        throw new IllegalArgumentException(at + "Variable ${" + v + "} ist hier noch nicht gesetzt "
                                + "(vorher z.B. „ich setze " + v + " auf \"…\"“ oder „ich merke mir das Ergebnis als "
                                + v + "“).");
                    }
                }
            }
            if (d.needsResult() && !hasResult) {
                throw new IllegalArgumentException(at + "„" + step.display() + "“ braucht ein Ergebnis, aber davor "
                        + "wird kein Tool aufgerufen.");
            }
            if (d.regexArg() >= 0) {
                GherkinSteps.requireRegex(b, d.regexArg());
            }
            if (d.toolArg() >= 0) {
                checkToolCall(b, own, warnings);
            } else if (step.table() != null || step.docString() != null) {
                throw new IllegalArgumentException(at + "Der Schritt „" + step.display() + "“ nimmt keine Tabelle "
                        + "und keinen DocString – die gibt es nur bei Tool-Aufrufen.");
            }
            if (d.variableArg() >= 0) {
                String v = b.string(d.variableArg());
                if (!GherkinSteps.VARIABLE.matcher(v).matches()) {
                    throw new IllegalArgumentException(at + "'" + v + "' ist kein gültiger Variablenname "
                            + "(Buchstaben, Ziffern und _, beginnend mit einem Buchstaben).");
                }
                if (params.contains(v)) {
                    throw new IllegalArgumentException(at + "'" + v + "' ist schon der Parameter <" + v + "> – für die "
                            + "Variable einen anderen Namen wählen.");
                }
                defined.add(v);
            }
            hasResult |= d.callsTool();
        }
    }

    private void checkToolCall(GherkinSteps.Bound b, Set<String> own, Set<String> warnings) {
        GherkinScripts.Step step = b.step();
        String at = "Zeile " + step.line() + ": ";
        String tool = b.string(b.definition().toolArg());
        if (!tool.contains("<") && !tool.contains("${")) {
            if (!GherkinSteps.TOOL_NAME.matcher(tool).matches()) {
                throw new IllegalArgumentException(at + "'" + tool + "' ist kein Tool-Name (voller Name mit Präfix, "
                        + "z.B. git_status).");
            }
            if (!own.contains(tool) && !tools.isActive(tool)) {
                warnings.add("Tool '" + tool + "' (" + at.substring(0, at.length() - 2) + ") ist gerade nicht aktiv – "
                        + "der Aufruf schlägt fehl, solange es fehlt oder abgeschaltet ist.");
            }
        }
        if (step.table() != null) {
            for (List<String> row : step.table()) {
                if (row.size() != 2) {
                    throw new IllegalArgumentException(at + "Die Tabelle eines Tool-Aufrufs hat genau zwei Spalten: "
                            + "| parameter | wert | (ohne Kopfzeile).");
                }
            }
        }
        if (step.docString() != null) {
            try {
                if (!JSON.readTree(step.docString()).isObject()) {
                    throw new IllegalArgumentException("kein Objekt");
                }
            } catch (RuntimeException e) {
                throw new IllegalArgumentException(at + "Der DocString eines Tool-Aufrufs muss ein JSON-Objekt sein "
                        + "(Platzhalter nur in Texten, z.B. \"limit\": \"<limit>\").");
            }
        }
    }

    /** MCP-Hinweise aus den Tags; wie in der Groovy-DSL ist ein nicht lesendes Tool ohne Angabe destruktiv. */
    static McpSchema.ToolAnnotations annotations(Set<String> hints) {
        if (hints.isEmpty()) {
            return null;
        }
        boolean ro = hints.contains("readonly");
        return new McpSchema.ToolAnnotations(null, ro, ro ? null : true, ro ? null : hints.contains("idempotent"),
                true, null);
    }

    /** Ein Szenario mit zugeordneten Schritten. */
    record Scenario(GherkinScripts.Scenario scenario, List<GherkinSteps.Bound> steps) {
    }

    /** Übersetztes Gherkin-Skript; hält keinen eigenen ClassLoader. */
    record Compiled(String scriptName, GherkinScripts.Script script, List<Scenario> scenarios, List<String> warnings,
                    ToolCaller caller) implements CompiledScript {

        @Override
        public String displayName() {
            return script.displayName();
        }

        @Override
        public String description() {
            return script.description();
        }

        @Override
        public String instructions() {
            return null;
        }

        @Override
        public List<ConfigField> settings() {
            return List.of();
        }

        @Override
        public boolean enabledByDefault() {
            return true;
        }

        @Override
        public List<String> toolNames() {
            return scenarios.stream().map(s -> s.scenario().toolName()).toList();
        }

        @Override
        public List<ToolCallback> tools(ModuleConfig config, Supplier<Duration> timeout) {
            return scenarios.stream()
                    .map(s -> McpToolHints.withAnnotations(new ScenarioTool(scriptName, s, caller, timeout),
                            annotations(s.scenario().hints())))
                    .toList();
        }

        @Override
        public void close() {
            // nichts freizugeben
        }
    }

    /** Ein Szenario als Tool: Parameter aus den Platzhaltern, Ausführung unter dem Zeitlimit der Skripte. */
    static final class ScenarioTool implements ToolCallback {

        private final String scriptName;
        private final Scenario scenario;
        private final ToolCaller caller;
        private final Supplier<Duration> timeout;
        private final List<ScriptDefinition.Param> params;
        private final ToolDefinition definition;

        ScenarioTool(String scriptName, Scenario scenario, ToolCaller caller, Supplier<Duration> timeout) {
            this.scriptName = scriptName;
            this.scenario = scenario;
            this.caller = caller;
            this.timeout = timeout;
            this.params = scenario.scenario().params().stream()
                    .map(p -> new ScriptDefinition.Param(p.name(), "string", p.description(), p.required(), List.of()))
                    .toList();
            this.definition = ToolDefinition.builder().name(scenario.scenario().toolName())
                    .description(scenario.scenario().description())
                    .inputSchema(ScriptToolCallback.inputSchema(params)).build();
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public String call(String toolInput) {
            Map<String, String> values = new LinkedHashMap<>();
            ScriptToolCallback.arguments(params, toolInput).forEach((k, v) -> values.put(k, v == null ? "" : v.toString()));
            GherkinScripts.Scenario s = scenario.scenario();
            return ScriptTimeout.run("Tool '" + s.toolName() + "' (Skript '" + scriptName + "')", timeout.get(),
                    () -> new ScenarioRun(s.name(), values, caller).run(scenario.steps()));
        }
    }
}
