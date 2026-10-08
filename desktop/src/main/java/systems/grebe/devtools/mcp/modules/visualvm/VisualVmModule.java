package systems.grebe.devtools.mcp.modules.visualvm;

import java.util.List;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.ConnectionTestResult;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolModule;
import systems.grebe.devtools.mcp.modules.java.JavaEnvironmentProvider;
import systems.grebe.devtools.mcp.core.ToolBeans;

/**
 * VisualVM: eingebettete Engines (Heap-Analyse, CPU-Sampler über JMX) als Tools für das LLM sowie die
 * VisualVM-Oberfläche (extern) zum Öffnen von Prozessen und Dateien für den Menschen.
 */
@Component
public class VisualVmModule implements ToolModule {

    static final String HOME = "visualvmHome";
    static final String AUTO_DOWNLOAD = "autoDownload";
    static final String SAMPLE_INTERVAL = "sampleIntervalMs";

    private final JavaEnvironmentProvider env;
    private final org.springframework.beans.factory.ObjectProvider<systems.grebe.devtools.mcp.core.ToolRegistry> registry;

    public VisualVmModule(JavaEnvironmentProvider env,
                          org.springframework.beans.factory.ObjectProvider<systems.grebe.devtools.mcp.core.ToolRegistry> registry) {
        this.env = env;
        this.registry = registry;
    }

    @Override
    public String id() {
        return "visualvm";
    }

    @Override
    public String displayName() {
        return "VisualVM";
    }

    @Override
    public String description() {
        return "Heap-Dump-Analyse (größte Objekte, Pfad zur GC-Wurzel) und CPU-Sampling über JMX mit den eingebetteten "
                + "VisualVM-Engines – auch für entfernte JVMs. Zusätzlich öffnet die VisualVM-Oberfläche Prozesse, "
                + "Heap-Dumps, JFR-Dateien und Snapshots, um Befunde selbst anzusehen.";
    }

    @Override
    public String instructions() {
        return """
                - `visualvm_heap_analyze` für `.hprof`-Dateien (größte Objekte, Retained Size, Pfad zur GC-Wurzel) – statt \
                Heap-Dumps selbst zu parsen oder `jhat`/Eclipse MAT per Shell zu starten.
                - `visualvm_sample_cpu` für CPU-Sampling über JMX, auch bei entfernten JVMs.
                - `visualvm_open`/`visualvm_open_file` nur, wenn der Nutzer die Oberfläche selbst sehen will; sie öffnen ein Fenster \
                bei ihm und liefern keine Auswertung.""";
    }

    @Override
    public int order() {
        return 240;
    }

    @Override
    public boolean enabledByDefault() {
        return true;
    }

    @Override
    public List<ConfigField> configSchema() {
        return List.of(
                ConfigField.of(HOME, "VisualVM-Verzeichnis", FieldType.DIRECTORY)
                        .withHelp("Installation mit bin/visualvm(.exe). Leer = automatisch herunterladen (nur für die Oberfläche nötig)."),
                ConfigField.of(AUTO_DOWNLOAD, "Oberfläche automatisch herunterladen", FieldType.BOOLEAN).withDefault("true")
                        .withHelp("VisualVM 2.2.2 (23 MB, SHA-256-geprüft) nach ~/.devtools-mcp/tools beim ersten Öffnen."),
                ConfigField.of(SAMPLE_INTERVAL, "Sampling-Intervall (ms)", FieldType.INT).withDefault("20")
                        .withHelp("Abstand der Thread-Dumps beim CPU-Sampler. Kleiner = genauer, aber mehr Last."));
    }

    @Override
    public List<ToolCallback> createTools(ModuleConfig config) {
        return ToolBeans.callbacks(new VisualVmTools(env, config));
    }

    /** Für die UI: Datei in der VisualVM-Oberfläche öffnen (mit der gespeicherten Modulkonfiguration). */
    public String openFile(java.nio.file.Path file) {
        ModuleConfig config = registry.getObject().config(id());
        return new VisualVmLauncher(config, env.get().jdkHome()).launch(List.of("--openfile", file.toString()));
    }

    @Override
    public ConnectionTestResult testConnection(ModuleConfig config) {
        try {
            var exe = new VisualVmLauncher(config, env.get().jdkHome()).executable();
            return ConnectionTestResult.ok("Heap-Analyse und Sampler: eingebettet (VisualVM 2.2 Engines).\nOberfläche: " + exe);
        } catch (RuntimeException e) {
            return ConnectionTestResult.failed("Heap-Analyse und Sampler: eingebettet.\nOberfläche nicht verfügbar: " + e.getMessage());
        }
    }
}
