package systems.grebe.devtools.mcp.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AtomicFilesTest {

    @TempDir
    Path dir;

    @Test
    void writesNewAndReplacesExistingFilesCreatingDirectories() throws IOException {
        Path file = dir.resolve("a/b/state.json");
        AtomicFiles.writeString(file, "eins");
        assertThat(file).hasContent("eins");
        AtomicFiles.writeString(file, "zwei");
        assertThat(file).hasContent("zwei");
        assertThat(dir.resolve("a/b/state.json.tmp")).doesNotExist();
    }

    @Test
    void failedWriteKeepsTheOldContentAndRemovesTheTempFile() throws IOException {
        Path file = dir.resolve("state.json");
        AtomicFiles.writeString(file, "alt");
        assertThatThrownBy(() -> AtomicFiles.write(file, tmp -> {
            Files.writeString(tmp, "halb");
            throw new IOException("Platte voll");
        })).isInstanceOf(IOException.class).hasMessage("Platte voll");
        assertThat(file).hasContent("alt");
        assertThat(dir.resolve("state.json.tmp")).doesNotExist();
    }

    @Test
    void replaceMovesOverAnExistingTarget() throws IOException {
        Path tmp = Files.writeString(dir.resolve("x.tmp"), "neu");
        Path target = Files.writeString(dir.resolve("x"), "alt");
        AtomicFiles.replace(tmp, target);
        assertThat(target).hasContent("neu");
        assertThat(tmp).doesNotExist();
    }
}
