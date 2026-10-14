package systems.grebe.devtools.mcp.modules.ide.xcode;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/** Xcode: Workspace vor Projekt, Swift-Pakete. */
class XcodeIdeProviderTest {

    @TempDir
    Path tmp;

    @Test
    void prefersWorkspaceOverProject() throws Exception {
        XcodeIdeProvider p = new XcodeIdeProvider();
        assertThat(p.recognizes(tmp)).isFalse();
        Files.createDirectories(tmp.resolve("App.xcodeproj"));
        Files.createDirectories(tmp.resolve("App.xcworkspace"));
        assertThat(XcodeIdeProvider.projectFiles(tmp)).containsExactly(tmp.resolve("App.xcworkspace"),
                tmp.resolve("App.xcodeproj"));
        assertThat(p.recognizes(tmp)).isTrue();

        Path pkg = Files.createDirectories(tmp.resolve("pkg"));
        Files.writeString(pkg.resolve("Package.swift"), "");
        assertThat(p.recognizes(pkg)).isTrue();
        assertThat(XcodeIdeProvider.projectFiles(pkg)).isEmpty();
        assertThat(XcodeIdeProvider.CLOSE).contains("saving ask", "is not running");
    }
}
