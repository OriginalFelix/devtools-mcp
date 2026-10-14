package systems.grebe.devtools.mcp.modules.ide.vscode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import systems.grebe.devtools.mcp.modules.ide.spi.Ide;

import static org.assertj.core.api.Assertions.assertThat;

/** Befehlszeilen von VS Code, VSCodium und Cursor. */
class VsCodeFamilyProviderTest {

    @TempDir
    Path tmp;

    @Test
    void showOpensFileInProjectWindow() {
        Path file = tmp.resolve("App.java");
        assertThat(VsCodeFamilyProvider.VsCodeIde.showArgs(new Ide.Location(file, 42, 7, tmp)))
                .containsExactly(tmp.toString(), "-g", file + ":42:7");
        assertThat(VsCodeFamilyProvider.VsCodeIde.showArgs(new Ide.Location(file, 0, 0, null)))
                .containsExactly("-g", file.toString());
    }

    @Test
    void macAppBecomesItsCommandLine() throws Exception {
        Path bin = Files.createDirectories(tmp.resolve("Visual Studio Code.app/Contents/Resources/app/bin"));
        Path code = Files.writeString(bin.resolve("code"), "");
        assertThat(VsCodeFamilyProvider.launcher(tmp.resolve("Visual Studio Code.app"), "code").command(List.of("x")))
                .containsExactly(code.toString(), "x");
        assertThat(new VsCodeIdeProvider().recognizes(tmp)).isFalse();
        Files.createDirectories(tmp.resolve(".vscode"));
        assertThat(new CursorIdeProvider().recognizes(tmp)).isTrue();
    }
}
