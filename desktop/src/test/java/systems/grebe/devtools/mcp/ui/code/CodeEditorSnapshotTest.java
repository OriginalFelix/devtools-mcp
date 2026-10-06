package systems.grebe.devtools.mcp.ui.code;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Startet den Sichttest {@link CodeEditorSnapshot} aus Gradle – nur auf Wunsch (braucht einen Bildschirm): Ordner
 * {@code desktop/build/code-editor-snapshot} anlegen, dann {@code test --tests *CodeEditorSnapshotTest}.
 */
class CodeEditorSnapshotTest {

    private static final Path OUT = Path.of("build", "code-editor-snapshot");

    static boolean requested() {
        return Files.isDirectory(OUT);
    }

    @Test
    @EnabledIf("requested")
    void snapshots() throws Exception {
        assertThat(CodeEditorSnapshot.run(OUT)).isTrue();
    }
}
