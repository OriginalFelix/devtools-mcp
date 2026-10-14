package systems.grebe.devtools.mcp.modules.ide.jetbrains;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import systems.grebe.devtools.mcp.modules.ide.IdePrograms;
import systems.grebe.devtools.mcp.modules.ide.spi.Ide;

import static org.assertj.core.api.Assertions.assertThat;

/** Befehlszeilen der JetBrains-IDEs. */
class JetBrainsIdeProviderTest {

    @TempDir
    Path tmp;

    @Test
    void showPassesLineAndColumnBeforeFile() {
        Path file = tmp.resolve("App.java");
        assertThat(JetBrainsIdeProvider.JetBrainsIde.showArgs(new Ide.Location(file, 42, 7, tmp)))
                .containsExactly("--line", "42", "--column", "7", file.toString());
        assertThat(JetBrainsIdeProvider.JetBrainsIde.showArgs(new Ide.Location(file, 3, 0, null)))
                .containsExactly("--line", "3", file.toString());
        // Spalte ohne Zeile ergibt keinen Sinn
        assertThat(JetBrainsIdeProvider.JetBrainsIde.showArgs(new Ide.Location(file, 0, 7, null)))
                .containsExactly(file.toString());
    }

    @Test
    void macAppRunsViaOpenLikeToolboxScript() throws Exception {
        Path app = Files.createDirectories(tmp.resolve("IntelliJ IDEA.app/Contents/MacOS"));
        Path exe = Files.writeString(app.resolve("idea"), "");
        Path script = Files.writeString(tmp.resolve("idea"), "");
        if (IdePrograms.MAC) {
            assertThat(JetBrainsIdeProvider.launcher(tmp.resolve("IntelliJ IDEA.app"), "idea").command(List.of("x")))
                    .containsExactly("open", "-na", exe.toString(), "--args", "x");
        }
        assertThat(JetBrainsIdeProvider.launcher(script, "idea").command(List.of("x")))
                .containsExactly(script.toString(), "x");
    }

    @Test
    void recognizesIdeaFolderAndOffersEveryProductSetting() throws Exception {
        IntelliJIdeProvider p = new IntelliJIdeProvider();
        assertThat(p.recognizes(tmp)).isFalse();
        Files.createDirectories(tmp.resolve(".idea"));
        assertThat(p.recognizes(tmp)).isTrue();
        assertThat(p.configFields()).extracting(f -> f.key()).containsExactly("executable");
        assertThat(JetBrainsIdeProvider.candidates(new RiderIdeProvider().product())).isNotEmpty()
                .allSatisfy(c -> assertThat(c).contains("rider"));
    }
}
