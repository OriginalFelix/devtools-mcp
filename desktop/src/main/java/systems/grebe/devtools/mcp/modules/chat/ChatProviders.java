package systems.grebe.devtools.mcp.modules.chat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.modules.chat.spi.ChatProvider;

/**
 * Findet alle {@link ChatProvider} über {@link ServiceLoader} – geladen mit dem ClassLoader der SPI-Schnittstelle,
 * damit es auch im Spring-Boot-Fat-Jar funktioniert (wie {@code TicketProviders}).
 */
@Component
public class ChatProviders {

    private static final Logger LOG = LoggerFactory.getLogger(ChatProviders.class);

    private final Map<String, ChatProvider> providers;
    private final List<String> order;

    public ChatProviders() {
        this(ServiceLoader.load(ChatProvider.class, ChatProvider.class.getClassLoader()));
    }

    /** Für Tests: eigene Provider-Quelle. */
    public ChatProviders(Iterable<ChatProvider> source) {
        List<ChatProvider> found = new ArrayList<>();
        var it = source.iterator();
        while (true) {
            try {
                if (!it.hasNext()) {
                    break;
                }
                found.add(it.next());
            } catch (ServiceConfigurationError e) {
                // ein defekter Provider soll die übrigen nicht verhindern
                LOG.warn("Chat-Provider konnte nicht geladen werden: {}", e.getMessage());
            }
        }
        Map<String, ChatProvider> map = new LinkedHashMap<>();
        found.stream()
                .sorted(Comparator.comparingInt(ChatProvider::priority).thenComparing(ChatProvider::id))
                .forEach(p -> {
                    if (!p.id().matches("[a-z][a-z0-9-]*")) {
                        LOG.warn("Chat-Provider mit ungültiger ID ignoriert: '{}' ({})", p.id(), p.getClass().getName());
                    } else if (map.putIfAbsent(p.id(), p) != null) {
                        LOG.warn("Doppelte Chat-Provider-ID '{}' ignoriert: {}", p.id(), p.getClass().getName());
                    }
                });
        this.providers = Map.copyOf(map);
        this.order = List.copyOf(map.keySet());
        LOG.info("Chat-Provider gefunden: {}", order);
    }

    /** Provider in Reihenfolge (Priorität, dann ID). */
    public List<ChatProvider> providers() {
        return order.stream().map(providers::get).toList();
    }

    public ChatProvider provider(String id) {
        return providers.get(id);
    }
}
