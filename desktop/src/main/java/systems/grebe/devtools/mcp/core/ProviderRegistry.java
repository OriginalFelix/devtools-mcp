package systems.grebe.devtools.mcp.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provider einer SPI ({@link ServiceProvider}): die eingebauten (einmal über den {@link java.util.ServiceLoader}
 * geladen) plus die aus aktiven Plugins, sortiert nach Priorität und ID. Ungültige und doppelte IDs werden mit
 * Warnung ignoriert; eingebaute Provider haben bei gleicher ID Vorrang.
 *
 * <p>Die Plugin-Provider werden bei jedem Zugriff abgefragt – so erscheinen sie, sobald ein Plugin aktiv wird, und
 * verschwinden mit ihm. Der Lieferant muss dafür bei unverändertem Stand dieselbe Liste liefern (Identität), dann
 * wird nur einmal zusammengeführt und gewarnt.
 */
public class ProviderRegistry<T extends ServiceProvider> {

    private static final Logger LOG = LoggerFactory.getLogger(ProviderRegistry.class);

    private final String kind;
    private final List<T> builtin;
    private final Supplier<List<T>> plugins;
    private volatile Merged<T> merged;

    private record Merged<T>(List<T> source, Map<String, T> byId, List<T> ordered) {
    }

    /**
     * @param kind    Bezeichnung für das Log, z.B. „Ticket-Provider“
     * @param source  eingebaute Provider (z.B. {@code ServiceLoader.load(…)}); defekte werden übersprungen
     * @param plugins Provider aus aktiven Plugins
     */
    protected ProviderRegistry(String kind, Iterable<T> source, Supplier<List<T>> plugins) {
        this.kind = kind;
        this.builtin = load(kind, source);
        this.plugins = plugins;
        this.merged = merge(List.of());
        LOG.info("{} gefunden: {}", kind, merged.byId.keySet());
    }

    /** Provider in Auswahlreihenfolge (Priorität, dann ID). */
    public List<T> providers() {
        return current().ordered;
    }

    public T provider(String id) {
        return current().byId.get(id);
    }

    private Merged<T> current() {
        List<T> fromPlugins = plugins.get();
        Merged<T> m = merged;
        if (m.source != fromPlugins) {
            m = merge(fromPlugins);
            merged = m;
            if (!fromPlugins.isEmpty()) {
                LOG.info("{} mit Plugins: {}", kind, m.byId.keySet());
            }
        }
        return m;
    }

    private Merged<T> merge(List<T> fromPlugins) {
        Map<String, T> map = new LinkedHashMap<>();
        builtin.forEach(p -> map.put(p.id(), p));
        for (T p : fromPlugins) {
            if (!valid(p)) {
                LOG.warn("{} aus Plugin mit ungültiger ID ignoriert: '{}' ({})", kind, p.id(), className(p));
            } else if (map.putIfAbsent(p.id(), p) != null) {
                LOG.warn("{}-ID '{}' aus Plugin ist schon vergeben – ignoriert: {}", kind, p.id(),
                        className(p));
            }
        }
        List<T> ordered = map.values().stream()
                .sorted(Comparator.comparingInt(T::priority).thenComparing(T::id)).toList();
        return new Merged<>(fromPlugins, Map.copyOf(map), ordered);
    }

    private static <T extends ServiceProvider> List<T> load(String kind, Iterable<T> source) {
        List<T> found = new ArrayList<>();
        var it = source.iterator();
        while (true) {
            try {
                if (!it.hasNext()) {
                    break;
                }
                found.add(it.next());
            } catch (ServiceConfigurationError e) {
                // ein defekter Provider soll die übrigen nicht verhindern
                LOG.warn("{} konnte nicht geladen werden: {}", kind, e.getMessage());
            }
        }
        Map<String, T> map = new LinkedHashMap<>();
        found.stream().sorted(Comparator.comparingInt(T::priority).thenComparing(T::id)).forEach(p -> {
            if (!valid(p)) {
                LOG.warn("{} mit ungültiger ID ignoriert: '{}' ({})", kind, p.id(), className(p));
            } else if (map.putIfAbsent(p.id(), p) != null) {
                LOG.warn("Doppelte {}-ID '{}' ignoriert: {}", kind, p.id(), className(p));
            }
        });
        return List.copyOf(map.values());
    }

    /** Klasse des Providers – bei Plugin-Providern die echte, nicht die der {@link ContextLoaderProxy Hülle}. */
    private static String className(ServiceProvider p) {
        return ContextLoaderProxy.unwrap(p).getClass().getName();
    }

    private static boolean valid(ServiceProvider p) {
        return p.id() != null && p.id().matches("[a-z][a-z0-9-]*");
    }
}
