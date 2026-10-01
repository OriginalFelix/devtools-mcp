package systems.grebe.devtools.mcp.modules.git;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import systems.grebe.devtools.mcp.core.ModuleConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitToolsTest {

    @TempDir
    Path parent;

    Path repo;
    GitReadTools read;
    GitWriteTools write;

    @BeforeEach
    void setUp() throws Exception {
        repo = Files.createDirectory(parent.resolve("demo"));
        try (Git git = Git.init().setDirectory(repo.toFile()).setInitialBranch("main").call()) {
            git.getRepository().getConfig().setString("user", null, "name", "Tester");
            git.getRepository().getConfig().setString("user", null, "email", "t@example.com");
            git.getRepository().getConfig().setBoolean("commit", null, "gpgsign", false);
            git.getRepository().getConfig().save();
            Files.writeString(repo.resolve("App.java"), "class App {\n  int a = 1;\n}\n");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("Erster Commit").call();
            Files.writeString(repo.resolve("App.java"), "class App {\n  int a = 2;\n}\n");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("Wert geändert\n\nDetails im Body").call();
        }
        // Sammelordner konfigurieren -> Unterordner mit .git wird gefunden
        GitSupport support = new GitSupport(ModuleConfig.of(new GitModule().configSchema(),
                Map.of(GitModule.REPOSITORIES, parent.toString())));
        read = new GitReadTools(support);
        write = new GitWriteTools(support);
    }

    @Test
    void discoversRepositoriesInParentFolder() {
        assertThat(read.listRepositories()).startsWith("demo  [main]");
    }

    @Test
    void statusShowsCleanAndDirtyState() throws Exception {
        assertThat(read.status(null)).contains("Branch: main").contains("sauber");
        Files.writeString(repo.resolve("App.java"), "class App {}\n");
        Files.writeString(repo.resolve("Neu.txt"), "x");
        assertThat(read.status("demo")).contains("Geändert, nicht gestaged (1)").contains("App.java")
                .contains("Unversioniert (1)").contains("Neu.txt");
    }

    @Test
    void logListsCommitsNewestFirstAndFilters() {
        String log = read.log(null, null, null, null, null, null, null);
        assertThat(log.lines().toList()).hasSize(2);
        assertThat(log.lines().findFirst().orElseThrow()).contains("Wert geändert");
        assertThat(read.log(null, null, null, "erster", null, null, null)).contains("Erster Commit").doesNotContain("Wert");
        assertThat(read.log(null, null, "App.java", null, 1, null, null).lines().count()).isEqualTo(1);
    }

    @Test
    void diffBetweenRevisionsAndWorkingTree() throws Exception {
        assertThat(read.diff(null, "HEAD~1", "HEAD", null, null, null))
                .contains("-  int a = 1;").contains("+  int a = 2;");
        assertThat(read.diff(null, null, null, null, null, null)).contains("keine Änderungen");
        Files.writeString(repo.resolve("App.java"), "class App {\n  int a = 3;\n}\n");
        assertThat(read.diff(null, null, null, "App.java", null, null)).contains("+  int a = 3;");
        assertThat(read.diff(null, null, null, null, null, true)).isEqualTo("MODIFY App.java");
    }

    @Test
    void showCommitBlameAndFileAtRevision() {
        assertThat(read.showCommit(null, "HEAD", null)).contains("Details im Body").contains("+  int a = 2;");
        assertThat(read.blame(null, "App.java", 2, 2)).contains("int a = 2").contains("Tester");
        assertThat(read.fileAtRevision(null, "App.java", "HEAD~1")).contains("int a = 1");
        assertThat(read.branches(null, false)).contains("* main");
    }

    @Test
    void rejectsPathsOutsideRepository() {
        assertThatThrownBy(() -> read.fileAtRevision(null, "../geheim.txt", null))
                .hasMessageContaining("außerhalb");
        assertThatThrownBy(() -> read.status("C:/Windows")).hasMessageContaining("nicht freigegeben");
    }

    @Test
    void writeToolsBranchStageCommit() throws Exception {
        assertThat(write.createBranch(null, "feature/x", null, null)).contains("ausgecheckt");
        Files.writeString(repo.resolve("Neu.txt"), "neu");
        assertThatThrownBy(() -> write.commit(null, "leer", false)).hasMessageContaining("Nichts zu committen");
        assertThat(write.stage(null, List.of("Neu.txt"))).contains("Im Index: 1");
        assertThat(write.commit(null, "Neue Datei", false)).contains("auf feature/x");
        assertThat(read.log(null, null, null, null, 1, null, null)).contains("Neue Datei");
        assertThat(write.checkout(null, "main")).contains("main");
    }

    @Test
    void writeToolsOnlyWhenAllowed() {
        GitModule module = new GitModule();
        var off = ModuleConfig.of(module.configSchema(), Map.of(GitModule.REPOSITORIES, parent.toString(), GitModule.ALLOW_WRITE, "false"));
        var on = ModuleConfig.of(module.configSchema(), Map.of(GitModule.REPOSITORIES, parent.toString()));
        assertThat(module.createTools(off)).extracting(t -> t.getToolDefinition().name()).doesNotContain("commit");
        assertThat(module.createTools(on)).extracting(t -> t.getToolDefinition().name()).contains("commit", "stage");
        assertThat(module.testConnection(on).success()).isTrue();
    }
}
