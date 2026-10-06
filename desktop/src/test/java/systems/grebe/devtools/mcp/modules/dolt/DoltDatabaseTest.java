package systems.grebe.devtools.mcp.modules.dolt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import systems.grebe.devtools.mcp.modules.dolt.DoltDatabase.Kind;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DoltDatabaseTest {

    private static DoltDatabase db(Kind kind, String location) {
        return new DoltDatabase("app", kind, "", location, "root", "geheim-123", "");
    }

    @Test
    void parsesServerLocations() {
        assertThat(db(Kind.DOLT, "localhost:3307/app").server())
                .isEqualTo(new DoltDatabase.Server("localhost", 3307, "app", ""));
        assertThat(db(Kind.DOLT, "db.intern/app").server().port()).isEqualTo(3306);
        assertThat(db(Kind.DOLTGRES, "db.intern/app").server().port()).isEqualTo(5432);
        assertThat(db(Kind.DOLT, "jdbc:mysql://bob:pw@h:1/app?useSSL=false").server())
                .isEqualTo(new DoltDatabase.Server("h", 1, "app", "useSSL=false"));
        assertThat(db(Kind.DOLTGRES, "postgresql://h:5433/my%20db/").server().database()).isEqualTo("my db");
        assertThatThrownBy(() -> db(Kind.DOLT, "localhost:3306").server()).hasMessageContaining("host[:port]/datenbank");
        assertThatThrownBy(() -> db(Kind.DOLT, "localhost:3306/app/feature").server())
                .hasMessageContaining("Branch");
    }

    @Test
    void neverPrintsThePassword() {
        DoltDatabase d = DoltDatabase.of(Map.of("name", "app", "kind", "doltgres", "location", "h/app",
                "password", "geheim-123"));
        assertThat(d.kind()).isEqualTo(Kind.DOLTGRES);
        assertThat(d.toString()).doesNotContain("geheim-123");
        assertThat(DoltDatabase.of(Map.of("name", "x", "kind", "unbekannt")).kind()).isEqualTo(Kind.DOLT);
    }

    @Test
    void recognizesJdbcUrlsOnTheDefaultBranch() {
        DoltDatabase d = db(Kind.DOLT, "localhost:3306/app");
        assertThat(DoltBranches.pointsTo("jdbc:mysql://127.0.0.1:3306/app?useSSL=false", d)).isTrue();
        assertThat(DoltBranches.pointsTo("jdbc:mariadb://localhost/APP", d)).isTrue();
        assertThat(DoltBranches.pointsTo("jdbc:mysql://localhost:3306/app%2Ffeature", d)).isFalse();
        assertThat(DoltBranches.pointsTo("jdbc:mysql://localhost:3307/app", d)).isFalse();
        assertThat(DoltBranches.pointsTo("jdbc:mysql://other:3306/app", d)).isFalse();
        assertThat(DoltBranches.pointsTo("jdbc:postgresql://localhost:3306/app", d)).isFalse();
        assertThat(DoltBranches.pointsTo("jdbc:mysql://localhost:3306/app", db(Kind.DOLTGRES, "localhost:3306/app")))
                .isFalse();
    }

    @Test
    void readsBranchOfRepositoriesAndWorktrees(@TempDir Path tmp) throws Exception {
        Path repo = tmp.resolve("repo");
        try (Git git = Git.init().setDirectory(repo.toFile()).setInitialBranch("main").call()) {
            git.commit().setMessage("init").setAllowEmpty(true).call();
            git.checkout().setCreateBranch(true).setName("feature/ISSUE-33").call();
            assertThat(GitHead.branch(GitHead.gitDir(repo))).isEqualTo("feature/ISSUE-33");

            git.checkout().setName(git.getRepository().resolve("HEAD").name()).call();
            assertThat(GitHead.branch(GitHead.gitDir(repo))).isNull(); // losgelöst
        }
        // Worktree: .git ist eine Datei mit Verweis auf .git/worktrees/<name>
        Path meta = Files.createDirectories(repo.resolve(".git/worktrees/wt"));
        Files.writeString(meta.resolve("HEAD"), "ref: refs/heads/wt-branch\n");
        Path wt = Files.createDirectories(tmp.resolve("wt"));
        Files.writeString(wt.resolve(".git"), "gitdir: " + meta + "\n");
        assertThat(GitHead.gitDir(wt)).isEqualTo(meta);
        assertThat(GitHead.branch(GitHead.gitDir(wt))).isEqualTo("wt-branch");

        assertThatThrownBy(() -> GitHead.gitDir(tmp.resolve("leer"))).hasMessageContaining("Kein Git-Arbeitsverzeichnis");
    }
}
