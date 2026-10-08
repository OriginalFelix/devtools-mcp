package systems.grebe.devtools.mcp.modules.jfr;

import java.util.List;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolModule;
import systems.grebe.devtools.mcp.modules.java.JavaEnvironmentProvider;
import systems.grebe.devtools.mcp.core.ToolBeans;

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
    public String instructions() {
        return """
                Für Java-Flight-Recorder-Aufzeichnungen diese Tools statt `jcmd JFR.*` oder `jfr print` verwenden: \
                `jfr_record` für eine feste Dauer, sonst `jfr_start` → `jfr_dump` → `jfr_stop`; auswerten mit `jfr_analyze` \
                (cpu, allocation, gc, locks, io, exceptions, threads) und `jfr_flamegraph`. Erste Wahl für CPU-, Allokations- und \
                Lock-Analysen auf jeder Plattform.""";
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
        return ToolBeans.callbacks(new JfrTools(env, config.getString(SETTINGS, "profile"),
                Math.max(5, config.getInt(MAX_SECONDS, 300))));
    }
}
