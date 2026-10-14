package systems.grebe.devtools.mcp.modules.ide;

import java.util.List;
import java.util.ServiceLoader;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.core.ProviderRegistry;
import systems.grebe.devtools.mcp.modules.ide.spi.IdeProvider;
import systems.grebe.devtools.mcp.plugin.PluginManager;

/**
 * Alle {@link IdeProvider}: die eingebauten über {@link ServiceLoader} – geladen mit dem ClassLoader der
 * SPI-Schnittstelle, damit es auch im Spring-Boot-Fat-Jar funktioniert – plus die aus aktiven Plugins.
 */
@Component
public class IdeProviders extends ProviderRegistry<IdeProvider> {

    /** Nur die eingebauten Provider (Tests, Verbindungsprüfungen). */
    public IdeProviders() {
        this(builtin());
    }

    /** Für Tests: eigene Provider-Quelle. */
    public IdeProviders(Iterable<IdeProvider> source) {
        super("IDE-Provider", source, List::of);
    }

    /** In der App: eingebaute Provider plus die aus aktiven Plugins. */
    @Autowired
    public IdeProviders(ObjectProvider<PluginManager> plugins) {
        super("IDE-Provider", builtin(), PluginManager.providers(plugins, IdeProvider.class));
    }

    private static Iterable<IdeProvider> builtin() {
        return ServiceLoader.load(IdeProvider.class, IdeProvider.class.getClassLoader());
    }
}
