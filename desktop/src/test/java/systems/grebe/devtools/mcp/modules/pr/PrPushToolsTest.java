package systems.grebe.devtools.mcp.modules.pr;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import systems.grebe.devtools.mcp.core.CommandRunner;
import systems.grebe.devtools.mcp.core.ModuleConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** pr_push über das installierte git gegen ein lokales Bare-Repository; Push-Prüfung von pr_create. */
class PrPushToolsTest {

    @TempDir
    Path tmp;
    Path remote;
    Path work;
    PrModule module = new PrModule(new GitServerProviders());

    @BeforeEach
    void setUp() throws Exception {
        remote = tmp.resolve("remote.git");
        work = tmp.resolve("app");
        Git.init().setBare(true).setDirectory(remote.toFile()).setInitialBranch("main").call().close();
        try (Git git = Git.init().setDirectory(work.toFile()).setInitialBranch("main").call()) {
            var cfg = git.getRepository().getConfig();
            cfg.setString("remote", "origin", "url", remote.toUri().toString());
            cfg.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*");
            cfg.setString("user", null, "name", "Test");
            cfg.setString("user", null, "email", "test@example.com");
            cfg.save();
            Files.writeString(work.resolve("a.txt"), "a");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("init").setSign(false).call();
            git.push().setRemote("origin").add("main").call();
            git.checkout().setCreateBranch(true).setName("feature/x").call();
            Files.writeString(work.resolve("b.txt"), "b");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("feature").setSign(false).call();
        }
    }

    private PrEnvironment env() {
        return new PrEnvironment(module.providers(), ModuleConfig.of(module.configSchema(),
                Map.of("repositories", work.toString(), "github.enabled", "true", "allowPush", "true")));
    }

    private static boolean gitInstalled() {
        try {
            return CommandRunner.run(List.of("git", "--version"), Duration.ofSeconds(10)).ok();
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Test
    void pushesFeatureBranchButNeverDefaultBranch() throws Exception {
        assumeTrue(gitInstalled(), "git nicht installiert");
        PrEnvironment env = env();
        PrEnvironment.LocalRepo local = env.local(null);
        assertThat(local.branch()).isEqualTo("feature/x");
        assertThat(PrEnvironment.unpushed(local, "feature/x")).isEqualTo(-1);

        PrPushTools tools = new PrPushTools(env);
        assertThat(tools.push(null, null)).contains("Branch feature/x auf origin", "gepusht");
        assertThat(PrEnvironment.unpushed(local, "feature/x")).isZero();
        try (Git bare = Git.open(remote.toFile())) {
            assertThat(bare.getRepository().exactRef("refs/heads/feature/x")).isNotNull();
        }
        assertThat(tools.push(null, null)).contains("nichts zu pushen");

        assertThatThrownBy(() -> tools.push(null, "main")).hasMessageContaining("Standard-Branch");
        assertThatThrownBy(() -> tools.push(null, "gibts/nicht")).hasMessageContaining("existiert");
        assertThatThrownBy(() -> tools.push(null, "--force")).hasMessageContaining("Ungültiger Branch-Name");
    }

    @Test
    void createRefusesUnpushedBranchBeforeCallingServer() {
        // Remote ist kein GitHub – daher project/provider explizit; die Push-Prüfung greift über das lokale Repository
        PrCreateTools tools = new PrCreateTools(env());
        assertThatThrownBy(() -> tools.create("T", null, null, null, null, null, null, "app", "octo/app", "github"))
                .hasMessageContaining("nicht auf 'origin'").hasMessageContaining("pr_push");
        assertThatThrownBy(() -> tools.create("T", null, "main", "main", null, null, null, "app", "octo/app", "github"))
                .hasMessageContaining("beide 'main'");
    }
}
