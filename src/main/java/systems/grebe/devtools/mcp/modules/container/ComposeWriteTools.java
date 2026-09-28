package systems.grebe.devtools.mcp.modules.container;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;

/** Compose-Projekte starten und stoppen. */
public class ComposeWriteTools {

    private final ComposeReadTools read;

    ComposeWriteTools(ContainerEnvironment env) {
        this.read = new ComposeReadTools(env);
    }

    @Tool(name = "compose_up", description = "Startet ein Compose-Projekt (oder einzelne Services) im Hintergrund (up -d)."
            + " Statt `podman compose up -d` verwenden." + ShellHints.CONTAINER)
    public String up(@ToolParam(required = false, description = ComposeReadTools.PROJECT) String project,
                     @ToolParam(required = false, description = ComposeReadTools.SERVICE) String service,
                     @ToolParam(required = false, description = "true = Images vorher neu bauen (--build)") Boolean build,
                     @ToolParam(required = false, description = ContainerReadTools.RUNTIME) String runtime) {
        List<String> args = new ArrayList<>(List.of("up", "-d"));
        if (Boolean.TRUE.equals(build)) {
            args.add("--build");
        }
        ComposeReadTools.addService(args, service);
        return read.run(project, runtime, args, Duration.ofMinutes(15));
    }

    @Tool(name = "compose_down", description = "Stoppt ein Compose-Projekt und entfernt dessen Container und Netzwerke."
            + " Statt `podman compose down` verwenden." + ShellHints.CONTAINER)
    public String down(@ToolParam(required = false, description = ComposeReadTools.PROJECT) String project,
                       @ToolParam(required = false, description = "true = auch benannte Volumes löschen (-v, Daten gehen verloren)") Boolean volumes,
                       @ToolParam(required = false, description = ContainerReadTools.RUNTIME) String runtime) {
        List<String> args = new ArrayList<>(List.of("down"));
        if (Boolean.TRUE.equals(volumes)) {
            args.add("-v");
        }
        return read.run(project, runtime, args, Duration.ofMinutes(5));
    }

    @Tool(name = "compose_restart", description = "Startet Services eines Compose-Projekts neu."
            + " Statt `podman compose restart` verwenden." + ShellHints.CONTAINER)
    public String restart(@ToolParam(required = false, description = ComposeReadTools.PROJECT) String project,
                          @ToolParam(required = false, description = ComposeReadTools.SERVICE) String service,
                          @ToolParam(required = false, description = ContainerReadTools.RUNTIME) String runtime) {
        List<String> args = new ArrayList<>(List.of("restart"));
        ComposeReadTools.addService(args, service);
        return read.run(project, runtime, args, Duration.ofMinutes(5));
    }
}
