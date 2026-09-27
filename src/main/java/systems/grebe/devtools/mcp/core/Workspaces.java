package systems.grebe.devtools.mcp.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Löst konfigurierte Verzeichnisse zu Arbeitsverzeichnissen (Git-Repositories, Build-Projekte) auf und
 * stellt sicher, dass Tools nur auf freigegebene Verzeichnisse zugreifen.
 *
 * <p>Ein konfigurierter Eintrag ist entweder selbst ein Arbeitsverzeichnis (erfüllt {@code marker}) oder ein
 * Sammelordner – dann werden seine direkten Unterordner, die den Marker erfüllen, übernommen.
 */
public final class Workspaces {

    private final Map<String, Path> byName = new LinkedHashMap<>();
    private final String kind;

    public Workspaces(List<String> configured, Predicate<Path> marker, String kind) {
        this.kind = kind;
        for (String entry : configured) {
            Path dir;
            try {
                dir = Path.of(entry).toAbsolutePath().normalize();
            } catch (InvalidPathException e) {
                continue;
            }
            if (!Files.isDirectory(dir)) {
                continue;
            }
            if (marker.test(dir)) {
                add(dir);
            } else {
                try (Stream<Path> children = Files.list(dir)) {
                    children.filter(Files::isDirectory).filter(marker)
                            .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                            .forEach(this::add);
                } catch (IOException ignored) {
                    // nicht lesbar -> überspringen
                }
            }
        }
    }

    private void add(Path dir) {
        String name = dir.getFileName() == null ? dir.toString() : dir.getFileName().toString();
        if (byName.containsKey(name) && !byName.get(name).equals(dir)) {
            name = dir.toString();
        }
        byName.putIfAbsent(name, dir);
    }

    public Map<String, Path> all() {
        return byName;
    }

    public boolean isEmpty() {
        return byName.isEmpty();
    }

    /**
     * Löst einen Namen oder Pfad auf. Ohne Angabe wird {@code defaultName} verwendet bzw. das einzige
     * konfigurierte Verzeichnis.
     */
    public Path resolve(String nameOrPath, String defaultName) {
        if (byName.isEmpty()) {
            throw new IllegalStateException("Keine " + kind + " konfiguriert. Bitte in der DevTools-MCP-App eintragen.");
        }
        String wanted = nameOrPath == null || nameOrPath.isBlank() ? defaultName : nameOrPath.trim();
        if (wanted == null || wanted.isBlank()) {
            if (byName.size() == 1) {
                return byName.values().iterator().next();
            }
            throw new IllegalArgumentException("Mehrere " + kind + " verfügbar, bitte eines angeben: " + byName.keySet());
        }
        Path hit = byName.get(wanted);
        if (hit != null) {
            return hit;
        }
        for (var e : byName.entrySet()) {
            if (e.getKey().equalsIgnoreCase(wanted)) {
                return e.getValue();
            }
        }
        try {
            Path p = Path.of(wanted).toAbsolutePath().normalize();
            for (Path candidate : byName.values()) {
                if (p.equals(candidate) || p.startsWith(candidate)) {
                    return candidate;
                }
            }
        } catch (InvalidPathException ignored) {
            // kein Pfad
        }
        throw new IllegalArgumentException("'" + wanted + "' ist nicht freigegeben. Verfügbar: " + byName.keySet());
    }

    /** Prüft einen relativen Pfad innerhalb eines Arbeitsverzeichnisses und liefert ihn mit '/' getrennt. */
    public static String relativePath(Path root, String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Pfad fehlt");
        }
        String p = path.trim().replace('\\', '/');
        Path resolved;
        try {
            Path raw = Path.of(p);
            resolved = (raw.isAbsolute() ? raw : root.resolve(p)).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("Ungültiger Pfad: " + path);
        }
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Pfad liegt außerhalb von " + root + ": " + path);
        }
        String rel = root.relativize(resolved).toString().replace('\\', '/');
        return rel.isEmpty() ? "." : rel;
    }

    public List<String> describe() {
        List<String> out = new ArrayList<>();
        byName.forEach((k, v) -> out.add(k + " -> " + v));
        return out;
    }
}
