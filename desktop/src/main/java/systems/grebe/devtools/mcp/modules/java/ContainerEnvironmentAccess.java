package systems.grebe.devtools.mcp.modules.java;

import systems.grebe.container4j.ContainerRuntime;
import systems.grebe.devtools.mcp.modules.container.ContainerEnvironment;
import systems.grebe.performance.mcp.container.ContainerAccess;

/** Adapter: das Container-Modul von devtools als {@link ContainerAccess} des SDK performance-mcp. */
public final class ContainerEnvironmentAccess implements ContainerAccess {

    private final ContainerEnvironment env;

    public ContainerEnvironmentAccess(ContainerEnvironment env) {
        this.env = env;
    }

    @Override
    public ContainerRuntime runtimeOrNull() {
        return env.runtimeOrNull(null);
    }

    @Override
    public ContainerRuntime runtime() {
        return env.runtime(null);
    }

    @Override
    public void checkContainer(String container) {
        env.checkContainer(container);
    }

    @Override
    public boolean containerAllowed(String container) {
        return env.containerAllowed(container);
    }
}
