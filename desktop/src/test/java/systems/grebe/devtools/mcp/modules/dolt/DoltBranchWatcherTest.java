package systems.grebe.devtools.mcp.modules.dolt;

import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import systems.grebe.devtools.mcp.core.ToolCallListener.ToolCall;
import systems.grebe.devtools.mcp.core.ToolRegistry;
import systems.grebe.devtools.mcp.modules.dolt.DoltDatabase.Kind;

import static org.assertj.core.api.Assertions.assertThat;

class DoltBranchWatcherTest {

    @TempDir
    Path repo;

    private Git git;
    private final FakeDoltServer server = new FakeDoltServer();
    private DoltBranchWatcher watcher;
    private DoltDatabase db;

    @BeforeEach
    void setUp() throws Exception {
        git = Git.init().setDirectory(repo.toFile()).setInitialBranch("main").call();
        git.commit().setMessage("init").setAllowEmpty(true).call();
        DoltBranches branches = new DoltBranches(server, d -> { });
        watcher = new DoltBranchWatcher(new StaticListableBeanFactory().getBeanProvider(ToolRegistry.class), branches);
        db = new DoltDatabase("app", Kind.DOLT, repo.toString(), "localhost:3306/app", "root", "pw", "");
    }

    @AfterEach
    void tearDown() {
        watcher.stop();
        git.close();
    }

    @Test
    void switchesTheDatabaseWhenTheBranchChangesOutsideOfDevTools() throws Exception {
        watcher.apply(new DoltBranchWatcher.Snapshot(List.of(db), new DoltBranches.Settings(5, "")));
        await(() -> server.opened > 0); // Abgleich beim Einschalten

        long start = System.nanoTime();
        git.checkout().setCreateBranch(true).setName("feature/watch").call(); // wie IDE oder Shell
        await(() -> "feature/watch".equals(server.defaultBranch));
        long millis = (System.nanoTime() - start) / 1_000_000;
        // über den WatchService, nicht erst über die Abfrage alle 10 s
        assertThat(millis).as("Reaktionszeit in ms").isLessThan(DoltBranchWatcher.POLL.toMillis() / 2);
        assertThat(server.branches).containsEntry("feature/watch", "main");
    }

    @Test
    void watchServiceCreatedAfterAFailedFirstAttemptIsPolled() throws Exception {
        watcher.watchServices = () -> {
            throw new java.io.IOException("inotify-Grenze erreicht");
        };
        DoltBranchWatcher.Snapshot snapshot = new DoltBranchWatcher.Snapshot(List.of(db),
                new DoltBranches.Settings(5, ""));
        watcher.apply(snapshot);
        await(() -> server.opened > 0); // Abgleich beim Einschalten, die Schleife läuft ohne WatchService

        watcher.watchServices = () -> java.nio.file.FileSystems.getDefault().newWatchService();
        watcher.apply(new DoltBranchWatcher.Snapshot(List.of(db), new DoltBranches.Settings(6, ""))); // Neukonfiguration
        Thread.sleep(200);

        long start = System.nanoTime();
        git.checkout().setCreateBranch(true).setName("feature/spaeter").call();
        await(() -> "feature/spaeter".equals(server.defaultBranch));
        long millis = (System.nanoTime() - start) / 1_000_000;
        // bis zu einer Abfrage-Runde (10 s) wartete die alte Schleife, weil sie den neuen WatchService nie sah
        assertThat(millis).as("Reaktionszeit in ms").isLessThan(DoltBranchWatcher.POLL.toMillis() / 2);
    }

    @Test
    void reportsTheSwitchInTheResultOfGitTools() throws Exception {
        watcher.apply(new DoltBranchWatcher.Snapshot(List.of(db), new DoltBranches.Settings(5, "")));
        await(() -> server.opened > 0);
        git.checkout().setCreateBranch(true).setName("feature/tool").call();

        String result = watcher.afterSuccess(new ToolCall("git", "git_checkout", "{}", null), "Gewechselt.");
        assertThat(result).startsWith("Gewechselt.\n\nDatenbank-Branches (DevTools):\n- app (Dolt): Branch "
                + "feature/tool von main angelegt und als Standard-Branch eingestellt.");
        assertThat(server.defaultBranch).isEqualTo("feature/tool");
        // nur einmal gemeldet, andere Module unberührt
        assertThat(watcher.afterSuccess(new ToolCall("git", "git_status", "{}", null), "ok")).isEqualTo("ok");
        assertThat(watcher.afterSuccess(new ToolCall("jdbc", "jdbc_query", "{}", null), "x")).isEqualTo("x");
    }

    @Test
    void staysIdleWhileTheModuleIsOff() throws Exception {
        watcher.apply(DoltBranchWatcher.Snapshot.EMPTY);
        git.checkout().setCreateBranch(true).setName("feature/off").call();
        assertThat(watcher.afterSuccess(new ToolCall("git", "git_checkout", "{}", null), "ok")).isEqualTo("ok");
        Thread.sleep(300);
        assertThat(server.opened).isZero();
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Bedingung nicht erfüllt");
            }
            Thread.sleep(10);
        }
    }
}
