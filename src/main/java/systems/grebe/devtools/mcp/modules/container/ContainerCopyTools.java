package systems.grebe.devtools.mcp.modules.container;

import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** Dateien zwischen Host (freigegebene Verzeichnisse) und Container kopieren. */
public class ContainerCopyTools {

    private final ContainerEnvironment env;

    ContainerCopyTools(ContainerEnvironment env) {
        this.env = env;
    }

    @Tool(name = "copy_from", description = "Kopiert eine Datei oder ein Verzeichnis aus dem Container auf den Host "
            + "(Ziel muss in einem freigegebenen Host-Verzeichnis liegen).")
    public String copyFrom(
            @ToolParam(description = ContainerReadTools.CONTAINER) String container,
            @ToolParam(description = "Absoluter Pfad im Container") String containerPath,
            @ToolParam(description = "Zielpfad auf dem Host") String hostPath,
            @ToolParam(required = false, description = ContainerReadTools.RUNTIME) String runtime) {
        env.checkContainer(container);
        checkContainerPath(containerPath);
        Path target = env.checkHostPath(hostPath);
        env.runtime(runtime).copyFrom(container, containerPath, target);
        return "Kopiert: " + container + ":" + containerPath + " -> " + target;
    }

    @Tool(name = "copy_to", description = "Kopiert eine Datei oder ein Verzeichnis vom Host (freigegebene Verzeichnisse) in den Container.")
    public String copyTo(
            @ToolParam(description = ContainerReadTools.CONTAINER) String container,
            @ToolParam(description = "Quellpfad auf dem Host") String hostPath,
            @ToolParam(description = "Absoluter Zielpfad im Container") String containerPath,
            @ToolParam(required = false, description = ContainerReadTools.RUNTIME) String runtime) {
        env.checkContainer(container);
        checkContainerPath(containerPath);
        Path source = env.checkHostPath(hostPath);
        if (!Files.exists(source)) {
            throw new IllegalArgumentException("Quelle existiert nicht: " + source);
        }
        env.runtime(runtime).copyTo(container, source, containerPath);
        return "Kopiert: " + source + " -> " + container + ":" + containerPath;
    }

    private static void checkContainerPath(String p) {
        if (p == null || !p.startsWith("/") || p.contains("\n")) {
            throw new IllegalArgumentException("Pfad im Container muss absolut sein: " + p);
        }
    }
}
