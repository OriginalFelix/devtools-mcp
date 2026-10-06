package systems.grebe.devtools.mcp.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import systems.grebe.devtools.mcp.plugin.store.PluginRepository;

/**
 * Persistierter Zustand des Plugin-Systems.
 *
 * @param disabled     Namen von Plugins, die der Benutzer abgeschaltet hat (Jar bleibt liegen, wird nicht geladen)
 * @param repositories Maven-Repositories des Plugin-Stores (Reihenfolge = Suchreihenfolge)
 * @param sources      Herkunft über den Store installierter Plugins: Name → {@code groupId:artifactId:version}
 * @param trustedKeys  öffentliche Schlüssel (PEM), gegen die Plugin-Signaturen ({@code plugin.jwt}) geprüft werden
 */
public record PluginSettings(Set<String> disabled, List<PluginRepository> repositories, Map<String, String> sources,
                             List<String> trustedKeys) {

    public PluginSettings {
        disabled = disabled == null ? Set.of() : Set.copyOf(disabled);
        repositories = repositories == null ? List.of() : List.copyOf(repositories);
        sources = sources == null ? Map.of() : Map.copyOf(sources);
        trustedKeys = trustedKeys == null ? List.of() : List.copyOf(trustedKeys);
    }

    /** Erster Start: nur Maven Central, keine vertrauenswürdigen Schlüssel. */
    public static PluginSettings defaults() {
        return new PluginSettings(Set.of(), List.of(PluginRepository.central()), Map.of(), List.of());
    }

    public PluginSettings withDisabled(Set<String> value) {
        return new PluginSettings(value, repositories, sources, trustedKeys);
    }

    public PluginSettings withRepositories(List<PluginRepository> value) {
        return new PluginSettings(disabled, value, sources, trustedKeys);
    }

    public PluginSettings withTrustedKeys(List<String> value) {
        return new PluginSettings(disabled, repositories, sources, value);
    }

    public PluginSettings withSource(String plugin, String coordinates) {
        Map<String, String> m = new LinkedHashMap<>(sources);
        if (coordinates == null) {
            m.remove(plugin);
        } else {
            m.put(plugin, coordinates);
        }
        return new PluginSettings(disabled, repositories, m, trustedKeys);
    }

    public List<PluginRepository> mutableRepositories() {
        return new ArrayList<>(repositories);
    }
}
