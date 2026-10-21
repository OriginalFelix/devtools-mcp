package systems.grebe.devtools.mcp.modules.asprof;

import java.util.List;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import systems.grebe.performance.mcp.support.CommandRunner;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.ConnectionTestResult;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolModule;
import systems.grebe.devtools.mcp.modules.java.JavaEnvironmentProvider;
import systems.grebe.devtools.mcp.modules.java.SdkTools;
import systems.grebe.performance.mcp.PerformanceArea;
import systems.grebe.performance.mcp.PerformanceTools;
import systems.grebe.performance.mcp.asprof.AsprofInstaller;
import systems.grebe.performance.mcp.settings.AsprofSettings;

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
        return PerformanceArea.ASPROF.displayName();
    }

    @Override
    public String description() {
        return PerformanceArea.ASPROF.description();
    }

    @Override
    public String instructions() {
        return PerformanceArea.ASPROF.instructions();
    }

    @Override
    public int order() {
        return PerformanceArea.ASPROF.order();
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

    static AsprofSettings settings(ModuleConfig config) {
        return new AsprofSettings(config.getString(HOME, null), config.getBoolean(AUTO_DOWNLOAD),
                config.getInt(MAX_SECONDS, 300));
    }

    @Override
    public List<ToolCallback> createTools(ModuleConfig config) {
        return SdkTools.adapt(PerformanceTools.asprofTools(env, settings(config)));
    }

    @Override
    public ConnectionTestResult testConnection(ModuleConfig config) {
        AsprofInstaller installer = PerformanceTools.asprofInstaller(env, settings(config));
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
