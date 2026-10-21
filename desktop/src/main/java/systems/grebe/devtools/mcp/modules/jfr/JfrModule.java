package systems.grebe.devtools.mcp.modules.jfr;

import java.util.List;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolModule;
import systems.grebe.devtools.mcp.modules.java.JavaEnvironmentProvider;
import systems.grebe.devtools.mcp.modules.java.SdkTools;
import systems.grebe.performance.mcp.PerformanceArea;
import systems.grebe.performance.mcp.PerformanceTools;
import systems.grebe.performance.mcp.settings.JfrSettings;

/** Java Flight Recorder: Aufzeichnen (lokal, Container, JMX), Auswerten, Flame Graphs. */
@Component
public class JfrModule implements ToolModule {

    static final String SETTINGS = "settings";
    static final String MAX_SECONDS = "maxSeconds";

    private final JavaEnvironmentProvider env;

    public JfrModule(JavaEnvironmentProvider env) {
        this.env = env;
    }

    @Override
    public String id() {
        return "jfr";
    }

    @Override
    public String displayName() {
        return PerformanceArea.JFR.displayName();
    }

    @Override
    public String description() {
        return PerformanceArea.JFR.description();
    }

    @Override
    public String instructions() {
        return PerformanceArea.JFR.instructions();
    }

    @Override
    public String briefInstructions() {
        return PerformanceArea.JFR.briefInstructions();
    }

    @Override
    public int order() {
        return PerformanceArea.JFR.order();
    }

    @Override
    public boolean enabledByDefault() {
        return PerformanceArea.JFR.enabledByDefault();
    }

    @Override
    public List<ConfigField> configSchema() {
        return List.of(
                ConfigField.of(SETTINGS, "Standard-Profil", FieldType.ENUM).withDefault("profile").withOptions("profile", "default")
                        .withHelp("'profile' = feinere Samples (ca. 2 % Overhead), 'default' = für Dauerbetrieb (< 1 %)."),
                ConfigField.of(MAX_SECONDS, "Max. Dauer für jfr_record (Sekunden)", FieldType.INT).withDefault("300"));
    }

    @Override
    public List<ToolCallback> createTools(ModuleConfig config) {
        return SdkTools.adapt(PerformanceTools.jfrTools(env, new JfrSettings(config.getString(SETTINGS, "profile"),
                config.getInt(MAX_SECONDS, 300))));
    }
}
