package systems.grebe.devtools.mcp.modules.java;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import systems.grebe.container4j.ContainerRuntime;
import systems.grebe.devtools.mcp.core.CommandRunner;
import systems.grebe.devtools.mcp.modules.container.ContainerEnvironment;

/**
 * Container-Zugriff für die Java-Diagnosemodule ({@code container:}-Ziele). Nutzt die Laufzeiten und
 * Freigaben des Container-Moduls.
 */
public final class Containers {

    private final ContainerEnvironment env;

    Containers(ContainerEnvironment env) {
        this.env = env;
    }

    /** ID der Standard-Laufzeit oder {@code null}, wenn keine erreichbar ist. */
    public String cli() {
        ContainerRuntime rt = env.runtimeOrNull(null);
        return rt == null ? null : rt.id();
    }

    private ContainerRuntime runtime() {
        return env.runtime(null);
    }

    public void checkAllowed(String container) {
        env.checkContainer(container);
    }

    public record Container(String name, String image, String status) { }

    public List<Container> running() {
        ContainerRuntime rt = env.runtimeOrNull(null);
        if (rt == null) {
            return List.of();
        }
        List<Container> out = new ArrayList<>();
        try {
            for (var c : rt.list(false)) {
                if (env.containerAllowed(c.name())) {
                    out.add(new Container(c.name(), c.image(), c.status()));
                }
            }
        } catch (RuntimeException ignored) {
            // Laufzeit nicht erreichbar -> keine Container
        }
        return out;
    }

    /** Führt einen Befehl im Container aus. */
    public CommandRunner.Result exec(String container, Duration timeout, String... command) {
        checkAllowed(container);
        var r = runtime().exec(container, List.of(command), null, null, timeout);
        return new CommandRunner.Result(List.of(command), r.exitCode(), r.timedOut(), r.output());
    }

    public void copyFrom(String container, String containerPath, Path local) {
        checkAllowed(container);
        runtime().copyFrom(container, containerPath, local);
    }

    public void copyTo(String container, Path local, String containerPath) {
        checkAllowed(container);
        runtime().copyTo(container, local, containerPath);
    }
}
