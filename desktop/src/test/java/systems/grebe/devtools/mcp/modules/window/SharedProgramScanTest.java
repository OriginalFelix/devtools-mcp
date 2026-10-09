package systems.grebe.devtools.mcp.modules.window;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class SharedProgramScanTest {

    static Path program(Path dir, String base) throws Exception {
        Files.createDirectories(dir);
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path f = Files.writeString(dir.resolve(windows ? base + ".exe" : base), "x");
        f.toFile().setExecutable(true);
        return f;
    }

    @Test
    void findsProgramsInSubdirectoriesAndSkipsBuildFolders(@TempDir Path root) throws Exception {
        Path deep = program(root.resolve("a").resolve("b"), "tool");
        program(root.resolve("node_modules").resolve("x"), "hidden");
        program(root.resolve(".git"), "hook");
        Files.writeString(root.resolve("readme.txt"), "x");

        SharedProgramScan.Result r = SharedProgramScan.scan(List.of(root));

        assertThat(r.programs()).containsExactly(deep);
        assertThat(r.complete()).isTrue();
    }

    @Test
    void stopsAtTheLimit(@TempDir Path root) throws Exception {
        for (int i = 0; i < 10; i++) {
            program(root, "p" + i);
        }

        SharedProgramScan.Result r = SharedProgramScan.scan(List.of(root), 5);

        assertThat(r.complete()).isFalse();
        assertThat(r.programs().size()).isLessThanOrEqualTo(5);
    }

    @Test
    void describesProgramsByFolderStructure(@TempDir Path root) {
        Path other = root.resolveSibling(root.getFileName() + "-zweite");
        List<Path> programs = List.of(
                root.resolve("b").resolve("deep").resolve("Zeta.exe"),
                root.resolve("Spotify.exe"),
                root.resolve("crashpad_handler.exe"),
                root.resolve("b").resolve("Alpha.exe"),
                other.resolve("Tool.exe"));

        List<String> lines = SharedProgramScan.describe(List.of(root, other), programs);

        String sep = root.getFileSystem().getSeparator();
        assertThat(lines).containsExactly(
                root + " (4 Programme)",
                "  crashpad_handler, Spotify",
                "  b: Alpha",
                "  b" + sep + "deep: Zeta",
                other + " (1 Programm)",
                "  Tool");
    }

    @Test
    void summarizesLongFoldersAndManyFolders(@TempDir Path root) {
        List<Path> programs = new java.util.ArrayList<>();
        for (int i = 0; i < SharedProgramScan.NAMES_PER_LINE + 3; i++) {
            programs.add(root.resolve("p" + (char) ('a' + i) + ".exe"));
        }
        for (int i = 0; i < SharedProgramScan.FOLDERS + 2; i++) {
            programs.add(root.resolve("d" + (char) ('a' + i)).resolve("x.exe"));
        }

        List<String> lines = SharedProgramScan.describe(List.of(root), programs);

        assertThat(lines.get(1)).endsWith(", … (+3)");
        // Ordner: der Hauptordner selbst und FOLDERS + 2 Unterordner – FOLDERS werden gezeigt
        assertThat(lines.getLast()).isEqualTo("  … und 3 weitere Ordner");
        assertThat(lines).hasSize(1 + SharedProgramScan.FOLDERS + 1);
    }

    @Test
    void ignoresMissingDirectories(@TempDir Path root) {
        assertThat(SharedProgramScan.scan(List.of(root.resolve("fehlt"))).programs()).isEmpty();
    }
}
