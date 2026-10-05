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
    void ignoresMissingDirectories(@TempDir Path root) {
        assertThat(SharedProgramScan.scan(List.of(root.resolve("fehlt"))).programs()).isEmpty();
    }
}
