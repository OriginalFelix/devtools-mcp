package systems.grebe.devtools.mcp.modules.java;

import java.util.function.Supplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolRegistry;

/**
 * Liefert die {@link JavaEnvironment} aus den aktuell gespeicherten Grundeinstellungen. Tools rufen
 * {@link #get()} bei jedem Aufruf auf – Änderungen an den Grundeinstellungen wirken dadurch sofort.
 */
@Component
public class JavaEnvironmentProvider implements Supplier<JavaEnvironment> {

    private final ObjectProvider<ToolRegistry> registry;
    private volatile ModuleConfig lastConfig;
    private volatile JavaEnvironment cached;

    public JavaEnvironmentProvider(ObjectProvider<ToolRegistry> registry) {
        this.registry = registry;
    }

    @Override
    public JavaEnvironment get() {
        ModuleConfig config = registry.getObject().config(JavaSettingsModule.ID);
        if (cached == null || lastConfig == null || !lastConfig.rawValues().equals(config.rawValues())) {
            cached = new JavaEnvironment(config);
            lastConfig = config;
        }
        return cached;
    }
}
