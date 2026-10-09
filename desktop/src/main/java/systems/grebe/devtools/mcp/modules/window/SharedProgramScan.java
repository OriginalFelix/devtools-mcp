package systems.grebe.devtools.mcp.modules.window;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Sucht in den global freigegebenen Ordnern rekursiv nach Programmen ({@code .exe} unter Windows, {@code .app}-Bundles
 * unter macOS, sonst Dateien mit Ausführungsrecht) – für die Warnung beim Speichern. Build- und Werkzeugordner werden
 * übersprungen, nach {@link #LIMIT} Einträgen bricht die Suche ab.
 */
final class SharedProgramScan {

    static final int LIMIT = 50_000;
    /** So viele Programmnamen zeigt {@link #describe} je Ordner, danach nur die Anzahl. */
    static final int NAMES_PER_LINE = 10;
    /** So viele Ordnerzeilen zeigt {@link #describe} insgesamt. */
    static final int FOLDERS = 15;
    static final Set<String> SKIPPED = Set.of(".git", "node_modules", "build", "target", ".gradle", ".idea", "out", "dist");
    private static final boolean WINDOWS = File.separatorChar == '\\';

    /** @param complete {@code false}, wenn die Obergrenze erreicht wurde */
    record Result(List<Path> programs, boolean complete) {
    }

    private SharedProgramScan() {
    }

    static Result scan(List<Path> roots) {
        return scan(roots, LIMIT);
    }

    static Result scan(List<Path> roots, int limit) {
        List<Path> found = new ArrayList<>();
        int[] seen = {0};
        boolean[] cut = {false};
        for (Path root : roots) {
            if (cut[0] || !Files.isDirectory(root)) {
                continue;
            }
            try {
                Files.walkFileTree(root, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (++seen[0] > limit) {
                            cut[0] = true;
                            return FileVisitResult.TERMINATE;
                        }
                        String name = dir.getFileName() == null ? "" : dir.getFileName().toString().toLowerCase(Locale.ROOT);
                        if (!dir.equals(root) && SKIPPED.contains(name)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        if (name.endsWith(".app")) {
                            found.add(dir); // macOS-Bundle: zählt als ein Programm
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (++seen[0] > limit) {
                            cut[0] = true;
                            return FileVisitResult.TERMINATE;
                        }
                        if (attrs.isRegularFile() && program(file)) {
                            found.add(file);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException e) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                // nicht lesbarer Ordner: überspringen
            }
        }
        return new Result(List.copyOf(found), !cut[0]);
    }

    /**
     * Programme nach Ordnerstruktur zusammengefasst: je freigegebenem Ordner eine Kopfzeile mit Anzahl, darunter je
     * Ordner eine Zeile mit den Programmnamen (ohne Endung, sortiert), Unterordner relativ zum freigegebenen Ordner.
     * Höchstens {@link #NAMES_PER_LINE} Namen je Zeile und {@link #FOLDERS} Ordnerzeilen insgesamt.
     */
    static List<String> describe(List<Path> roots, List<Path> programs) {
        // freigegebener Ordner → Unterordner (relativ, "" = direkt darin) → Programmnamen
        Map<Path, Map<String, List<String>>> tree = new LinkedHashMap<>();
        roots.forEach(r -> tree.put(r.toAbsolutePath().normalize(), new TreeMap<>(String.CASE_INSENSITIVE_ORDER)));
        for (Path program : programs) {
            Path p = program.toAbsolutePath().normalize();
            tree.keySet().stream().filter(p::startsWith).max(Comparator.comparingInt(Path::getNameCount))
                    .ifPresent(root -> {
                        Path parent = p.getParent();
                        String folder = parent.equals(root) ? "" : root.relativize(parent).toString();
                        tree.get(root).computeIfAbsent(folder, k -> new ArrayList<>())
                                .add(ProcessFilter.name(p.getFileName().toString()));
                    });
        }
        List<String> out = new ArrayList<>();
        int folders = 0;
        int hidden = 0;
        for (Map.Entry<Path, Map<String, List<String>>> root : tree.entrySet()) {
            if (root.getValue().isEmpty()) {
                continue;
            }
            int count = root.getValue().values().stream().mapToInt(List::size).sum();
            if (folders >= FOLDERS) {
                hidden += root.getValue().size();
                continue;
            }
            out.add(root.getKey() + " (" + count + (count == 1 ? " Programm)" : " Programme)"));
            for (Map.Entry<String, List<String>> folder : root.getValue().entrySet()) {
                if (folders++ >= FOLDERS) {
                    hidden++;
                    continue;
                }
                List<String> names = folder.getValue().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
                String shown = String.join(", ", names.subList(0, Math.min(names.size(), NAMES_PER_LINE)));
                if (names.size() > NAMES_PER_LINE) {
                    shown += ", … (+" + (names.size() - NAMES_PER_LINE) + ")";
                }
                out.add("  " + (folder.getKey().isEmpty() ? "" : folder.getKey() + ": ") + shown);
            }
        }
        if (hidden > 0) {
            out.add("  … und " + hidden + " weitere Ordner");
        }
        return out;
    }

    private static boolean program(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return WINDOWS ? name.endsWith(".exe") : Files.isExecutable(file);
    }
}
