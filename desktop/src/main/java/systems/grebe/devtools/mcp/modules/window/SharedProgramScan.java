package systems.grebe.devtools.mcp.modules.window;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Sucht in den global freigegebenen Ordnern rekursiv nach Programmen ({@code .exe} unter Windows, {@code .app}-Bundles
 * unter macOS, sonst Dateien mit Ausführungsrecht) – für die Warnung beim Speichern. Build- und Werkzeugordner werden
 * übersprungen, nach {@link #LIMIT} Einträgen bricht die Suche ab.
 */
final class SharedProgramScan {

    static final int LIMIT = 50_000;
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

    private static boolean program(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return WINDOWS ? name.endsWith(".exe") : Files.isExecutable(file);
    }
}
