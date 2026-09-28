package systems.grebe.devtools.mcp.modules.asprof;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

import systems.grebe.devtools.mcp.core.CommandRunner;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.modules.java.JvmTarget;
import systems.grebe.devtools.mcp.modules.java.ToolDownloads;

/** Stellt asprof lokal bzw. in einem Container bereit. */
final class AsprofInstaller {

    static final String CONTAINER_DIR = "/tmp/devtools-mcp";
    private static final String VERSION_DIR = "async-profiler-4.5-linux-";

    private final String home;
    private final boolean autoDownload;

    AsprofInstaller(ModuleConfig c) {
        this.home = c.getString(AsyncProfilerModule.HOME, null);
        this.autoDownload = c.getBoolean(AsyncProfilerModule.AUTO_DOWNLOAD);
    }

    boolean autoDownload() {
        return autoDownload;
    }

    /** asprof für lokale JVMs (nur Linux/macOS). */
    Path localAsprof() {
        if (CommandRunner.WINDOWS) {
            throw new IllegalStateException("async-profiler unterstützt Windows nicht. Für lokale JVMs jfr_record + jfr_flamegraph "
                    + "verwenden, oder die JVM in einem Linux-Container starten und container:<name> als Ziel angeben.");
        }
        if (home != null) {
            Path p = Path.of(home, "bin", "asprof");
            if (!Files.isRegularFile(p)) {
                throw new IllegalStateException("bin/asprof nicht gefunden in " + home);
            }
            return p;
        }
        if (!autoDownload) {
            throw new IllegalStateException("async-profiler nicht konfiguriert (Installationsverzeichnis oder automatischer Download).");
        }
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            Path zip = ToolDownloads.get(ToolDownloads.Artifact.ASYNC_PROFILER_MACOS);
            Path dir = ToolDownloads.unzipOnce(zip, "async-profiler-4.5-macos");
            return find(dir);
        }
        Path tgz = linuxArchive(arch.contains("aarch64") || arch.contains("arm64") ? "aarch64" : "x86_64");
        Path dir = tgz.resolveSibling(tgz.getFileName().toString().replace(".tar.gz", ""));
        if (!Files.isRegularFile(dir.resolve("bin/asprof"))) {
            // tar erhält die Ausführungsrechte
            CommandRunner.run(List.of("tar", "xzf", tgz.toString(), "-C", tgz.getParent().toString()), Duration.ofMinutes(1))
                    .orThrow("Entpacken von async-profiler");
        }
        return dir.resolve("bin/asprof");
    }

    private static Path find(Path dir) {
        try (var s = Files.walk(dir, 4)) {
            return s.filter(p -> p.getFileName().toString().equals("asprof")).findFirst()
                    .orElseThrow(() -> new IllegalStateException("asprof im Archiv nicht gefunden"));
        } catch (IOException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /** Linux-Archiv passend zur Architektur ({@code uname -m}). */
    Path linuxArchive(String unameM) {
        if (!autoDownload) {
            throw new IllegalStateException("Automatischer Download ist deaktiviert – für Container wird das Linux-Paket benötigt.");
        }
        boolean arm = unameM.contains("aarch64") || unameM.contains("arm64");
        return ToolDownloads.get(arm ? ToolDownloads.Artifact.ASYNC_PROFILER_LINUX_ARM64 : ToolDownloads.Artifact.ASYNC_PROFILER_LINUX_X64);
    }

    /**
     * Stellt asprof im Container bereit und liefert den Pfad dort. Das Archiv wird im Container entpackt,
     * damit Ausführungsrechte erhalten bleiben (auch wenn diese App unter Windows läuft).
     */
    String containerAsprof(JvmTarget.InContainer t) {
        var arch = t.exec(Duration.ofSeconds(20), "uname", "-m");
        String m = arch.output().strip();
        boolean arm = m.contains("aarch64") || m.contains("arm64");
        String dir = CONTAINER_DIR + "/" + VERSION_DIR + (arm ? "arm64" : "x64");
        String asprof = dir + "/bin/asprof";
        if (t.exec(Duration.ofSeconds(20), "test", "-x", asprof).ok()) {
            return asprof;
        }
        Path tgz = linuxArchive(m);
        t.exec(Duration.ofSeconds(20), "mkdir", "-p", CONTAINER_DIR).orThrow("Arbeitsordner im Container anlegen");
        String remoteTgz = CONTAINER_DIR + "/asprof.tar.gz";
        t.containers().copyTo(t.container(), tgz, remoteTgz);
        t.exec(Duration.ofMinutes(1), "tar", "xzf", remoteTgz, "-C", CONTAINER_DIR).orThrow("Entpacken im Container (tar vorhanden?)");
        t.exec(Duration.ofSeconds(20), "rm", "-f", remoteTgz);
        return asprof;
    }
}
