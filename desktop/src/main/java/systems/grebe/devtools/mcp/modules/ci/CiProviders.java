package systems.grebe.devtools.mcp.modules.ci;

import java.util.List;
import java.util.ServiceLoader;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.core.ProviderRegistry;
import systems.grebe.devtools.mcp.modules.ci.spi.CiProvider;
import systems.grebe.devtools.mcp.plugin.PluginManager;

/**
 * Alle {@link CiProvider}: die eingebauten über {@link ServiceLoader} – geladen mit dem ClassLoader der
 * SPI-Schnittstelle, damit es auch im Spring-Boot-Fat-Jar funktioniert – plus die aus aktiven Plugins.
 */
@Component
public class CiProviders extends ProviderRegistry<CiProvider> {

    /** Nur die eingebauten Provider (Tests, Verbindungsprüfungen). */
    public CiProviders() {
        this(builtin());
    }

    /** Für Tests: eigene Provider-Quelle. */
    public CiProviders(Iterable<CiProvider> source) {
        super("CI-Provider", source, List::of);
    }

    /** In der App: eingebaute Provider plus die aus aktiven Plugins. */
    @Autowired
    public CiProviders(ObjectProvider<PluginManager> plugins) {
        super("CI-Provider", builtin(), PluginManager.providers(plugins, CiProvider.class));
    }

    private static Iterable<CiProvider> builtin() {
        return ServiceLoader.load(CiProvider.class, CiProvider.class.getClassLoader());
    }
}
