package systems.grebe.devtools.mcp.modules.java;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

/**
 * Ablage für Diagnose-Artefakte (JFR, Heap-Dumps, Flame Graphs, Snapshots) mit einheitlichem Namensschema
 * und automatischem Aufräumen. Pro Verzeichnis gibt es genau eine Instanz, damit die UI Änderungen sieht.
 */
public final class ArtifactStore {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final Map<Path, ArtifactStore> INSTANCES = new ConcurrentHashMap<>();
    private static final List<Runnable> GLOBAL_LISTENERS = new CopyOnWriteArrayList<>();

    private final Path dir;
    private volatile int maxFiles;

    private ArtifactStore(Path dir, int maxFiles) {
        this.dir = dir;
        this.maxFiles = maxFiles;
    }

    public static ArtifactStore forDirectory(Path dir, int maxFiles) {
        Path d = dir.toAbsolutePath().normalize();
        ArtifactStore s = INSTANCES.computeIfAbsent(d, k -> new ArtifactStore(k, maxFiles));
        s.maxFiles = Math.max(5, maxFiles);
        return s;
    }

    /** Informiert bei jeder neuen/gelöschten Datei (für die UI). */
    public static void addListener(Runnable r) {
        GLOBAL_LISTENERS.add(r);
    }

    public Path dir() {
        return dir;
    }

    /** Reserviert einen neuen Dateipfad {@code <zeit>-<label>.<ext>} (Label wird auf 80 Zeichen gekürzt). */
    public Path newFile(String label, String ext) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Ablageordner nicht anlegbar: " + dir, e);
        }
        String safe = label.replaceAll("[^A-Za-z0-9._-]+", "_");
        if (safe.length() > 80) {
            safe = safe.substring(safe.length() - 80);
        }
        return dir.resolve(TS.format(LocalDateTime.now()) + "-" + safe + "." + ext);
    }

    /** Nach dem Schreiben aufrufen: räumt alte Dateien auf und benachrichtigt die UI. */
    public Path commit(Path file) {
        cleanup();
        GLOBAL_LISTENERS.forEach(Runnable::run);
        return file;
    }

    public List<Path> list() {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().startsWith(".")) // laufende Sitzungen
                    .sorted(Comparator.comparing(ArtifactStore::modified).reversed())
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * Löst einen Dateinamen oder Pfad auf. Relative Namen beziehen sich auf den Ablageordner;
     * absolute Pfade sind erlaubt, sofern die Datei existiert (z.B. eigene JFR-Dateien).
     */
    public Path resolve(String nameOrPath) {
        if (nameOrPath == null || nameOrPath.isBlank()) {
            throw new IllegalArgumentException("Dateiname fehlt");
        }
        Path p = Path.of(nameOrPath.trim());
        Path candidate = p.isAbsolute() ? p : dir.resolve(p).normalize();
        if (!p.isAbsolute() && !candidate.startsWith(dir)) {
            throw new IllegalArgumentException("Pfad außerhalb des Ablageordners: " + nameOrPath);
        }
        if (!Files.isRegularFile(candidate)) {
            throw new IllegalArgumentException("Datei nicht gefunden: " + candidate);
        }
        return candidate;
    }

    /** Neueste Datei mit Endung, optional mit Namensteil. */
    public Path latest(String ext, String contains) {
        return list().stream()
                .filter(p -> p.getFileName().toString().endsWith("." + ext))
                .filter(p -> contains == null || p.getFileName().toString().contains(contains))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Keine ." + ext + "-Datei im Ablageordner " + dir));
    }

    public void delete(Path file) throws IOException {
        if (file.toAbsolutePath().normalize().startsWith(dir)) {
            Files.deleteIfExists(file);
            deleteHeapCache(file);
            GLOBAL_LISTENERS.forEach(Runnable::run);
        }
    }

    /** VisualVM legt neben Heap-Dumps einen Index-Cache (&lt;dump&gt;.hwcache) an – mit dem Dump entfernen. */
    private static void deleteHeapCache(Path dump) {
        Path cache = dump.resolveSibling(dump.getFileName() + ".hwcache");
        if (!Files.isDirectory(cache)) {
            return;
        }
        try (Stream<Path> s = Files.walk(cache)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // noch gemappt -> beim nächsten Aufräumen
                }
            });
        } catch (IOException ignored) {
            // egal
        }
    }

    private void cleanup() {
        List<Path> files = list();
        for (int i = maxFiles; i < files.size(); i++) {
            try {
                Files.deleteIfExists(files.get(i));
                deleteHeapCache(files.get(i));
            } catch (IOException ignored) {
                // gesperrt -> nächstes Mal
            }
        }
    }

    private static long modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    public static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double v = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        int i = -1;
        do {
            v /= 1024;
            i++;
        } while (v >= 1024 && i < units.length - 1);
        return String.format("%.1f %s", v, units[i]);
    }
}
