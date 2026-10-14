package systems.grebe.devtools.mcp.modules.ide.visualstudio;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import systems.grebe.devtools.mcp.modules.ide.IdePrograms;
import systems.grebe.devtools.mcp.modules.ticket.spi.ProviderSettings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/** Visual Studio: Wahl der Projektmappe, Skripte für die Automatisierung (DTE). */
class VisualStudioIdeProviderTest {

    @TempDir
    Path tmp;

    @Test
    void picksSolutionNamedLikeFolderElseFirstElseFolder() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("Shop"));
        assertThat(VisualStudioIdeProvider.target(dir)).isEqualTo(dir);
        assertThat(new VisualStudioIdeProvider().recognizes(dir)).isFalse();
        Files.writeString(dir.resolve("Aaa.sln"), "");
        assertThat(VisualStudioIdeProvider.target(dir)).isEqualTo(dir.resolve("Aaa.sln"));
        Files.writeString(dir.resolve("Shop.slnx"), "");
        assertThat(VisualStudioIdeProvider.target(dir)).isEqualTo(dir.resolve("Shop.slnx"));
        assertThat(new VisualStudioIdeProvider().recognizes(dir)).isTrue();
    }

    @Test
    void scriptsGoAsEncodedCommandAndTakePathsFromEnvironment() {
        // UTF-16LE in Base64: Anführungszeichen überstehen die Befehlszeile von Windows
        List<String> cmd = VisualStudioIdeProvider.VisualStudioIde.command("'a \"b\"'");
        assertThat(cmd).startsWith("powershell.exe").contains("-EncodedCommand");
        assertThat(new String(Base64.getDecoder().decode(cmd.getLast()), StandardCharsets.UTF_16LE))
                .endsWith("'a \"b\"'");
        for (String script : List.of(VisualStudioIdeProvider.OPEN, VisualStudioIdeProvider.CLOSE,
                VisualStudioIdeProvider.SHOW)) {
            assertThat(script).contains("$env:DT_DIR", "!VisualStudio.DTE").doesNotContain("%s");
        }
        // schließt nur ohne ungespeicherte Änderungen
        assertThat(VisualStudioIdeProvider.CLOSE).contains("-not $_.Saved", "exit 5", "Solution.Close($false)");
    }

    @Test
    void onlyAvailableOnWindows() {
        assumeThat(IdePrograms.WINDOWS).isFalse();
        assertThat(new VisualStudioIdeProvider().create(ProviderSettings.of(Map.of())).probe().message())
                .contains("nur unter Windows");
    }
}
