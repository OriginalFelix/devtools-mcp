package systems.grebe.devtools.mcp.modules.debug;

import java.util.List;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolModule;
import systems.grebe.devtools.mcp.core.ToolScope;
import systems.grebe.devtools.mcp.modules.java.JavaEnvironmentProvider;

/** Java-Debugger (JDI) – verbindet sich mit einer per JDWP gestarteten JVM. Nur lesend, keine Ausdrucksauswertung. */
@Component
public class DebugModule implements ToolModule {

    static final String MAX_WAIT = "maxWaitSeconds";
    static final String DEPTH = "variableDepth";

    private final JavaEnvironmentProvider env;

    public DebugModule(JavaEnvironmentProvider env) {
        this.env = env;
    }

    @Override
    public String id() {
        return "debug";
    }

    @Override
    public String displayName() {
        return "Debugger (JDI)";
    }

    @Override
    public String description() {
        return "Hängt sich an eine JVM mit JDWP (-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005): "
                + "Breakpoints setzen, auf Treffer warten, Stack und Variablen lesen, Schritte ausführen. "
                + "Keine Ausdrucksauswertung und keine Wertänderung. Hält Threads an – nur für Entwicklungs-/Test-JVMs.";
    }

    @Override
    public String instructions() {
        return """
                Für Breakpoint-Debugging einer JVM mit JDWP diese Tools statt `jdb` verwenden: `debug_attach` → \
                `debug_set_breakpoint` → `debug_wait_for_break` → `debug_stack`/`debug_variables` → `debug_step`/`debug_resume`; zum \
                Schluss `debug_detach`. Breakpoints halten Threads der Ziel-JVM an – vorher mit dem Nutzer klären, wenn er gerade \
                selbst in der JVM arbeitet oder einen eigenen Debugger verbunden hat.""";
    }

    @Override
    public int order() {
        return 250;
    }

    @Override
    public List<ConfigField> configSchema() {
        return List.of(
                ConfigField.of(MAX_WAIT, "Max. Wartezeit auf Breakpoint (Sekunden)", FieldType.INT).withDefault("120"),
                ConfigField.of(DEPTH, "Objekttiefe bei Variablen", FieldType.INT).withDefault("2")
                        .withHelp("Wie weit verschachtelte Objekte aufgeklappt werden."));
    }

    @Override
    public List<ToolCallback> createTools(ModuleConfig config) {
        return createTools(config, ToolScope.LOCAL);
    }

    @Override
    public List<ToolCallback> createTools(ModuleConfig config, ToolScope scope) {
        DebugSessions sessions = scope.state("debug.sessions", DebugSessions::new);
        return List.of(ToolCallbacks.from(new DebugTools(env, sessions,
                Math.max(1, config.getInt(MAX_WAIT, 120)), Math.max(0, Math.min(5, config.getInt(DEPTH, 2))))));
    }
}
