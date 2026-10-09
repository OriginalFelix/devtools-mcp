package systems.grebe.devtools.mcp.modules.container;

import java.time.Duration;
import java.util.List;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.container4j.ContainerRuntime;
import systems.grebe.devtools.mcp.core.Text;
import systems.grebe.devtools.mcp.core.ShellHints;

/** Befehle in Containern ausführen (nur wenn im Modul erlaubt). */
public class ContainerExecTools {

    private final ContainerEnvironment env;

    ContainerExecTools(ContainerEnvironment env) {
        this.env = env;
    }

    @Tool(name = "exec", description = "Führt einen Befehl in einem laufenden Container aus (ohne Shell; für Pipes/Umleitungen "
            + "explizit [\"sh\",\"-c\",\"…\"] übergeben). Liefert Exit-Code und Ausgabe."
            + " Statt `podman exec` verwenden." + ShellHints.CONTAINER)
    public String exec(
            @ToolParam(description = ContainerReadTools.CONTAINER) String container,
            @ToolParam(description = "Befehl als Liste: Programm und Argumente, z.B. [\"ls\",\"-la\",\"/app\"]") List<String> command,
            @ToolParam(required = false, description = "Arbeitsverzeichnis im Container") String workDir,
            @ToolParam(required = false, description = "Benutzer im Container (z.B. root oder 1000)") String user,
            @ToolParam(required = false, description = "Timeout in Sekunden (max. Einstellung)") Integer timeoutSeconds,
            @ToolParam(required = false, description = ContainerReadTools.RUNTIME) String runtime) {
        env.checkContainer(container);
        env.checkExec(command == null ? List.of() : command);
        if (user != null && !user.isBlank() && !user.matches("[A-Za-z0-9_.:-]+")) {
            throw new IllegalArgumentException("Ungültiger Benutzer: " + user);
        }
        int t = timeoutSeconds == null || timeoutSeconds <= 0 ? env.execTimeout() : Math.min(timeoutSeconds, env.execTimeout());
        ContainerRuntime.ExecResult r = env.runtime(runtime).exec(container, command, workDir, user, Duration.ofSeconds(t));
        String head = r.timedOut() ? "Zeitüberschreitung nach " + t + " s" : "Exit-Code " + r.exitCode();
        String out = r.output().strip();
        return head + (out.isEmpty() ? " (keine Ausgabe)" : "\n" + Text.limitLines(out, env.maxLines()));
    }
}
