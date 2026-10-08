package systems.grebe.devtools.mcp.plugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import static systems.grebe.devtools.mcp.plugin.PluginDescriptor.FILE_NAME;

/** Liest und prüft die {@code plugin.yml} eines Plugins (Format siehe {@link PluginDescriptor}). */
public final class PluginDescriptorReader {

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,63}");
    private static final Pattern CLASS_NAME = Pattern.compile("[\\p{L}_$][\\p{L}\\p{N}_$]*(\\.[\\p{L}_$][\\p{L}\\p{N}_$]*)+");
    private static final Pattern COORDINATES = Pattern.compile("[^:\\s]+:[^:\\s]+(:[^:\\s]+){1,3}");

    private PluginDescriptorReader() {
    }

    /** Liest und prüft die {@code plugin.yml} eines Jars. */
    public static PluginDescriptor read(Path jar) {
        try (JarFile file = new JarFile(jar.toFile())) {
            ZipEntry entry = file.getEntry(FILE_NAME);
            if (entry == null) {
                throw new InvalidPluginException(jar.getFileName() + " enthält keine " + FILE_NAME
                        + " – kein DevTools-Plugin.");
            }
            try (InputStream in = file.getInputStream(entry)) {
                return parse(in);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Plugin-Jar nicht lesbar: " + jar, e);
        }
    }

    /** Parst und prüft eine {@code plugin.yml}. */
    public static PluginDescriptor parse(InputStream yaml) {
        Object loaded;
        try {
            loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
        } catch (YAMLException e) {
            throw new InvalidPluginException(FILE_NAME + " ist kein gültiges YAML: " + e.getMessage());
        }
        if (!(loaded instanceof Map<?, ?> map)) {
            throw new InvalidPluginException(FILE_NAME + " ist leer oder keine Zuordnung (key: value).");
        }
        String name = required(map, "name");
        if (!NAME.matcher(name).matches()) {
            throw new InvalidPluginException("Plugin-Name '" + name + "' ungültig: erlaubt sind Kleinbuchstaben, "
                    + "Ziffern und '-', beginnend mit einem Buchstaben.");
        }
        String main = required(map, "main");
        if (!CLASS_NAME.matcher(main).matches()) {
            throw new InvalidPluginException("'main' muss ein voll qualifizierter Klassenname sein: " + main);
        }
        if (main.startsWith("systems.grebe.devtools.mcp.")) {
            throw new InvalidPluginException("'main' darf nicht im Paket der Anwendung liegen: " + main);
        }
        int api = 1;
        Object apiRaw = map.get("api-version");
        if (apiRaw != null) {
            try {
                api = Integer.parseInt(String.valueOf(apiRaw).trim());
            } catch (NumberFormatException e) {
                throw new InvalidPluginException("'api-version' muss eine ganze Zahl sein: " + apiRaw);
            }
        }
        List<String> authors = new ArrayList<>(list(map, "authors"));
        String author = optional(map, "author");
        if (author != null) {
            authors.addFirst(author);
        }
        List<String> libraries = list(map, "libraries");
        for (String lib : libraries) {
            if (!COORDINATES.matcher(lib).matches()) {
                throw new InvalidPluginException("Bibliothek '" + lib + "' ist keine Maven-Koordinate "
                        + "(groupId:artifactId:version).");
            }
        }
        return new PluginDescriptor(name, required(map, "version"), main, api, optional(map, "description"),
                authors, optional(map, "website"), names(map, "depend", name), names(map, "softdepend", name),
                libraries);
    }

    private static String required(Map<?, ?> map, String key) {
        String v = optional(map, key);
        if (v == null) {
            throw new InvalidPluginException(FILE_NAME + ": Pflichtfeld '" + key + "' fehlt.");
        }
        return v;
    }

    private static String optional(Map<?, ?> map, String key) {
        Object v = map.get(key);
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).strip();
        return s.isEmpty() ? null : s;
    }

    private static List<String> list(Map<?, ?> map, String key) {
        Object v = map.get(key);
        if (v == null) {
            return List.of();
        }
        Collection<?> items = v instanceof Collection<?> c ? c : List.of(v);
        return items.stream().filter(java.util.Objects::nonNull).map(o -> String.valueOf(o).strip())
                .filter(s -> !s.isEmpty()).toList();
    }

    private static List<String> names(Map<?, ?> map, String key, String self) {
        List<String> names = list(map, key);
        for (String n : names) {
            if (n.equals(self)) {
                throw new InvalidPluginException("'" + key + "' darf das Plugin selbst nicht enthalten.");
            }
        }
        return names.stream().distinct().toList();
    }
}
