package systems.grebe.devtools.mcp.modules.dolt;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import systems.grebe.devtools.mcp.modules.dolt.DoltBranches.Outcome;
import systems.grebe.devtools.mcp.modules.dolt.DoltBranches.Status;
import systems.grebe.devtools.mcp.modules.dolt.DoltDatabase.Kind;

import static org.assertj.core.api.Assertions.assertThat;

class DoltBranchesTest {

    private static final DoltBranches.Settings SETTINGS = new DoltBranches.Settings(5, "");

    @TempDir
    Path repo;

    private Git git;
    private final FakeDoltServer server = new FakeDoltServer();
    private final List<String> switched = new ArrayList<>();
    private DoltBranches branches;

    @BeforeEach
    void setUp() throws Exception {
        git = Git.init().setDirectory(repo.toFile()).setInitialBranch("main").call();
        git.commit().setMessage("init").setAllowEmpty(true).call();
        branches = new DoltBranches(server, db -> switched.add(db.name()));
    }

    @AfterEach
    void tearDown() {
        git.close();
    }

    private DoltDatabase db(String baseBranch) {
        return new DoltDatabase("app", Kind.DOLT, repo.toString(), "localhost:3306/app", "root", "pw", baseBranch);
    }

    private void checkout(String branch, boolean create) throws Exception {
        git.checkout().setCreateBranch(create).setName(branch).call();
    }

    @Test
    void createsMissingBranchFromTheCurrentOneAndSwitches() throws Exception {
        DoltDatabase db = db("");
        assertThat(branches.syncIfNeeded(db, SETTINGS, Duration.ZERO)).get().extracting(Outcome::status)
                .isEqualTo(Status.IN_SYNC);

        checkout("feature/ISSUE-33", true);
        Outcome o = branches.syncIfNeeded(db, SETTINGS, Duration.ZERO).orElseThrow();
        assertThat(o.status()).isEqualTo(Status.SWITCHED);
        assertThat(o.from()).isEqualTo("main");
        assertThat(o.describe()).isEqualTo("app (Dolt): Branch feature/ISSUE-33 von main angelegt und als "
                + "Standard-Branch eingestellt.");
        assertThat(server.defaultBranch).isEqualTo("feature/ISSUE-33");
        assertThat(switched).containsExactly("app");

        // wie git checkout -b: vom Branch, auf dem die Datenbank gerade steht
        checkout("feature/ISSUE-34", true);
        branches.syncIfNeeded(db, SETTINGS, Duration.ZERO);
        assertThat(server.branches).containsEntry("feature/ISSUE-34", "feature/ISSUE-33");

        // zurück auf einen vorhandenen Branch: nur umstellen
        checkout("main", false);
        o = branches.syncIfNeeded(db, SETTINGS, Duration.ZERO).orElseThrow();
        assertThat(o.status()).isEqualTo(Status.SWITCHED);
        assertThat(o.from()).isNull();
        assertThat(o.message()).isEqualTo("Standard-Branch von feature/ISSUE-34 auf main umgestellt.");
        assertThat(server.log).containsExactly("create feature/ISSUE-33 from main", "default feature/ISSUE-33",
                "create feature/ISSUE-34 from feature/ISSUE-33", "default feature/ISSUE-34", "default main");
    }

    @Test
    void doesNothingWhileTheGitBranchStaysTheSame() throws Exception {
        DoltDatabase db = db("");
        checkout("feature/a", true);
        assertThat(branches.syncIfNeeded(db, SETTINGS, Duration.ZERO)).isPresent();
        int opened = server.opened;
        assertThat(branches.syncIfNeeded(db, SETTINGS, Duration.ZERO)).isEmpty();
        assertThat(server.opened).isEqualTo(opened); // keine Verbindung ohne Wechsel

        // geänderte Einstellungen gelten als Wechsel
        DoltDatabase other = new DoltDatabase("app", Kind.DOLT, repo.toString(), "localhost:3307/app", "root", "pw", "");
        assertThat(branches.syncIfNeeded(other, SETTINGS, Duration.ZERO)).get().extracting(Outcome::status)
                .isEqualTo(Status.IN_SYNC);
        // erzwungen verbindet immer
        assertThat(branches.sync(other, SETTINGS).status()).isEqualTo(Status.IN_SYNC);
        assertThat(server.opened).isEqualTo(opened + 2);
    }

    @Test
    void usesTheConfiguredStartPoint() throws Exception {
        server.branches.put("develop", "main");
        server.defaultBranch = "develop";
        checkout("feature/b", true);
        Outcome o = branches.sync(db("main"), SETTINGS);
        assertThat(o.from()).isEqualTo("main");
        assertThat(server.branches).containsEntry("feature/b", "main");
    }

    @Test
    void fallsBackToMainWhenTheDefaultBranchIsGone() throws Exception {
        server.defaultBranch = "geloescht";
        checkout("feature/c", true);
        Outcome o = branches.sync(db(""), SETTINGS);
        assertThat(o.status()).isEqualTo(Status.SWITCHED);
        assertThat(o.from()).isEqualTo("main");
        assertThat(o.message()).contains("von main angelegt");
    }

    @Test
    void leavesTheDatabaseAloneOnDetachedHead() throws Exception {
        git.checkout().setName(git.getRepository().resolve("HEAD").name()).call();
        Outcome o = branches.sync(db(""), SETTINGS);
        assertThat(o.status()).isEqualTo(Status.DETACHED);
        assertThat(server.opened).isZero();
    }

    @Test
    void reportsDatabasesThatCannotSwitchTheDefaultBranch() throws Exception {
        server.canSetDefault = false;
        checkout("feature/d", true);
        Outcome o = branches.sync(db(""), SETTINGS);
        assertThat(o.status()).isEqualTo(Status.NOT_SWITCHABLE);
        assertThat(o.message()).startsWith("Branch feature/d von main angelegt; Dolt kann den Standard-Branch")
                .contains("bleibt main").contains("fake/app/feature/d");
        assertThat(server.defaultBranch).isEqualTo("main");
        assertThat(switched).isEmpty();
    }

    @Test
    void retriesFailuresAfterAPauseAndReportsEachChangeOnce() throws Exception {
        DoltDatabase db = db("");
        server.running = false;
        checkout("feature/e", true);
        Outcome failed = branches.syncIfNeeded(db, SETTINGS, Duration.ofMinutes(1)).orElseThrow();
        assertThat(failed.status()).isEqualTo(Status.FAILED);
        assertThat(failed.message()).contains("Connection refused");
        assertThat(branches.syncIfNeeded(db, SETTINGS, Duration.ofMinutes(1))).isEmpty(); // Pause
        assertThat(branches.takeUnreported(List.of(db), Duration.ofMinutes(1))).containsExactly(failed);

        // derselbe Fehler wird nicht noch einmal gemeldet
        branches.syncIfNeeded(db, SETTINGS, Duration.ZERO);
        assertThat(branches.takeUnreported(List.of(db), Duration.ofMinutes(1))).isEmpty();

        server.running = true;
        Outcome ok = branches.syncIfNeeded(db, SETTINGS, Duration.ZERO).orElseThrow();
        assertThat(ok.status()).isEqualTo(Status.SWITCHED);
        assertThat(branches.takeUnreported(List.of(db), Duration.ofMinutes(1))).containsExactly(ok);
        assertThat(branches.takeUnreported(List.of(db), Duration.ofMinutes(1))).isEmpty();
        assertThat(branches.last(db)).contains(ok);
    }

    @Test
    void reportsUnreadableRepositories(@TempDir Path empty) {
        DoltDatabase db = new DoltDatabase("app", Kind.DOLT, empty.toString(), "h/app", "", null, "");
        Outcome o = branches.syncIfNeeded(db, SETTINGS, Duration.ofMinutes(1)).orElseThrow();
        assertThat(o.status()).isEqualTo(Status.FAILED);
        assertThat(o.message()).contains("Kein Git-Arbeitsverzeichnis");
        assertThat(branches.syncIfNeeded(db, SETTINGS, Duration.ofMinutes(1))).isEmpty();
    }
}
