package systems.grebe.devtools.mcp.modules.pr;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.modules.pr.spi.GitServer;
import systems.grebe.devtools.mcp.modules.pr.spi.GitServerProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ServiceLoader-Erkennung, Schema, Tool-Liste und Zuordnung eines Aufrufs zu Server und Repository. */
class PrModuleTest {

    GitServerProviders providers = new GitServerProviders();
    PrModule module = new PrModule(providers);

    @TempDir
    Path tmp;

    private PrEnvironment env(Map<String, String> values) {
        return new PrEnvironment(providers, ModuleConfig.of(module.configSchema(), values));
    }

    /** Lokales Repository mit Branch und Remote {@code origin}. */
    private Path repo(String name, String remoteUrl) throws Exception {
        Path dir = tmp.resolve(name);
        try (Git git = Git.init().setDirectory(dir.toFile()).setInitialBranch("feature/x").call()) {
            var cfg = git.getRepository().getConfig();
            cfg.setString("remote", "origin", "url", remoteUrl);
            cfg.save();
        }
        return dir;
    }

    @Test
    void serviceLoaderFindsBuiltInProvidersAndBuildsSchema() {
        assertThat(providers.providers()).extracting(GitServerProvider::id).containsExactly("github", "gitlab", "bitbucket");
        assertThat(module.configSchema()).extracting(ConfigField::key).contains("repositories", "remote",
                "defaultProvider", "github.enabled", "github.token", "gitlab.baseUrl", "bitbucket.deployment",
                "bitbucket.user", "allowCreate", "allowComment", "allowResolve", "allowMerge", "allowPush", "writeProjects");
        assertThat(module.configSchema()).filteredOn(f -> f.key().equals("defaultProvider"))
                .flatExtracting(ConfigField::options).containsExactly("auto", "github", "gitlab", "bitbucket");
        assertThat(env(Map.of()).entries()).isEmpty();
    }

    @Test
    void readToolsAlwaysWriteToolsPerSwitch() {
        List<ToolCallback> tools = module.createTools(ModuleConfig.of(module.configSchema(), Map.of()));
        assertThat(tools).extracting(t -> t.getToolDefinition().name())
                .containsExactlyInAnyOrder("providers", "list", "get", "diff", "comments");
        List<ToolCallback> all = module.createTools(ModuleConfig.of(module.configSchema(), Map.of("allowCreate", "true",
                "allowComment", "true", "allowResolve", "true", "allowMerge", "true", "allowPush", "true")));
        assertThat(all).extracting(t -> t.getToolDefinition().name()).contains("create", "update", "comment", "reply",
                "resolve", "merge", "push");
        assertThat(module.instructions()).contains("`pr_comments", "`pr_reply`", "gh pr");
    }

    @Test
    void takesRepositoriesFromGitModuleOnFirstStart() {
        Map<String, String> init = module.initialValues(id -> "git".equals(id)
                ? Map.of("repositories", "C:/src", "defaultRepository", "app") : Map.of());
        assertThat(init).containsEntry("repositories", "C:/src").containsEntry("defaultRepository", "app");
    }

    @Test
    void resolvesServerAndRepositoryFromLocalRemote() throws Exception {
        Path gh = repo("gh", "git@github.com:octo/app.git");
        Path gl = repo("gl", "https://felix@git.example.com/gitlab/grp/sub/app.git");
        Path bb = repo("bb", "ssh://git@bitbucket.example.com:7999/proj/app.git");
        Path bbHttp = repo("bbhttp", "https://bitbucket.example.com/scm/~felix/tool.git");
        Path cloud = repo("cloud", "https://felix@bitbucket.org/team/web.git");
        Map<String, String> v = new HashMap<>(Map.of("github.enabled", "true", "gitlab.enabled", "true",
                "gitlab.baseUrl", "https://git.example.com/gitlab", "bitbucket.enabled", "true",
                "bitbucket.baseUrl", "https://bitbucket.example.com",
                "repositories", String.join("\n", gh.toString(), gl.toString(), bb.toString(), bbHttp.toString())));
        PrEnvironment env = env(v);

        PrEnvironment.Target t = env.target(null, "gh", null, null);
        assertThat(t.providerId()).isEqualTo("github");
        assertThat(t.project()).isEqualTo("octo/app");
        assertThat(t.local().branch()).isEqualTo("feature/x");

        assertThat(env.target(null, "gl", null, null).project()).isEqualTo("grp/sub/app");
        assertThat(env.target(null, "bb", null, null)).extracting(PrEnvironment.Target::providerId,
                PrEnvironment.Target::project).containsExactly("bitbucket", "PROJ/app");
        assertThat(env.target(null, "bbhttp", null, null).project()).isEqualTo("~felix/tool");

        // URL und voller Schlüssel schlagen das Standard-Repository
        assertThat(env.target(null, null, null, "https://github.com/other/lib/pull/3").project()).isEqualTo("other/lib");
        assertThat(env.target("gitlab", null, null, "x/y!4").project()).isEqualTo("x/y");
        assertThat(env.target("github", null, "a/b", "7").project()).isEqualTo("a/b");

        // mehrere Repositories ohne Standard: nachfragen
        assertThatThrownBy(() -> env.target(null, null, null, null)).hasMessageContaining("Mehrere");

        // Remote, das zu keinem aktiven Server gehört
        v.put("repositories", cloud.toString());
        v.put("bitbucket.baseUrl", "https://bitbucket.example.com");
        assertThatThrownBy(() -> env(v).target(null, null, null, null))
                .hasMessageContaining("gehört zu keinem aktiven Git-Server");
        v.put("bitbucket.baseUrl", "https://bitbucket.org");
        assertThat(env(v).target(null, null, null, null).project()).isEqualTo("team/web");

        assertThatThrownBy(() -> env(Map.of()).target(null, null, "a/b", null)).hasMessageContaining("Kein Git-Server");
    }

    @Test
    void writeProjectsRestrictByRepositoryFromKey() {
        PrEnvironment env = env(Map.of("github.enabled", "true", "writeProjects", "github:octo/*\nother/one"));
        assertThat(env.writeAllowed("github", "octo/app")).isTrue();
        assertThat(env.writeAllowed("github", "Other/One")).isTrue();
        assertThat(env.writeAllowed("gitlab", "octo/app")).isFalse();
        PrEnvironment.Target t = env.target(null, null, "octo/app", null);
        assertThat(env.checkWrite(t, "octo/app#1", "Kommentieren")).isEqualTo("octo/app");
        // der Schlüssel zählt, nicht das freigegebene project
        assertThatThrownBy(() -> env.checkWrite(t, "evil/repo#1", "Kommentieren")).hasMessageContaining("nicht freigegeben");
    }

    @Test
    void parsesRemotesAndUnifiedDiffs() {
        assertThat(GitServer.parseRemote("git@github.com:octo/app.git"))
                .isEqualTo(new GitServer.Remote("ssh", "github.com", -1, "octo/app"));
        assertThat(GitServer.parseRemote("https://user:pw@GitLab.Example.com:8443/a/b/c/"))
                .isEqualTo(new GitServer.Remote("https", "gitlab.example.com", 8443, "a/b/c"));
        assertThat(GitServer.parseRemote("C:/repos/app")).isNull();

        Map<String, String> files = GitServer.splitUnifiedDiff("""
                diff --git a/src/A.java b/src/A.java
                index 1..2 100644
                --- a/src/A.java
                +++ b/src/A.java
                @@ -1,2 +1,2 @@
                -old
                +new
                diff --git a/gone.txt b/gone.txt
                deleted file mode 100644
                --- a/gone.txt
                +++ /dev/null
                @@ -1 +0,0 @@
                -bye
                """);
        assertThat(files).containsOnlyKeys("src/A.java", "gone.txt");
        assertThat(files.get("src/A.java")).isEqualTo("@@ -1,2 +1,2 @@\n-old\n+new");
        assertThat(files.get("gone.txt")).isEqualTo("@@ -1 +0,0 @@\n-bye");
    }
}
