package systems.grebe.devtools.mcp.modules.debug;

import java.util.List;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolModule;
import systems.grebe.devtools.mcp.core.ToolScope;
import systems.grebe.devtools.mcp.modules.java.JavaEnvironmentProvider;
import systems.grebe.devtools.mcp.modules.java.SdkTools;
import systems.grebe.performance.mcp.PerformanceArea;
import systems.grebe.performance.mcp.PerformanceTools;
import systems.grebe.performance.mcp.debug.DebugSessions;
import systems.grebe.performance.mcp.settings.DebugSettings;

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
        return PerformanceArea.DEBUG.displayName();
    }

    @Override
    public String description() {
        return PerformanceArea.DEBUG.description();
    }

    @Override
    public String instructions() {
        return PerformanceArea.DEBUG.instructions();
    }

    @Override
    public String briefInstructions() {
        return PerformanceArea.DEBUG.briefInstructions();
    }

    @Override
    public int order() {
        return PerformanceArea.DEBUG.order();
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
        DebugSessions sessions = scope.state(PerformanceTools.DEBUG_SESSIONS_KEY, DebugSessions::new);
        return SdkTools.adapt(PerformanceTools.debugTools(env,
                new DebugSettings(config.getInt(MAX_WAIT, 120), config.getInt(DEPTH, 2)), sessions));
    }
}
