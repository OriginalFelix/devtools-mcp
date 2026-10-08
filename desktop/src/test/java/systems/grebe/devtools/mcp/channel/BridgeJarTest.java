package systems.grebe.devtools.mcp.channel;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BridgeJarTest {

    @TempDir
    Path dir;

    @Test
    void copiesRunningJarToFixedPath() throws Exception {
        Path source = jar("app/devtools-mcp-0.1.0-SNAPSHOT.jar", "v1");
        Path target = BridgeJar.path(dir.resolve("home"));

        assertThat(new BridgeJar(Optional.of(source), target).sync()).isTrue();

        assertThat(target).hasFileName("devtools-mcp.jar").hasContent("v1");
        assertThat(Files.getLastModifiedTime(target)).isEqualTo(Files.getLastModifiedTime(source));
        try (var files = Files.list(target.getParent())) {
            assertThat(files).containsExactly(target); // keine temporäre Datei übrig
        }
    }

    @Test
    void replacesOutdatedCopyAndKeepsCurrentOne() throws Exception {
        Path source = jar("app/devtools-mcp.jar", "v2-neu");
        Path target = jar("home/devtools-mcp.jar", "v1");
        BridgeJar bridge = new BridgeJar(Optional.of(source), target);

        assertThat(bridge.sync()).isTrue();
        assertThat(target).hasContent("v2-neu");

        FileTime copied = Files.getLastModifiedTime(target);
        Files.writeString(target, "v2-alt"); // gleiche Größe, aber andere Änderungszeit
        Files.setLastModifiedTime(target, FileTime.from(Instant.parse("2020-01-01T00:00:00Z")));
        assertThat(bridge.sync()).isTrue();
        assertThat(target).hasContent("v2-neu");
        assertThat(Files.getLastModifiedTime(target)).isEqualTo(copied);
    }

    @Test
    void appRunningFromTheCopyItselfIsUpToDate() throws Exception {
        Path target = jar("home/devtools-mcp.jar", "v1");

        assertThat(new BridgeJar(Optional.of(target), target).sync()).isTrue();
        assertThat(target).hasContent("v1");
    }

    @Test
    void withoutJarNothingHappens() {
        Path target = BridgeJar.path(dir);

        assertThat(new BridgeJar(Optional.empty(), target).sync()).isTrue();
        assertThat(target).doesNotExist();
    }

    @Test
    void runningJarOnlyForSingleJarOnClassPath() {
        assertThat(BridgeJar.runningJar("lib/devtools-mcp.jar")).contains(Path.of("lib/devtools-mcp.jar").toAbsolutePath());
        assertThat(BridgeJar.runningJar("build/classes" + File.pathSeparator + "lib/a.jar")).isEmpty();
        assertThat(BridgeJar.runningJar("build/classes/java/main")).isEmpty();
        assertThat(BridgeJar.runningJar("")).isEmpty();
    }

    private Path jar(String name, String content) throws Exception {
        Path p = dir.resolve(name);
        Files.createDirectories(p.getParent());
        return Files.writeString(p, content);
    }
}
