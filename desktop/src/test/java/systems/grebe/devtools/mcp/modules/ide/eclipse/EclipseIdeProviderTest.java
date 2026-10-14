package systems.grebe.devtools.mcp.modules.ide.eclipse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import systems.grebe.devtools.mcp.modules.ide.IdePrograms;
import systems.grebe.devtools.mcp.modules.ticket.spi.ProviderSettings;

import static org.assertj.core.api.Assertions.assertThat;

/** Befehlszeilen von Eclipse: Starter mit {@code --launcher.openFile}, unter macOS {@code open -a}. */
class EclipseIdeProviderTest {

    @TempDir
    Path tmp;

    private EclipseIdeProvider.EclipseIde ide(Path exe, String workspace) {
        return new EclipseIdeProvider.EclipseIde(ProviderSettings.of(workspace == null
                ? Map.of("executable", exe.toString())
                : Map.of("executable", exe.toString(), "workspace", workspace)));
    }

    @Test
    void launcherPassesPathToRunningInstance() throws Exception {
        Path exe = Files.writeString(tmp.resolve("eclipse"), "");
        assertThat(ide(exe, null).args("/p/App.java:42:7")).containsExactly("--launcher.openFile", "/p/App.java:42:7");
        assertThat(ide(exe, "/ws").args("/p")).containsExactly("-data", "/ws", "--launcher.openFile", "/p");
    }

    @Test
    void macAppGoesThroughOpen() throws Exception {
        Path bin = Files.createDirectories(tmp.resolve("Eclipse.app/Contents/MacOS"));
        Path exe = Files.writeString(bin.resolve("eclipse"), "");
        EclipseIdeProvider.EclipseIde ide = ide(exe, "/ws");
        if (IdePrograms.MAC) {
            assertThat(EclipseIdeProvider.EclipseIde.launcher(exe).prefix())
                    .containsExactly("open", "-a", tmp.resolve("Eclipse.app").toString());
            assertThat(ide.args("/p")).containsExactly("/p", "--args", "-data", "/ws");
        } else {
            assertThat(ide.args("/p")).containsExactly("-data", "/ws", "--launcher.openFile", "/p");
        }
    }

    @Test
    void recognizesProjectFile() throws Exception {
        assertThat(new EclipseIdeProvider().recognizes(tmp)).isFalse();
        Files.writeString(tmp.resolve(".project"), "<projectDescription/>");
        assertThat(new EclipseIdeProvider().recognizes(tmp)).isTrue();
    }
}
