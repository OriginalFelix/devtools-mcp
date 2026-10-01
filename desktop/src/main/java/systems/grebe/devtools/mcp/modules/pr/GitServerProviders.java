package systems.grebe.devtools.mcp.modules.pr;

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
import systems.grebe.devtools.mcp.modules.pr.spi.GitServerProvider;

/**
 * Findet alle {@link GitServerProvider} über {@link ServiceLoader} – geladen mit dem ClassLoader der
 * SPI-Schnittstelle, damit es auch im Spring-Boot-Fat-Jar funktioniert (wie {@code TicketProviders}).
 */
@Component
public class GitServerProviders {

    private static final Logger LOG = LoggerFactory.getLogger(GitServerProviders.class);

    private final Map<String, GitServerProvider> providers;
    private final List<String> order;

    public GitServerProviders() {
        this(ServiceLoader.load(GitServerProvider.class, GitServerProvider.class.getClassLoader()));
    }

    /** Für Tests: eigene Provider-Quelle. */
    public GitServerProviders(Iterable<GitServerProvider> source) {
        List<GitServerProvider> found = new ArrayList<>();
        var it = source.iterator();
        while (true) {
            try {
                if (!it.hasNext()) {
                    break;
                }
                found.add(it.next());
            } catch (ServiceConfigurationError e) {
                // ein defekter Provider soll die übrigen nicht verhindern
                LOG.warn("Git-Server-Provider konnte nicht geladen werden: {}", e.getMessage());
            }
        }
        Map<String, GitServerProvider> map = new LinkedHashMap<>();
        found.stream()
                .sorted(Comparator.comparingInt(GitServerProvider::priority).thenComparing(GitServerProvider::id))
                .forEach(p -> {
                    if (!p.id().matches("[a-z][a-z0-9-]*")) {
                        LOG.warn("Git-Server-Provider mit ungültiger ID ignoriert: '{}' ({})", p.id(), p.getClass().getName());
                    } else if (map.putIfAbsent(p.id(), p) != null) {
                        LOG.warn("Doppelte Git-Server-Provider-ID '{}' ignoriert: {}", p.id(), p.getClass().getName());
                    }
                });
        this.providers = Map.copyOf(map);
        this.order = List.copyOf(map.keySet());
        LOG.info("Git-Server-Provider gefunden: {}", order);
    }

    /** Provider in Auswahlreihenfolge (Priorität, dann ID). */
    public List<GitServerProvider> providers() {
        return order.stream().map(providers::get).toList();
    }

    public GitServerProvider provider(String id) {
        return providers.get(id);
    }
}
