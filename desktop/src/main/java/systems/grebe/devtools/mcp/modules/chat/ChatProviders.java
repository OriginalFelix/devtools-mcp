package systems.grebe.devtools.mcp.modules.chat;

import java.util.List;
import java.util.ServiceLoader;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.core.ProviderRegistry;
import systems.grebe.devtools.mcp.modules.chat.spi.ChatProvider;
import systems.grebe.devtools.mcp.plugin.PluginManager;

/**
 * Alle {@link ChatProvider}: die eingebauten über {@link ServiceLoader} – geladen mit dem ClassLoader der
 * SPI-Schnittstelle, damit es auch im Spring-Boot-Fat-Jar funktioniert – plus die aus aktiven Plugins.
 */
@Component
public class ChatProviders extends ProviderRegistry<ChatProvider> {

    private static final String KIND = "Chat-Provider";

    /** Nur die eingebauten Provider (Tests, Verbindungsprüfungen). */
    public ChatProviders() {
        super(KIND, ChatProvider.class, List::of);
    }

    /** Für Tests: eigene Provider-Quelle. */
    public ChatProviders(Iterable<ChatProvider> source) {
        super(KIND, source, List::of);
    }

    /** In der App: eingebaute Provider plus die aus aktiven Plugins. */
    @Autowired
    public ChatProviders(ObjectProvider<PluginManager> plugins) {
        super(KIND, ChatProvider.class, plugins);
    }
}
