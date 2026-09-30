package systems.grebe.devtools.mcp.modules.visualvm;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import systems.grebe.devtools.mcp.core.CommandRunner;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.modules.java.ToolDownloads;

/** Findet bzw. installiert die VisualVM-Oberfläche und startet sie mit Befehlszeilenoptionen. */
final class VisualVmLauncher {

    private final String home;
    private final boolean autoDownload;
    private final Path jdk;

    VisualVmLauncher(ModuleConfig c, Path jdk) {
        this.home = c.getString(VisualVmModule.HOME, null);
        this.autoDownload = c.getBoolean(VisualVmModule.AUTO_DOWNLOAD);
        this.jdk = jdk;
    }

    Path executable() {
        Path dir;
        if (home != null) {
            dir = Path.of(home);
        } else if (autoDownload) {
            Path zip = ToolDownloads.get(ToolDownloads.Artifact.VISUALVM);
            dir = ToolDownloads.unzipOnce(zip, "visualvm_222").resolve("visualvm_222");
        } else {
            throw new IllegalStateException("VisualVM ist nicht konfiguriert (Installationsverzeichnis oder automatischer Download).");
        }
        String name = CommandRunner.WINDOWS ? "visualvm.exe" : "visualvm";
        Path exe = dir.resolve("bin").resolve(name);
        if (!Files.isRegularFile(exe)) {
            throw new IllegalStateException("VisualVM-Starter nicht gefunden: " + exe);
        }
        if (!CommandRunner.WINDOWS) {
            exe.toFile().setExecutable(true, false);
        }
        return exe;
    }

    /** Startet VisualVM (bzw. übergibt die Optionen an eine laufende Instanz) ohne zu warten. */
    String launch(List<String> options) {
        Path exe = executable();
        List<String> cmd = new ArrayList<>(List.of(exe.toString(), "--jdkhome", jdk.toString()));
        cmd.addAll(options);
        try {
            new ProcessBuilder(cmd).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("VisualVM konnte nicht gestartet werden: " + e.getMessage(), e);
        }
        return "VisualVM gestartet: " + String.join(" ", options).toLowerCase(Locale.ROOT).replace("--", "");
    }
}
