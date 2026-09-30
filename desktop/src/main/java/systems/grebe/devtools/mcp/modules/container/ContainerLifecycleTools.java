package systems.grebe.devtools.mcp.modules.container;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;

/** Container starten, stoppen, neu starten. */
public class ContainerLifecycleTools {

    private final ContainerEnvironment env;

    ContainerLifecycleTools(ContainerEnvironment env) {
        this.env = env;
    }

    @Tool(name = "start", description = "Startet einen gestoppten Container."
            + " Statt `podman start` verwenden." + ShellHints.CONTAINER)
    public String start(@ToolParam(description = ContainerReadTools.CONTAINER) String container,
                        @ToolParam(required = false, description = ContainerReadTools.RUNTIME) String runtime) {
        env.checkContainer(container);
        env.runtime(runtime).start(container);
        return "Container " + container + " gestartet.";
    }

    @Tool(name = "stop", description = "Stoppt einen laufenden Container (SIGTERM, nach Wartezeit SIGKILL)."
            + " Statt `podman stop` verwenden." + ShellHints.CONTAINER)
    public String stop(@ToolParam(description = ContainerReadTools.CONTAINER) String container,
                       @ToolParam(required = false, description = "Wartezeit in Sekunden vor SIGKILL (Standard 10)") Integer timeoutSeconds,
                       @ToolParam(required = false, description = ContainerReadTools.RUNTIME) String runtime) {
        env.checkContainer(container);
        env.runtime(runtime).stop(container, wait(timeoutSeconds));
        return "Container " + container + " gestoppt.";
    }

    @Tool(name = "restart", description = "Startet einen Container neu."
            + " Statt `podman restart` verwenden." + ShellHints.CONTAINER)
    public String restart(@ToolParam(description = ContainerReadTools.CONTAINER) String container,
                          @ToolParam(required = false, description = "Wartezeit in Sekunden vor SIGKILL (Standard 10)") Integer timeoutSeconds,
                          @ToolParam(required = false, description = ContainerReadTools.RUNTIME) String runtime) {
        env.checkContainer(container);
        env.runtime(runtime).restart(container, wait(timeoutSeconds));
        return "Container " + container + " neu gestartet.";
    }

    private static int wait(Integer t) {
        return t == null || t < 0 ? 10 : Math.min(t, 300);
    }
}
