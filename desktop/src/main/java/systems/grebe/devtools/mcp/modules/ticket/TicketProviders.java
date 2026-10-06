package systems.grebe.devtools.mcp.modules.ticket;

import java.util.List;
import java.util.ServiceLoader;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.core.ProviderRegistry;
import systems.grebe.devtools.mcp.modules.ticket.spi.TicketProvider;
import systems.grebe.devtools.mcp.plugin.PluginManager;

/**
 * Alle {@link TicketProvider}: die eingebauten über {@link ServiceLoader} – geladen mit dem ClassLoader der
 * SPI-Schnittstelle, damit es auch im Spring-Boot-Fat-Jar funktioniert – plus die aus aktiven Plugins.
 */
@Component
public class TicketProviders extends ProviderRegistry<TicketProvider> {

    /** Nur die eingebauten Provider (Tests, Verbindungsprüfungen). */
    public TicketProviders() {
        this(builtin());
    }

    /** Für Tests: eigene Provider-Quelle. */
    public TicketProviders(Iterable<TicketProvider> source) {
        super("Ticket-Provider", source, List::of);
    }

    /** In der App: eingebaute Provider plus die aus aktiven Plugins. */
    @Autowired
    public TicketProviders(ObjectProvider<PluginManager> plugins) {
        super("Ticket-Provider", builtin(), PluginManager.providers(plugins, TicketProvider.class));
    }

    private static Iterable<TicketProvider> builtin() {
        return ServiceLoader.load(TicketProvider.class, TicketProvider.class.getClassLoader());
    }
}
