package systems.grebe.devtools.mcp.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/** Gemeinsame Pfadhilfen für Freigaben: Benutzerverzeichnis und "liegt wirklich darin". */
class LocalFilesPathsTest {

    @TempDir
    Path tmp;

    @Test
    void expandsOnlyALeadingTildeFollowedBySeparatorOrEnd() {
        String home = System.getProperty("user.home");
        assertThat(LocalFiles.expandHome("~")).isEqualTo(home);
        assertThat(LocalFiles.expandHome("  ~/docs ")).isEqualTo(home + "/docs");
        assertThat(LocalFiles.expandHome("~\\docs")).isEqualTo(home + "\\docs");
        assertThat(LocalFiles.expandHome("~foo")).isEqualTo("~foo");
        assertThat(LocalFiles.expandHome("/a/~/b")).isEqualTo("/a/~/b");
    }

    @Test
    void insideChecksTheNearestExistingAncestorAndToleratesMissingTargets() throws IOException {
        Path root = Files.createDirectories(tmp.resolve("root"));
        assertThat(LocalFiles.realPathInside(root.resolve("neu/datei.txt"), root)).isTrue();
        assertThat(LocalFiles.realPathInside(tmp.resolve("daneben/datei.txt"), root)).isFalse();
        // eine noch nicht angelegte Freigabe kann keinen Link enthalten
        assertThat(LocalFiles.realPathInside(tmp.resolve("fehlt/x"), tmp.resolve("fehlt"))).isTrue();
    }
}
