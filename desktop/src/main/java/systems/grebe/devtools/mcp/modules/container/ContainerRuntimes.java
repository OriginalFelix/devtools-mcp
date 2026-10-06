package systems.grebe.devtools.mcp.modules.container;

import java.util.List;
import java.util.ServiceLoader;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.core.ProviderRegistry;
import systems.grebe.devtools.mcp.modules.container.spi.ContainerRuntimeProvider;
import systems.grebe.devtools.mcp.plugin.PluginManager;

/**
 * Alle {@link ContainerRuntimeProvider}: die eingebauten über {@link ServiceLoader} – geladen mit dem ClassLoader der
 * SPI-Schnittstelle, damit es auch im Spring-Boot-Fat-Jar funktioniert – plus die aus aktiven Plugins.
 */
@Component
public class ContainerRuntimes extends ProviderRegistry<ContainerRuntimeProvider> {

    /** Nur die eingebauten Provider (Tests, Verbindungsprüfungen). */
    public ContainerRuntimes() {
        this(builtin());
    }

    /** Für Tests: eigene Provider-Quelle. */
    public ContainerRuntimes(Iterable<ContainerRuntimeProvider> source) {
        super("Container-Laufzeit", source, List::of);
    }

    /** In der App: eingebaute Provider plus die aus aktiven Plugins. */
    @Autowired
    public ContainerRuntimes(ObjectProvider<PluginManager> plugins) {
        super("Container-Laufzeit", builtin(), PluginManager.providers(plugins, ContainerRuntimeProvider.class));
    }

    private static Iterable<ContainerRuntimeProvider> builtin() {
        return ServiceLoader.load(ContainerRuntimeProvider.class, ContainerRuntimeProvider.class.getClassLoader());
    }
}
