package systems.grebe.devtools.mcp.modules.pr;

import java.util.List;
import java.util.ServiceLoader;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.core.ProviderRegistry;
import systems.grebe.devtools.mcp.modules.pr.spi.GitServerProvider;
import systems.grebe.devtools.mcp.plugin.PluginManager;

/**
 * Alle {@link GitServerProvider}: die eingebauten über {@link ServiceLoader} – geladen mit dem ClassLoader der
 * SPI-Schnittstelle, damit es auch im Spring-Boot-Fat-Jar funktioniert – plus die aus aktiven Plugins.
 */
@Component
public class GitServerProviders extends ProviderRegistry<GitServerProvider> {

    private static final String KIND = "Git-Server-Provider";

    /** Nur die eingebauten Provider (Tests, Verbindungsprüfungen). */
    public GitServerProviders() {
        super(KIND, GitServerProvider.class, List::of);
    }

    /** Für Tests: eigene Provider-Quelle. */
    public GitServerProviders(Iterable<GitServerProvider> source) {
        super(KIND, source, List::of);
    }

    /** In der App: eingebaute Provider plus die aus aktiven Plugins. */
    @Autowired
    public GitServerProviders(ObjectProvider<PluginManager> plugins) {
        super(KIND, GitServerProvider.class, plugins);
    }
}
