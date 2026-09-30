package systems.grebe.devtools.mcp.modules.container;

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
import systems.grebe.devtools.mcp.modules.container.spi.ContainerRuntimeProvider;

/**
 * Findet alle {@link ContainerRuntimeProvider} über {@link ServiceLoader} auf dem Classpath.
 *
 * <p>Geladen wird mit dem ClassLoader der SPI-Schnittstelle – so funktioniert es auch im Spring-Boot-Fat-Jar,
 * dessen Klassen nicht im System-ClassLoader liegen.
 */
@Component
public class ContainerRuntimes {

    private static final Logger LOG = LoggerFactory.getLogger(ContainerRuntimes.class);

    private final Map<String, ContainerRuntimeProvider> providers;
    private final List<String> order;

    public ContainerRuntimes() {
        this(ServiceLoader.load(ContainerRuntimeProvider.class, ContainerRuntimeProvider.class.getClassLoader()));
    }

    /** Für Tests: eigene Provider-Quelle. */
    public ContainerRuntimes(Iterable<ContainerRuntimeProvider> source) {
        List<ContainerRuntimeProvider> found = new ArrayList<>();
        var it = source.iterator();
        while (true) {
            try {
                if (!it.hasNext()) {
                    break;
                }
                found.add(it.next());
            } catch (ServiceConfigurationError e) {
                // ein defekter Provider soll die übrigen nicht verhindern
                LOG.warn("Container-Laufzeit konnte nicht geladen werden: {}", e.getMessage());
            }
        }
        Map<String, ContainerRuntimeProvider> map = new LinkedHashMap<>();
        found.stream()
                .sorted(Comparator.comparingInt(ContainerRuntimeProvider::priority).thenComparing(ContainerRuntimeProvider::id))
                .forEach(p -> {
                    if (!p.id().matches("[a-z][a-z0-9-]*")) {
                        LOG.warn("Container-Laufzeit mit ungültiger ID ignoriert: '{}' ({})", p.id(), p.getClass().getName());
                    } else if (map.putIfAbsent(p.id(), p) != null) {
                        LOG.warn("Doppelte Container-Laufzeit-ID '{}' ignoriert: {}", p.id(), p.getClass().getName());
                    }
                });
        this.providers = Map.copyOf(map);
        this.order = List.copyOf(map.keySet());
        LOG.info("Container-Laufzeiten gefunden: {}", order);
    }

    /** Provider in Auswahlreihenfolge (Priorität, dann ID). */
    public List<ContainerRuntimeProvider> providers() {
        return order.stream().map(providers::get).toList();
    }

    public ContainerRuntimeProvider provider(String id) {
        return providers.get(id);
    }
}
