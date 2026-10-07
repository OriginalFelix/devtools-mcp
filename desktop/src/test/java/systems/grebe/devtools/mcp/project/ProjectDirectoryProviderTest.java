package systems.grebe.devtools.mcp.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolScope;
import systems.grebe.devtools.mcp.project.spi.ProjectDirectory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectDirectoryProviderTest {

    private static final Predicate<Path> GRADLE = dir -> Files.exists(dir.resolve("settings.gradle"));

    @TempDir
    Path tmp;

    private Path egecko;
    private Path ikit;
    private Path other;
    private Map<String, Map<String, String>> values;
    private ProjectDirectoryProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        Path root = tmp.toRealPath(); // macOS: /var → /private/var
        Path workspace = Files.createDirectories(root.resolve("Entwicklung"));
        egecko = gradle(workspace.resolve("egecko"));
        ikit = gradle(workspace.resolve("ikit"));
        Files.createDirectories(workspace.resolve("notizen"));
        other = gradle(root.resolve("woanders/projekt"));
        Files.createDirectories(egecko.resolve("src/main/java/de/css"));
        values = Map.of(
                "git", Map.of("repositories", ikit.toString()),
                "build", Map.of("projects", workspace + "\n" + "evo@felix=" + egecko),
                "access", Map.of("directories", ikit.toString()));
        provider = new ProjectDirectoryProvider(
                id -> ModuleConfig.of(List.of(), values.getOrDefault(id, Map.of())), values::containsKey);
    }

    @AfterEach
    void tearDown() {
        ToolScope.LOCAL.setUnrestricted(false);
        ToolScope.LOCAL.restrictWrites(null);
    }

    private static Path gradle(Path dir) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("settings.gradle"), "rootProject.name = '" + dir.getFileName() + "'");
        return dir.toRealPath();
    }

    @Test
    void collectsAllSharedDirectoriesOnceWithBackendNamesFirst() {
        List<ProjectDirectory> projects = provider.projects(GRADLE);

        assertThat(projects).extracting(ProjectDirectory::name).containsExactly("evo@felix", "ikit");
        assertThat(projects).extracting(ProjectDirectory::path).containsExactly(egecko, ikit);
        assertThat(projects).allMatch(ProjectDirectory::writable);
        assertThat(provider.projects(null)).extracting(ProjectDirectory::name).contains("Entwicklung")
                .doesNotContain("notizen");
    }

    @Test
    void resolvesNamePathAndSubdirectory() {
        assertThat(provider.resolve("EVO@felix", GRADLE).path()).isEqualTo(egecko);
        assertThat(provider.resolve(egecko.resolve("src/main/java/de/css").toString(), GRADLE))
                .isEqualTo(new ProjectDirectory("evo@felix", egecko, true));
        assertThat(provider.resolve(ikit.toString(), GRADLE).name()).isEqualTo("ikit");
        assertThatThrownBy(() -> provider.resolve(null, GRADLE)).hasMessageContaining("Mehrere");
        assertThatThrownBy(() -> provider.resolve(other.toString(), GRADLE)).hasMessageContaining("nicht freigegeben");
    }

    @Test
    void unrestrictedAcceptsOtherPathsAndWritesFollowScope() {
        ToolScope.LOCAL.setUnrestricted(true);
        assertThat(provider.resolve(other.resolve("sub").toString(), GRADLE).path()).isEqualTo(other);

        ToolScope.LOCAL.restrictWrites(root -> !root.equals(egecko));
        assertThat(provider.resolve("evo@felix", GRADLE).writable()).isFalse();
        assertThat(provider.resolve("ikit", GRADLE).writable()).isTrue();
    }
}
