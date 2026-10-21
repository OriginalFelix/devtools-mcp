package systems.grebe.devtools.mcp.modules.java;

import java.util.Map;
import java.util.function.Supplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolRegistry;
import systems.grebe.devtools.mcp.modules.container.ContainerEnvironment;
import systems.grebe.devtools.mcp.modules.container.ContainerModule;
import systems.grebe.devtools.mcp.modules.container.ContainerRuntimes;
import systems.grebe.performance.mcp.PerformanceTools;
import systems.grebe.performance.mcp.java.JavaEnvironment;

/**
 * Liefert die {@link JavaEnvironment} aus den aktuell gespeicherten Grundeinstellungen und den Einstellungen
 * des Container-Moduls. Tools rufen {@link #get()} bei jedem Aufruf auf – Änderungen wirken dadurch sofort.
 */
@Component
public class JavaEnvironmentProvider implements Supplier<JavaEnvironment> {

    private final ObjectProvider<ToolRegistry> registry;
    private final ContainerRuntimes runtimes;
    private volatile Map<String, String> lastJava;
    private volatile Map<String, String> lastContainer;
    private volatile JavaEnvironment cached;

    public JavaEnvironmentProvider(ObjectProvider<ToolRegistry> registry, ContainerRuntimes runtimes) {
        this.registry = registry;
        this.runtimes = runtimes;
    }

    @Override
    public JavaEnvironment get() {
        ToolRegistry r = registry.getObject();
        ModuleConfig java = r.config(JavaSettingsModule.ID);
        ModuleConfig container = r.config(ContainerModule.ID);
        if (cached == null || !java.rawValues().equals(lastJava) || !container.rawValues().equals(lastContainer)) {
            cached = environment(java, container);
            lastJava = java.rawValues();
            lastContainer = container.rawValues();
        }
        return cached;
    }

    /** Für Verbindungsprüfungen mit ungespeicherten Java-Einstellungen. */
    public JavaEnvironment with(ModuleConfig java) {
        return environment(java, registry.getObject().config(ContainerModule.ID));
    }

    private JavaEnvironment environment(ModuleConfig java, ModuleConfig container) {
        return PerformanceTools.environment(JavaSettingsModule.settings(java),
                new ContainerEnvironmentAccess(new ContainerEnvironment(runtimes, container)));
    }
}
