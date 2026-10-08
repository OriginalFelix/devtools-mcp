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

    private static final String KIND = "Ticket-Provider";

    /** Nur die eingebauten Provider (Tests, Verbindungsprüfungen). */
    public TicketProviders() {
        super(KIND, TicketProvider.class, List::of);
    }

    /** Für Tests: eigene Provider-Quelle. */
    public TicketProviders(Iterable<TicketProvider> source) {
        super(KIND, source, List::of);
    }

    /** In der App: eingebaute Provider plus die aus aktiven Plugins. */
    @Autowired
    public TicketProviders(ObjectProvider<PluginManager> plugins) {
        super(KIND, TicketProvider.class, plugins);
    }
}
