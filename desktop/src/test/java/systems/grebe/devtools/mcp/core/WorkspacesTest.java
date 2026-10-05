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
    void unrestrictedScopeAcceptsAnyAbsolutePathUpToTheNextMarker() throws Exception {
        Path shared = Files.createDirectories(tmp.resolve("shared/.git"));
        Path other = Files.createDirectories(tmp.resolve("other"));
        Files.createDirectories(other.resolve(".git"));
        Path nested = Files.createDirectories(other.resolve("src/main"));
        Workspaces w = new Workspaces(List.of(shared.getParent().toString()), p -> Files.exists(p.resolve(".git")),
                "Git-Repositories");
        assertThatThrownBy(() -> w.resolve(other.toString(), null)).hasMessageContaining("nicht freigegeben");

        ToolScope open = new ToolScope("t", null, null, null, null, true);
        open.setUnrestricted(true);
        ToolScope.callIn(open, () -> {
            assertThat(w.resolve(other.toString(), null)).isEqualTo(other.toAbsolutePath().normalize());
            assertThat(w.resolve(nested.toString(), null)).isEqualTo(other.toAbsolutePath().normalize());
            assertThat(w.resolve("shared", null)).isEqualTo(shared.getParent().toAbsolutePath().normalize());
            assertThatThrownBy(() -> w.resolve("other", null)).hasMessageContaining("absoluten Pfad");
            assertThatThrownBy(() -> w.resolve(tmp.toString(), null)).hasMessageContaining("kein passendes");
            assertThat(w.unrestrictedHint()).contains("absolutem Pfad");
            return null;
        });
        assertThat(w.unrestrictedHint()).isEmpty();
    }

    @Test
    void unrestrictedScopeWorksWithoutConfiguredEntries() throws Exception {
        Path repo = Files.createDirectories(tmp.resolve("repo/.git")).getParent();
        Workspaces w = new Workspaces(List.of(), p -> Files.exists(p.resolve(".git")), "Git-Repositories");
        assertThatThrownBy(() -> w.resolve(repo.toString(), null)).hasMessageContaining("Keine Git-Repositories");

        ToolScope open = new ToolScope("t", null, null, null, null, true);
        open.setUnrestricted(true);
        ToolScope.callIn(open, () -> {
            assertThat(w.resolve(repo.toString(), null)).isEqualTo(repo.toAbsolutePath().normalize());
            assertThatThrownBy(() -> w.resolve(null, null)).hasMessageContaining("absoluten Pfad");
            return null;
        });
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
