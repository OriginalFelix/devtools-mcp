package systems.grebe.devtools.mcp.plugin.store;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Plugin-Katalog eines Repositories: ein YAML-Artefakt (Extension {@code yml}), das wie jedes andere Artefakt ins
 * Repository deployt wird, z.B. {@code com.acme:devtools-plugins:3:yml}. Der Store lädt immer die neueste Version.
 *
 * <pre>{@code
 * plugins:
 *   - coordinates: com.acme.devtools:jira-plugin     # groupId:artifactId, Pflicht
 *     name: Jira                                     # optional, sonst artifactId
 *     description: Tickets lesen und kommentieren
 *     author: Team Tools
 *     tags: [ticket, atlassian]
 * }</pre>
 */
public final class PluginCatalog {

    /** Ein Eintrag; {@code repositoryId} ist das Repository, aus dem der Katalog stammt. */
    public record Entry(String groupId, String artifactId, String name, String description, String author,
                        List<String> tags, String repositoryId) {

        public Entry {
            tags = List.copyOf(tags == null ? List.of() : tags);
        }

        public String coordinates() {
            return groupId + ":" + artifactId;
        }

        /** Treffer für eine Suche über Name, Koordinaten, Beschreibung, Autor und Tags (Groß/klein egal). */
        public boolean matches(String query) {
            if (query == null || query.isBlank()) {
                return true;
            }
            String q = query.strip().toLowerCase();
            return (name + " " + coordinates() + " " + description + " " + author + " " + String.join(" ", tags))
                    .toLowerCase().contains(q);
        }
    }

    private PluginCatalog() {
    }

    public static List<Entry> read(Path file, String repositoryId) {
        try (InputStream in = Files.newInputStream(file)) {
            return parse(in, repositoryId);
        } catch (IOException e) {
            throw new UncheckedIOException("Katalog nicht lesbar: " + file, e);
        }
    }

    public static List<Entry> parse(InputStream yaml, String repositoryId) {
        Object root;
        try {
            root = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
        } catch (YAMLException e) {
            throw new IllegalArgumentException("Katalog ist kein gültiges YAML: " + e.getMessage(), e);
        }
        if (!(root instanceof Map<?, ?> map) || !(map.get("plugins") instanceof Collection<?> items)) {
            throw new IllegalArgumentException("Katalog braucht eine Liste 'plugins'.");
        }
        List<Entry> entries = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> e)) {
                continue;
            }
            String coords = str(e.get("coordinates"));
            String[] parts = coords.split(":");
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                continue; // fehlerhafte Einträge überspringen, den Rest anzeigen
            }
            List<String> tags = e.get("tags") instanceof Collection<?> c
                    ? c.stream().map(PluginCatalog::str).filter(s -> !s.isEmpty()).toList() : List.of();
            String name = str(e.get("name"));
            entries.add(new Entry(parts[0], parts[1], name.isEmpty() ? parts[1] : name, str(e.get("description")),
                    str(e.get("author")), tags, repositoryId));
        }
        return entries;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).strip();
    }
}
