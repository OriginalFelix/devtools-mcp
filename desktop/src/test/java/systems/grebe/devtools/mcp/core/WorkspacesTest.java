package systems.grebe.devtools.mcp.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspacesTest {

    @TempDir
    Path tmp;

    @Test
    void namedEntriesUseTheirName() throws Exception {
        Path app = Files.createDirectories(tmp.resolve("app"));
        Workspaces w = new Workspaces(List.of("mein-projekt=" + app, "geteilt@alice=" + app), p -> true, "Projekte");
        assertThat(w.all()).containsOnlyKeys("mein-projekt", "geteilt@alice");
        assertThat(w.resolve("geteilt@alice", null)).isEqualTo(app.toAbsolutePath().normalize());
    }

    @Test
    void pathsContainingEqualsSignsAreNotNames() throws Exception {
        Path odd = Files.createDirectories(tmp.resolve("a=b"));
        Workspaces w = new Workspaces(List.of(odd.toString()), p -> true, "Projekte");
        assertThat(w.all()).containsOnlyKeys("a=b");
    }

    @Test
    void writeGuardFollowsScope() {
        Path root = tmp.resolve("x");
        ToolScope readOnly = new ToolScope("t", "1", "u", null, null, false, r -> false);
        assertThatThrownBy(() -> ToolScope.callIn(readOnly, () -> {
            Workspaces.requireWritable(root);
            return null;
        })).hasMessageContaining("nur lesend");
        Workspaces.requireWritable(root); // lokal (ohne Benutzer) immer erlaubt
    }
}
