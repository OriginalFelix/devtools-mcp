package systems.grebe.devtools.mcp.modules.git;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
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

/** Erweiterte Git-Tools: Lesen (Tags, Vergleich, Suche …), Stash/Tag/Reset, Integrieren, Verwerfen, Remote, Worktrees. */
class GitToolsExtendedTest {

    @TempDir
    Path parent;
    @TempDir
    Path outside;

    Path repo;
    GitSupport support;
    GitReadTools read;
    GitWriteTools write;
    GitIntegrateTools integrate;
    GitDiscardTools discard;

    @BeforeEach
    void setUp() throws Exception {
        repo = Files.createDirectory(parent.resolve("demo"));
        try (Git git = Git.init().setDirectory(repo.toFile()).setInitialBranch("main").call()) {
            var cfg = git.getRepository().getConfig();
            cfg.setString("user", null, "name", "Tester");
            cfg.setString("user", null, "email", "t@example.com");
            cfg.setBoolean("commit", null, "gpgsign", false);
            cfg.setBoolean("tag", null, "gpgsign", false);
            cfg.save();
            commit(git, "App.java", "class App {\n  int a = 1;\n}\n", "Erster Commit");
            commit(git, "App.java", "class App {\n  int a = 2;\n  // TODO aufräumen\n}\n", "Wert geändert");
        }
        support = support(Map.of());
    }

    private GitSupport support(Map<String, String> extra) {
        Map<String, String> v = new HashMap<>(Map.of(GitModule.REPOSITORIES, parent.toString()));
        v.putAll(extra);
        GitSupport s = new GitSupport(ModuleConfig.of(new GitModule().configSchema(), v));
        read = new GitReadTools(s);
        write = new GitWriteTools(s);
        integrate = new GitIntegrateTools(s);
        discard = new GitDiscardTools(s);
        return s;
    }

    private static void commit(Git git, String file, String content, String message) throws Exception {
        Path f = git.getRepository().getWorkTree().toPath().resolve(file);
        Files.createDirectories(f.getParent());
        Files.writeString(f, content);
        git.add().addFilepattern(".").call();
        git.commit().setMessage(message).call();
    }

    private Git open() throws Exception {
        return Git.open(repo.toFile());
    }

    private static boolean gitInstalled() {
        try {
            return CommandRunner.run(List.of("git", "--version"), Duration.ofSeconds(10)).ok();
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ Modul

    @Test
    void toolsAppearPerSwitch() {
        GitModule module = new GitModule();
        var base = ModuleConfig.of(module.configSchema(), Map.of(GitModule.REPOSITORIES, parent.toString()));
        assertThat(module.createTools(base)).extracting(t -> t.getToolDefinition().name())
                .contains("tags", "remotes", "stash_list", "reflog", "compare", "grep", "stash", "tag", "reset",
                        "rename_branch")
                .doesNotContain("push", "merge", "restore");
        var all = ModuleConfig.of(module.configSchema(), Map.of(GitModule.REPOSITORIES, parent.toString(),
                GitModule.ALLOW_SYNC, "true", GitModule.ALLOW_INTEGRATE, "true", GitModule.ALLOW_DISCARD, "true"));
        assertThat(module.createTools(all)).extracting(t -> t.getToolDefinition().name())
                .contains("fetch", "pull", "push", "merge", "rebase", "cherry_pick", "revert", "continue", "abort",
                        "restore", "delete_branch", "delete_tag", "stash_drop");
        assertThat(module.instructions()).contains("`git_compare`", "`git_continue`", "<repository>/<ordner>");
    }

    // ------------------------------------------------------------------ Lesen

    @Test
    void tagsReflogCompareGrepAndPickaxe() throws Exception {
        assertThat(write.tag(null, "v1.0", "HEAD~1", "Release 1")).contains("Annotierter Tag 'v1.0'");
        assertThat(write.tag(null, "latest", null, null)).startsWith("Tag 'latest'");
        assertThat(read.tags(null, null)).contains("latest", "v1.0", "„Release 1“");
        assertThat(read.tags(null, "v1")).contains("v1.0").doesNotContain("latest");

        try (Git git = open()) {
            git.checkout().setCreateBranch(true).setName("feature").call();
            commit(git, "src/Feature.java", "class Feature { String token = \"geheim\"; }\n", "Feature hinzugefügt");
            git.checkout().setName("main").call();
            commit(git, "README.md", "# Demo\n", "Readme");
        }
        String cmp = read.compare(null, "main", "feature", null);
        assertThat(cmp).contains("feature gegenüber main: 1 voraus, 1 zurück", "merge-base:", "Wert geändert",
                "Nur in feature (1)", "Feature hinzugefügt", "Nur in main (1)", "Readme", "git_diff from=");

        assertThat(read.reflog(null, null, 3)).contains("HEAD@{0}", "commit");

        assertThat(read.grep(null, "todo", null, null, null, ".java", null, null)).contains("App.java:3: // TODO aufräumen");
        assertThat(read.grep(null, "token\\s*=", true, true, null, null, "feature", null))
                .contains("src/Feature.java:1:");
        assertThat(read.grep(null, "token", null, null, null, null, null, null)).startsWith("(keine Treffer");
        assertThatThrownBy(() -> read.grep(null, "(", true, null, null, null, null, null))
                .hasMessageContaining("Ungültiger regulärer Ausdruck");

        // wann kam "int a = 2" hinein? – nur der Commit, der die Anzahl ändert
        String pick = read.log(null, null, null, null, null, null, "int a = 2");
        assertThat(pick).contains("Wert geändert").doesNotContain("Erster Commit").doesNotContain("Readme");
    }

    @Test
    void remotesHidePasswords() throws Exception {
        try (Git git = open()) {
            var cfg = git.getRepository().getConfig();
            cfg.setString("remote", "origin", "url", "https://user:secret@example.com/demo.git");
            cfg.save();
        }
        assertThat(read.remotes(null)).contains("origin", "fetch: https://user@example.com/demo.git")
                .doesNotContain("secret");
    }

    // ------------------------------------------------------------------ Schreiben

    @Test
    void stashRenameAndResetWithDiscardGate() throws Exception {
        Files.writeString(repo.resolve("App.java"), "geändert\n");
        Files.writeString(repo.resolve("neu.txt"), "x");
        assertThat(write.stash(null, "push", "WIP", true, null)).contains("stash@{0}");
        assertThat(read.status(null)).contains("sauber");
        assertThat(read.stashList(null)).contains("stash@{0}", "WIP");
        assertThat(write.stash(null, "pop", null, null, null)).contains("entfernt");
        assertThat(Files.readString(repo.resolve("App.java")).strip()).isEqualTo("geändert");
        assertThat(read.stashList(null)).isEqualTo("(keine Stashes)");
        assertThatThrownBy(() -> write.stash(null, "clear", null, null, null)).hasMessageContaining("git_stash_drop");

        assertThat(write.renameBranch(null, null, "trunk")).contains("'main' in 'trunk'");

        assertThat(write.reset(null, "HEAD~1", "soft")).contains("zurückgesetzt (soft)");
        assertThat(read.status(null)).contains("Gestaged (geändert)");
        assertThatThrownBy(() -> write.reset(null, "HEAD", "hard")).hasMessageContaining("Verwerfen und Löschen");
        support(Map.of(GitModule.ALLOW_DISCARD, "true"));
        write.reset(null, "HEAD", "hard");
        assertThat(read.status(null)).contains("Unversioniert (1)").doesNotContain("Gestaged");
    }

    // ------------------------------------------------------------------ Integrieren

    @Test
    void mergeConflictShowsStateThenAbortRestores() throws Exception {
        try (Git git = open()) {
            git.checkout().setCreateBranch(true).setName("other").call();
            commit(git, "App.java", "class App {\n  int a = 99;\n}\n", "Andere Änderung");
            git.checkout().setName("main").call();
            commit(git, "App.java", "class App {\n  int a = 3;\n}\n", "Haupt-Änderung");
        }
        String out = integrate.merge(null, "other", null, null);
        assertThat(out).contains("Konflikte in:", "App.java", "git_continue");
        assertThat(read.status(null)).contains("Zustand: Merge mit Konflikten", "Konflikte (1)");
        assertThatThrownBy(() -> integrate.continueOperation(null, null)).hasMessageContaining("ungelöste Konflikte");
        assertThatThrownBy(() -> integrate.rebase(null, "other")).hasMessageContaining("Es läuft bereits");

        assertThat(integrate.abort(null)).contains("abgebrochen");
        assertThat(read.status(null)).contains("sauber").doesNotContain("Zustand");
        assertThat(Files.readString(repo.resolve("App.java"))).contains("int a = 3");
        assertThat(integrate.abort(null)).contains("Es läuft kein");
    }

    @Test
    void mergeConflictResolvedAndContinued() throws Exception {
        try (Git git = open()) {
            git.checkout().setCreateBranch(true).setName("other").call();
            commit(git, "App.java", "class App {\n  int a = 99;\n}\n", "Andere Änderung");
            git.checkout().setName("main").call();
            commit(git, "App.java", "class App {\n  int a = 3;\n}\n", "Haupt-Änderung");
        }
        integrate.merge(null, "other", null, null);
        Files.writeString(repo.resolve("App.java"), "class App {\n  int a = 100;\n}\n");
        write.stage(null, List.of("App.java"));
        assertThat(integrate.continueOperation(null, null)).contains("abgeschlossen: Commit", "Merge");
        assertThat(read.log(null, null, null, null, 1, null, null)).contains("Merge");
        assertThat(read.status(null)).contains("sauber");
    }

    @Test
    void fastForwardRebaseCherryPickAndRevert() throws Exception {
        try (Git git = open()) {
            git.checkout().setCreateBranch(true).setName("feature").call();
            commit(git, "f1.txt", "1", "F1");
            commit(git, "f2.txt", "2", "F2");
            git.checkout().setName("main").call();
        }
        assertThat(integrate.merge(null, "feature", "ff-only", null)).contains("Fast-Forward");

        try (Git git = open()) {
            git.checkout().setCreateBranch(true).setName("topic").setStartPoint("HEAD~2").call();
            commit(git, "t.txt", "t", "Topic");
        }
        assertThat(integrate.rebase(null, "main")).contains("topic auf main rebased");
        assertThat(read.compare(null, "main", "topic", null)).contains("1 voraus, 0 zurück");

        write.checkout(null, "main");
        String hash = read.log(null, "topic", null, null, 1, null, null).substring(0, 8);
        assertThat(integrate.cherryPick(null, List.of(hash))).contains("Übernommen: " + hash, "Topic");
        assertThat(Files.exists(repo.resolve("t.txt"))).isTrue();

        assertThat(integrate.revert(null, "HEAD")).contains("Revert-Commit", "Revert \"Topic\"");
        assertThat(Files.exists(repo.resolve("t.txt"))).isFalse();
    }

    // ------------------------------------------------------------------ Verwerfen

    @Test
    void restoreDeleteBranchTagAndStash() throws Exception {
        Files.writeString(repo.resolve("App.java"), "kaputt\n");
        Files.writeString(repo.resolve("tmp.log"), "x");
        assertThatThrownBy(() -> discard.restore(null, null, null, null, null)).hasMessageContaining("Pfade fehlen");
        assertThat(discard.restore(null, List.of("App.java"), null, null, null)).contains("Verworfen (1)", "App.java");
        assertThat(Files.readString(repo.resolve("App.java"))).contains("int a = 2");
        assertThat(discard.restore(null, null, true, null, true)).contains("Unversioniert gelöscht (1)", "tmp.log");
        assertThat(Files.exists(repo.resolve("tmp.log"))).isFalse();

        try (Git git = open()) {
            git.checkout().setCreateBranch(true).setName("unmerged").call();
            commit(git, "u.txt", "u", "Unmerged");
            git.checkout().setName("main").call();
        }
        assertThatThrownBy(() -> discard.deleteBranch(null, "main", null)).hasMessageContaining("ausgecheckt");
        assertThatThrownBy(() -> discard.deleteBranch(null, "unmerged", null)).hasMessageContaining("nicht in den aktuellen Branch gemergt");
        assertThat(discard.deleteBranch(null, "unmerged", true)).contains("gelöscht");

        write.tag(null, "weg", null, null);
        assertThat(discard.deleteTag(null, "weg")).contains("gelöscht");
        assertThatThrownBy(() -> discard.deleteTag(null, "weg")).hasMessageContaining("existiert nicht");

        Files.writeString(repo.resolve("App.java"), "a\n");
        write.stash(null, "push", null, null, null);
        assertThatThrownBy(() -> discard.stashDrop(null, 3, null)).hasMessageContaining("existiert nicht");
        assertThat(discard.stashDrop(null, null, null)).contains("stash@{0} gelöscht");
    }

    // ------------------------------------------------------------------ Remote und Worktrees (installiertes git)

    @Test
    void fetchPullAndPushAgainstBareRemote() throws Exception {
        assumeTrue(gitInstalled(), "git nicht installiert");
        Path remote = outside.resolve("remote.git");
        Git.init().setBare(true).setDirectory(remote.toFile()).setInitialBranch("main").call().close();
        try (Git git = open()) {
            var cfg = git.getRepository().getConfig();
            cfg.setString("remote", "origin", "url", remote.toUri().toString());
            cfg.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*");
            cfg.save();
        }
        GitSyncTools sync = new GitSyncTools(support(Map.of()));
        assertThatThrownBy(() -> sync.push(null, null, null, null)).hasMessageContaining("geschützt");
        write.createBranch(null, "feature/x", null, null);
        assertThat(sync.push(null, null, null, null)).contains("Branch feature/x auf origin gepusht", "0 voraus, 0 zurück");
        assertThatThrownBy(() -> sync.push(null, "--force", null, null)).hasMessageContaining("Ungültiger");

        // zweiter Klon schiebt einen Commit nach, dann fetch + pull
        Path other = outside.resolve("other");
        try (Git clone = Git.cloneRepository().setURI(remote.toUri().toString()).setDirectory(other.toFile())
                .setBranch("feature/x").call()) {
            var cfg = clone.getRepository().getConfig();
            cfg.setString("user", null, "name", "Zweiter");
            cfg.setString("user", null, "email", "z@example.com");
            cfg.setBoolean("commit", null, "gpgsign", false);
            cfg.save();
            commit(clone, "remote.txt", "r", "Vom Remote");
            clone.push().call();
        }
        assertThat(sync.fetch(null, null, null)).contains("0 voraus, 1 zurück");
        assertThat(sync.pull(null, null, null, null)).contains("0 voraus, 0 zurück");
        assertThat(Files.exists(repo.resolve("remote.txt"))).isTrue();

        write.tag(null, "v2", null, "Version 2");
        assertThat(sync.push(null, null, null, "v2")).contains("Tag v2 auf origin gepusht");
        try (Git bare = Git.open(remote.toFile())) {
            assertThat(bare.getRepository().exactRef("refs/tags/v2")).isNotNull();
        }
    }

    @Test
    void worktreesAreListedAndResolvedByNameOrPath() throws Exception {
        assumeTrue(gitInstalled(), "git nicht installiert");
        Path wt = repo.resolve(".claude").resolve("worktrees").resolve("wt1");
        CommandRunner.run(List.of("git", "worktree", "add", "-b", "wt-branch", wt.toString()), Duration.ofSeconds(30),
                java.nio.charset.StandardCharsets.UTF_8, repo).orThrow("git worktree add");
        GitSupport s = support(Map.of());
        assertThat(s.worktrees()).extracting(GitSupport.Worktree::name).containsExactly("demo/wt1");
        assertThat(read.listRepositories()).contains("demo  [main]", "demo/wt1  [wt-branch]", "(Worktree)");
        assertThat(read.status("demo/wt1")).contains("Branch: wt-branch");
        // Pfad im Worktree (liegt im Haupt-Repository) → Worktree, nicht das Haupt-Repository
        assertThat(read.status(wt.resolve("App.java").toString())).contains("Branch: wt-branch");
        Files.writeString(wt.resolve("wt.txt"), "w");
        write.stage("demo/wt1", List.of("wt.txt"));
        assertThat(write.commit("demo/wt1", "Im Worktree", false)).contains("auf wt-branch");
        assertThat(read.status("demo")).contains("Branch: main");
    }
}
