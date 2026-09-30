package systems.grebe.devtools.mcp.plugin.store;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import systems.grebe.devtools.mcp.config.PluginSettings;
import systems.grebe.devtools.mcp.config.SettingsStore;
import systems.grebe.devtools.mcp.core.ManagedToolCallback;
import systems.grebe.devtools.mcp.plugin.PluginManager;

/**
 * Plugin-Store über Maven-Repositories: durchsucht die Kataloge der eingetragenen Repositories, zeigt Versionen
 * (aus {@code maven-metadata.xml}), installiert und aktualisiert Plugins. Ein Plugin ist ein gewöhnliches
 * Jar-Artefakt mit {@code plugin.yml}; ohne Katalog lässt es sich direkt über {@code groupId:artifactId[:version]}
 * installieren. Alle Methoden blockieren (Netzwerk) – nicht im UI-Thread aufrufen.
 */
public class PluginStore {

    private static final Logger LOG = LoggerFactory.getLogger(PluginStore.class);
    private static final Pattern COORDS = Pattern.compile("([^:\\s]+):([^:\\s]+)(?::([^:\\s]+))?");

    /** Ergebnis einer Katalogabfrage; {@code errors}: Repository-ID → Meldung für nicht erreichbare Kataloge. */
    public record CatalogResult(List<PluginCatalog.Entry> entries, Map<String, String> errors) {
    }

    /** Verfügbares Update eines über den Store installierten Plugins. */
    public record Update(String plugin, String installedVersion, String latestVersion, String coordinates) {
    }

    private final SettingsStore store;
    private final PluginManager manager;
    private final MavenPluginResolver resolver;

    public PluginStore(SettingsStore store, PluginManager manager, MavenPluginResolver resolver) {
        this.store = store;
        this.manager = manager;
        this.resolver = resolver;
    }

    // ------------------------------------------------------------------ Repositories

    public List<PluginRepository> repositories() {
        return store.plugins().repositories();
    }

    /** Fügt ein Repository hinzu oder ersetzt das mit gleicher ID (leeres Passwort = bisheriges behalten). */
    public void saveRepository(PluginRepository repo) {
        List<String> errors = repo.validate();
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("\n", errors));
        }
        PluginSettings s = store.plugins();
        List<PluginRepository> list = s.mutableRepositories();
        int idx = indexOf(list, repo.id());
        PluginRepository toSave = repo;
        if (idx >= 0 && repo.password().isEmpty() && repo.hasCredentials()
                && list.get(idx).username().equals(repo.username())) {
            PluginRepository old = list.get(idx);
            toSave = new PluginRepository(repo.id(), repo.name(), repo.url(), repo.username(), old.password(),
                    repo.snapshots(), repo.catalog(), repo.enabled());
        }
        if (idx >= 0) {
            list.set(idx, toSave);
        } else {
            list.add(toSave);
        }
        store.savePlugins(s.withRepositories(list));
    }

    public void removeRepository(String id) {
        PluginSettings s = store.plugins();
        List<PluginRepository> list = s.mutableRepositories();
        list.removeIf(r -> r.id().equals(id));
        store.savePlugins(s.withRepositories(list));
    }

    /** Verschiebt ein Repository in der Suchreihenfolge ({@code -1} nach oben, {@code +1} nach unten). */
    public void moveRepository(String id, int delta) {
        PluginSettings s = store.plugins();
        List<PluginRepository> list = s.mutableRepositories();
        int idx = indexOf(list, id);
        int to = idx + delta;
        if (idx < 0 || to < 0 || to >= list.size()) {
            return;
        }
        list.add(to, list.remove(idx));
        store.savePlugins(s.withRepositories(list));
    }

    /** Prüft ein (evtl. noch ungespeichertes) Repository. */
    public String testRepository(PluginRepository repo) {
        List<String> errors = repo.validate();
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("\n", errors));
        }
        return resolver.check(repo);
    }

    // ------------------------------------------------------------------ Katalog und Installation

    /** Alle Katalogeinträge der aktiven Repositories mit Katalog; doppelte Koordinaten nur einmal (erstes Repo). */
    public CatalogResult catalog() {
        Map<String, PluginCatalog.Entry> entries = new LinkedHashMap<>();
        Map<String, String> errors = new LinkedHashMap<>();
        for (PluginRepository repo : repositories()) {
            if (!repo.enabled() || !repo.hasCatalog()) {
                continue;
            }
            try {
                Path file = resolver.resolveCatalog(repo);
                for (PluginCatalog.Entry e : PluginCatalog.read(file, repo.id())) {
                    entries.putIfAbsent(e.coordinates(), e);
                }
            } catch (RuntimeException e) {
                LOG.warn("Katalog von {} nicht ladbar", repo.id(), e);
                errors.put(repo.id(), ManagedToolCallback.describe(e));
            }
        }
        return new CatalogResult(List.copyOf(entries.values()), errors);
    }

    /** Katalogeinträge, die zur Suche passen. */
    public CatalogResult search(String query) {
        CatalogResult all = catalog();
        return new CatalogResult(all.entries().stream().filter(e -> e.matches(query)).toList(), all.errors());
    }

    /** Versionen eines Plugins, neueste zuerst; SNAPSHOTs nur aus Repositories mit Snapshot-Freigabe. */
    public List<String> versions(String groupId, String artifactId) {
        return resolver.versions(groupId, artifactId);
    }

    /**
     * Lädt und installiert ein Plugin: {@code groupId:artifactId} (neueste Version) oder
     * {@code groupId:artifactId:version}.
     */
    public PluginManager.PluginInfo install(String coordinates) {
        var m = COORDS.matcher(coordinates == null ? "" : coordinates.strip());
        if (!m.matches()) {
            throw new IllegalArgumentException("Koordinate muss groupId:artifactId oder groupId:artifactId:version sein.");
        }
        String version = m.group(3);
        if (version == null || version.isBlank()) {
            version = resolver.latestVersion(m.group(1), m.group(2));
            if (version == null) {
                throw new IllegalStateException(m.group(1) + ":" + m.group(2) + " liegt in keinem aktiven Repository.");
            }
        }
        String gav = m.group(1) + ":" + m.group(2) + ":" + version;
        Path jar = resolver.resolve(gav);
        return manager.install(jar, gav);
    }

    /** Updates für alle über den Store installierten Plugins (Repositories nicht erreichbar → übersprungen). */
    public List<Update> updates() {
        List<Update> out = new ArrayList<>();
        for (PluginManager.PluginInfo p : manager.plugins()) {
            if (p.source() == null || !p.valid()) {
                continue;
            }
            String[] gav = p.source().split(":");
            if (gav.length < 3) {
                continue;
            }
            try {
                String latest = resolver.latestVersion(gav[0], gav[1]);
                if (latest != null && !latest.equals(gav[2]) && isNewer(latest, gav[2])) {
                    out.add(new Update(p.name(), gav[2], latest, gav[0] + ":" + gav[1] + ":" + latest));
                }
            } catch (RuntimeException e) {
                LOG.warn("Update-Prüfung für {} fehlgeschlagen: {}", p.name(), e.getMessage());
            }
        }
        return out;
    }

    /** Installiertes Plugin, das aus diesen Koordinaten stammt. */
    public Optional<PluginManager.PluginInfo> installed(String groupId, String artifactId) {
        String prefix = groupId + ":" + artifactId + ":";
        return manager.plugins().stream().filter(p -> p.source() != null && p.source().startsWith(prefix)).findFirst();
    }

    static boolean isNewer(String candidate, String current) {
        var scheme = new org.eclipse.aether.util.version.GenericVersionScheme();
        try {
            return scheme.parseVersion(candidate).compareTo(scheme.parseVersion(current)) > 0;
        } catch (org.eclipse.aether.version.InvalidVersionSpecificationException e) {
            return false;
        }
    }

    private static int indexOf(List<PluginRepository> list, String id) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }
}
