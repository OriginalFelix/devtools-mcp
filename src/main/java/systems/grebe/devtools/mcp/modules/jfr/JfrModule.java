package systems.grebe.devtools.mcp.modules.jfr;

import java.util.List;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolModule;
import systems.grebe.devtools.mcp.modules.java.JavaEnvironmentProvider;

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
        return "Flight Recorder (JFR)";
    }

    @Override
    public String description() {
        return "Zeichnet mit dem Java Flight Recorder auf und wertet aus: CPU-Hotspots, Allokationen, GC-Pausen, "
                + "Lock-Konkurrenz, Exceptions, langsame I/O – plus interaktive Flame Graphs. Funktioniert auf jeder "
                + "Plattform, auch unter Windows.";
    }

    @Override
    public int order() {
        return 220;
    }

    @Override
    public boolean enabledByDefault() {
        return true;
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
        return List.of(ToolCallbacks.from(new JfrTools(env, config.getString(SETTINGS, "profile"),
                Math.max(5, config.getInt(MAX_SECONDS, 300)))));
    }
}
