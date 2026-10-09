package systems.grebe.devtools.mcp.modules.container;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.container4j.ContainerRuntime;
import systems.grebe.devtools.mcp.core.Text;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.Workspaces;
import systems.grebe.devtools.mcp.core.ToolHints;

/** Lesender Zugriff auf Compose-Projekte in freigegebenen Verzeichnissen. */
@ToolHints(readOnly = true, openWorld = false)
public class ComposeReadTools {

    static final String PROJECT = "Compose-Projekt (Ordnername aus compose_projects). Leer = einziges Projekt.";
    static final String SERVICE = "Name eines Services aus der Compose-Datei";

    private final ContainerEnvironment env;

    ComposeReadTools(ContainerEnvironment env) {
        this.env = env;
    }

    @Tool(name = "compose_projects", description = "Listet die freigegebenen Compose-Projekte (Verzeichnisse mit compose.yaml)." + ShellHints.CONTAINER)
    public String projects() {
        StringBuilder sb = new StringBuilder();
        env.composeProjects().forEach((n, p) -> sb.append("- ").append(n).append(": ").append(p).append('\n'));
        if (Workspaces.unrestricted()) {
            sb.append("Alle Verzeichnisse sind freigegeben – weitere Compose-Projekte per absolutem Pfad angeben.");
        }
        return sb.isEmpty() ? "Keine Compose-Projekte konfiguriert." : sb.toString().strip();
    }

    @Tool(name = "compose_ps", description = "Zeigt die Services eines Compose-Projekts mit Zustand und Ports."
            + " Statt `podman compose ps` verwenden." + ShellHints.CONTAINER)
    public String ps(@ToolParam(required = false, description = PROJECT) String project,
                     @ToolParam(required = false, description = ContainerReadTools.RUNTIME) String runtime) {
        return run(project, runtime, List.of("ps", "-a"), Duration.ofMinutes(1));
    }

    @Tool(name = "compose_logs", description = "Logs eines Compose-Projekts oder einzelner Services."
            + " Statt `podman compose logs` verwenden." + ShellHints.CONTAINER)
    public String logs(@ToolParam(required = false, description = PROJECT) String project,
                       @ToolParam(required = false, description = SERVICE) String service,
                       @ToolParam(required = false, description = "Anzahl letzter Zeilen je Service (Standard aus Einstellungen)") Integer tail,
                       @ToolParam(required = false, description = ContainerReadTools.RUNTIME) String runtime) {
        List<String> args = new ArrayList<>(List.of("logs", "--no-color", "--tail",
                String.valueOf(tail == null || tail <= 0 ? env.logTail() : Math.min(tail, 10_000))));
        addService(args, service);
        return run(project, runtime, args, Duration.ofMinutes(2));
    }

    @Tool(name = "compose_config", description = "Zeigt die aufgelöste Compose-Konfiguration (Variablen ersetzt)."
            + " Statt `podman compose config` verwenden." + ShellHints.CONTAINER)
    public String config(@ToolParam(required = false, description = PROJECT) String project,
                         @ToolParam(required = false, description = ContainerReadTools.RUNTIME) String runtime) {
        return run(project, runtime, List.of("config"), Duration.ofMinutes(1));
    }

    static void addService(List<String> args, String service) {
        if (service != null && !service.isBlank()) {
            if (!service.matches("[A-Za-z0-9][A-Za-z0-9_.-]*")) {
                throw new IllegalArgumentException("Ungültiger Service-Name: " + service);
            }
            args.add(service);
        }
    }

    String run(String project, String runtime, List<String> args, Duration timeout) {
        Path dir = env.composeProject(project);
        ContainerRuntime rt = env.runtime(runtime);
        if (!rt.supportsCompose()) {
            throw new IllegalStateException("Die Laufzeit '" + rt.id() + "' bietet kein Compose (compose-Plugin fehlt).");
        }
        ContainerRuntime.ExecResult r = rt.compose(dir, args, timeout);
        String out = r.output().lines()
                .filter(l -> !l.startsWith(">>>> Executing external compose provider"))
                .reduce((a, b) -> a + "\n" + b).orElse("").strip();
        String head = r.timedOut() ? "Zeitüberschreitung" : r.ok() ? "" : "Fehler (Exit-Code " + r.exitCode() + ")\n";
        return head + (out.isEmpty() ? "(keine Ausgabe)" : Text.tailLines(out.lines().toList(), env.maxLines()));
    }
}
