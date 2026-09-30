package systems.grebe.devtools.mcp.modules.asprof;

import java.util.List;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.core.CommandRunner;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.ConnectionTestResult;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolModule;
import systems.grebe.devtools.mcp.modules.java.JavaEnvironmentProvider;

/**
 * async-profiler 4.5: sampling-Profiler ohne Safepoint-Bias (CPU inkl. nativer/Kernel-Frames, Allokationen, Locks, Wall-Clock).
 * Läuft nativ unter Linux/macOS. Unter Windows werden JVMs in Linux-Containern profiliert (asprof wird hineinkopiert).
 */
@Component
public class AsyncProfilerModule implements ToolModule {

    static final String HOME = "asprofHome";
    static final String AUTO_DOWNLOAD = "autoDownload";
    static final String MAX_SECONDS = "maxSeconds";

    private final JavaEnvironmentProvider env;

    public AsyncProfilerModule(JavaEnvironmentProvider env) {
        this.env = env;
    }

    @Override
    public String id() {
        return "asprof";
    }

    @Override
    public String displayName() {
        return "async-profiler";
    }

    @Override
    public String description() {
        return "Präziser Sampling-Profiler (CPU ohne Safepoint-Bias inkl. nativer Frames, Allokationen, Locks, Wall-Clock) "
                + "mit Flame Graphs. Nativ auf Linux/macOS; unter Windows für JVMs in Linux-Containern (container:<name>). "
                + "Lokale Windows-JVMs: Flight Recorder verwenden.";
    }

    @Override
    public String instructions() {
        return """
                Statt `asprof`/`profiler.sh` in der Shell: `asprof_profile` für eine feste Dauer, sonst \
                `asprof_start` → `asprof_stop`; `asprof_status` zeigt verfügbare Events. Genauer als JFR bei nativen Frames und ohne \
                Safepoint-Bias; fehlt das Modul, `jfr_record` verwenden.""";
    }

    @Override
    public int order() {
        return 230;
    }

    @Override
    public List<ConfigField> configSchema() {
        return List.of(
                ConfigField.of(HOME, "Installationsverzeichnis", FieldType.DIRECTORY)
                        .withHelp("Entpacktes async-profiler-Verzeichnis (mit bin/asprof) für lokale JVMs unter Linux/macOS. "
                                + "Leer = automatisch herunterladen."),
                ConfigField.of(AUTO_DOWNLOAD, "Automatisch herunterladen", FieldType.BOOLEAN).withDefault("true")
                        .withHelp("Lädt async-profiler 4.5 (SHA-256-geprüft) von GitHub nach ~/.devtools-mcp/tools – für "
                                + "Container immer die Linux-Variante passend zur Container-Architektur."),
                ConfigField.of(MAX_SECONDS, "Max. Profildauer (Sekunden)", FieldType.INT).withDefault("300"));
    }

    @Override
    public List<ToolCallback> createTools(ModuleConfig config) {
        return List.of(ToolCallbacks.from(new AsyncProfilerTools(env, new AsprofInstaller(config),
                Math.max(5, config.getInt(MAX_SECONDS, 300)))));
    }

    @Override
    public ConnectionTestResult testConnection(ModuleConfig config) {
        AsprofInstaller installer = new AsprofInstaller(config);
        StringBuilder sb = new StringBuilder();
        if (CommandRunner.WINDOWS) {
            sb.append("Windows: lokale JVMs werden nicht unterstützt, nur Container-Ziele.\n");
        } else {
            try {
                sb.append("Lokal: ").append(installer.localAsprof()).append('\n');
            } catch (RuntimeException e) {
                return ConnectionTestResult.failed("Lokal nicht verfügbar: " + e.getMessage());
            }
        }
        String cli = env.get().containers().cli();
        sb.append("Container-Laufzeit: ").append(cli == null ? "keine" : cli);
        if (installer.autoDownload()) {
            try {
                sb.append("\nLinux-x64-Paket: ").append(installer.linuxArchive("x86_64"));
            } catch (RuntimeException e) {
                return ConnectionTestResult.failed(sb + "\nDownload fehlgeschlagen: " + e.getMessage());
            }
        }
        return ConnectionTestResult.ok(sb.toString());
    }
}
