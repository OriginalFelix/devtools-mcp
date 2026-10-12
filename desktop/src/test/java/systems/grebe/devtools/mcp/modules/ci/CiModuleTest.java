package systems.grebe.devtools.mcp.modules.ci;

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
import systems.grebe.devtools.mcp.modules.ci.spi.CiProvider;
import systems.grebe.devtools.mcp.modules.ci.spi.CiSystem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ServiceLoader-Erkennung, Schema, Tool-Liste und Zuordnung eines Aufrufs zu System und Projekt. */
class CiModuleTest {

    CiProviders providers = new CiProviders();
    CiModule module = new CiModule(providers);

    @TempDir
    Path tmp;

    private CiEnvironment env(Map<String, String> values) {
        return new CiEnvironment(providers, ModuleConfig.of(module.configSchema(), values));
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
        assertThat(providers.providers()).extracting(CiProvider::id).containsExactly("github", "gitlab", "jenkins");
        assertThat(module.configSchema()).extracting(ConfigField::key).contains("repositories", "remote",
                "defaultProvider", "github.enabled", "github.token", "gitlab.baseUrl", "jenkins.baseUrl", "jenkins.user",
                "jenkins.token", "jenkins.jobs", "logLines", "allowStart", "allowCancel", "allowRetry", "writeProjects");
        assertThat(module.configSchema()).filteredOn(f -> f.key().equals("defaultProvider"))
                .flatExtracting(ConfigField::options).containsExactly("auto", "github", "gitlab", "jenkins");
        assertThat(env(Map.of()).entries()).isEmpty();
    }

    @Test
    void readToolsAlwaysControlToolsPerSwitch() {
        List<ToolCallback> tools = module.createTools(ModuleConfig.of(module.configSchema(), Map.of()));
        assertThat(tools).extracting(t -> t.getToolDefinition().name())
                .containsExactlyInAnyOrder("providers", "list", "get", "log", "workflows");
        List<ToolCallback> all = module.createTools(ModuleConfig.of(module.configSchema(), Map.of("allowStart", "true",
                "allowCancel", "true", "allowRetry", "true")));
        assertThat(all).extracting(t -> t.getToolDefinition().name()).contains("start", "cancel", "retry");
        assertThat(module.instructions()).contains("`ci_get`", "`ci_log`", "gh run");
    }

    @Test
    void takesRepositoriesFromGitModuleOnFirstStart() {
        Map<String, String> init = module.initialValues(id -> "git".equals(id)
                ? Map.of("repositories", "C:/src", "defaultRepository", "app") : Map.of());
        assertThat(init).containsEntry("repositories", "C:/src").containsEntry("defaultRepository", "app");
    }

    @Test
    void resolvesSystemAndProjectFromLocalRemote() throws Exception {
        Path gh = repo("gh", "git@github.com:octo/app.git");
        Path gl = repo("gl", "https://felix@git.example.com/gitlab/grp/sub/app.git");
        Path jk = repo("jk", "ssh://git@bitbucket.example.com:7999/proj/tool.git");
        Map<String, String> v = new HashMap<>(Map.of("github.enabled", "true", "gitlab.enabled", "true",
                "gitlab.baseUrl", "https://git.example.com/gitlab", "jenkins.enabled", "true",
                "jenkins.baseUrl", "https://ci.example.com/jenkins", "jenkins.jobs", "proj/tool = team/tool\nfoo=bar",
                "repositories", String.join("\n", gh.toString(), gl.toString(), jk.toString())));
        CiEnvironment env = env(v);

        CiEnvironment.Target t = env.target(null, "gh", null, null);
        assertThat(t.providerId()).isEqualTo("github");
        assertThat(t.project()).isEqualTo("octo/app");
        assertThat(t.localBranch()).isEqualTo("feature/x");

        assertThat(env.target(null, "gl", null, null).project()).isEqualTo("grp/sub/app");
        // Jenkins über die Job-Zuordnung (Remote-Pfad, ohne Groß-/Kleinschreibung)
        assertThat(env.target(null, "jk", null, null)).extracting(CiEnvironment.Target::providerId,
                CiEnvironment.Target::project).containsExactly("jenkins", "team/tool");

        // URL und voller Schlüssel schlagen das Standard-Repository
        assertThat(env.target(null, null, null, "https://github.com/other/lib/actions/runs/99").project())
                .isEqualTo("other/lib");
        assertThat(env.target(null, null, null, "https://ci.example.com/jenkins/job/a/job/b/7/console"))
                .extracting(CiEnvironment.Target::providerId, CiEnvironment.Target::project)
                .containsExactly("jenkins", "a/b");
        assertThat(env.target("gitlab", null, null, "x/y#4").project()).isEqualTo("x/y");
        assertThat(env.target("github", null, "a/b", "7").project()).isEqualTo("a/b");

        assertThatThrownBy(() -> env.target(null, null, null, null)).hasMessageContaining("Mehrere");
        assertThatThrownBy(() -> env(Map.of()).target(null, null, "a/b", null)).hasMessageContaining("Kein CI-System");

        // Jenkins ohne Zuordnung: Hinweis auf die Job-Zuordnung
        v.put("jenkins.jobs", "");
        v.put("github.enabled", "false");
        v.put("gitlab.enabled", "false");
        v.put("repositories", jk.toString());
        assertThatThrownBy(() -> env(v).target(null, null, null, null)).hasMessageContaining("Job-Zuordnung");
    }

    @Test
    void writeProjectsRestrictByProjectFromKey() {
        CiEnvironment env = env(Map.of("github.enabled", "true", "writeProjects", "github:octo/*\njenkins:team/app"));
        assertThat(env.writeAllowed("github", "octo/app")).isTrue();
        assertThat(env.writeAllowed("jenkins", "Team/App")).isTrue();
        assertThat(env.writeAllowed("gitlab", "octo/app")).isFalse();
        CiEnvironment.Target t = env.target(null, null, "octo/app", null);
        assertThat(env.checkWrite(t, "octo/app#1", "Abbrechen")).isEqualTo("octo/app");
        // der Schlüssel zählt, nicht das freigegebene project
        assertThatThrownBy(() -> env.checkWrite(t, "evil/repo#1", "Abbrechen")).hasMessageContaining("nicht freigegeben");
    }

    @Test
    void parsesStatusFilterAndFormatsDurations() {
        assertThat(CiSystem.Status.parse(null)).isNull();
        assertThat(CiSystem.Status.parse("all")).isNull();
        assertThat(CiSystem.Status.parse("Failure")).isEqualTo(CiSystem.Status.FAILED);
        assertThat(CiSystem.Status.parse("cancelled")).isEqualTo(CiSystem.Status.CANCELED);
        assertThatThrownBy(() -> CiSystem.Status.parse("kaputt")).hasMessageContaining("erlaubt");
        assertThat(CiTools.duration(45L)).isEqualTo("45s");
        assertThat(CiTools.duration(187L)).isEqualTo("3m 07s");
        assertThat(CiTools.duration(3720L)).isEqualTo("1h 02m");
    }
}
