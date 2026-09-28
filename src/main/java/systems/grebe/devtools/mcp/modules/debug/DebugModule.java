package systems.grebe.devtools.mcp.modules.debug;

import java.util.List;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolModule;
import systems.grebe.devtools.mcp.modules.java.JavaEnvironmentProvider;

/** Java-Debugger (JDI) – verbindet sich mit einer per JDWP gestarteten JVM. Nur lesend, keine Ausdrucksauswertung. */
@Component
public class DebugModule implements ToolModule {

    static final String MAX_WAIT = "maxWaitSeconds";
    static final String DEPTH = "variableDepth";

    private final JavaEnvironmentProvider env;
    private final DebugSessions sessions;

    public DebugModule(JavaEnvironmentProvider env, DebugSessions sessions) {
        this.env = env;
        this.sessions = sessions;
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
        return List.of(ToolCallbacks.from(new DebugTools(env, sessions,
                Math.max(1, config.getInt(MAX_WAIT, 120)), Math.max(0, Math.min(5, config.getInt(DEPTH, 2))))));
    }
}
