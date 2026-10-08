package systems.grebe.devtools.mcp.modules.dolt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import systems.grebe.devtools.mcp.core.CommandRunner;
import systems.grebe.devtools.mcp.modules.dolt.DoltBranches.Outcome;
import systems.grebe.devtools.mcp.modules.dolt.DoltBranches.Status;
import systems.grebe.devtools.mcp.modules.dolt.DoltDatabase.Kind;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Doltlite über das echte Programm. Übersprungen, wenn es weder per {@code -Pdoltlite=…} angegeben noch im PATH ist
 * (Download: github.com/dolthub/doltlite/releases).
 */
class DoltliteBackendTest {

    static String program;

    @TempDir
    Path tmp;

    @BeforeAll
    static void findProgram() {
        String configured = System.getProperty("devtools.test.doltlite");
        for (String candidate : configured == null ? List.of("doltlite") : List.of(configured)) {
            try {
                if (CommandRunner.run(List.of(candidate, "-version"), Duration.ofSeconds(10)).ok()) {
                    program = candidate;
                }
            } catch (RuntimeException e) {
                // nicht installiert
            }
        }
        Assumptions.assumeTrue(program != null, "doltlite nicht gefunden");
    }

    private String sql(Path file, String sql) {
        return CommandRunner.run(List.of(program, "-bail", "-json", file.toString()), Duration.ofSeconds(20),
                StandardCharsets.UTF_8, null, Map.of(), sql).orThrow("doltlite").output().strip();
    }

    @Test
    void followsTheGitBranch() throws Exception {
        Path file = tmp.resolve("app.db");
        sql(file, "CREATE TABLE kunden(id INTEGER PRIMARY KEY, name TEXT); INSERT INTO kunden VALUES (1, 'Müller');"
                + " SELECT dolt_commit('-Am', 'init');");
        Path repo = tmp.resolve("repo");
        try (Git git = Git.init().setDirectory(repo.toFile()).setInitialBranch("main").call()) {
            git.commit().setMessage("init").setAllowEmpty(true).call();
            DoltDatabase db = new DoltDatabase("lite", Kind.DOLTLITE, repo.toString(), file.toString(), "", null, "");
            DoltBranches branches = new DoltBranches(
                    (d, s) -> DoltliteBackend.open(d, s.doltlite(), s.timeoutSeconds()), d -> { });
            DoltBranches.Settings settings = new DoltBranches.Settings(10, program);

            assertThat(branches.sync(db, settings).status()).isEqualTo(Status.IN_SYNC);

            git.checkout().setCreateBranch(true).setName("feature/ISSUE-33").call();
            long start = System.nanoTime();
            Outcome o = branches.syncIfNeeded(db, settings, Duration.ZERO).orElseThrow();
            long millis = (System.nanoTime() - start) / 1_000_000;
            System.out.println("Doltlite: Branch anlegen und umstellen in " + millis + " ms");
            assertThat(o.status()).isEqualTo(Status.SWITCHED);
            assertThat(o.from()).isEqualTo("main");
            // eine neue Verbindung ohne Branch-Angabe landet auf dem neuen Branch – mit den Daten von main
            assertThat(sql(file, "SELECT active_branch() AS b, count(*) AS n FROM kunden"))
                    .isEqualTo("[{\"b\":\"feature/ISSUE-33\",\"n\":1}]");

            // Änderungen auf dem Feature-Branch bleiben dort
            sql(file, "INSERT INTO kunden VALUES (2, 'Schmidt'); SELECT dolt_commit('-Am', 'feature');");
            git.checkout().setName("main").call();
            assertThat(branches.syncIfNeeded(db, settings, Duration.ZERO).orElseThrow().message())
                    .isEqualTo("Standard-Branch von feature/ISSUE-33 auf main umgestellt.");
            assertThat(sql(file, "SELECT active_branch() AS b, count(*) AS n FROM kunden"))
                    .isEqualTo("[{\"b\":\"main\",\"n\":1}]");

            try (DoltBackend b = DoltliteBackend.open(db, program, 10)) {
                assertThat(b.branches()).containsExactly("feature/ISSUE-33", "main");
                assertThat(b.version()).startsWith("Doltlite");
                assertThatThrownBy(() -> b.setDefault("gibt-es-nicht")).hasMessageContaining("not found");
            }
        }
    }

    @Test
    void neverCreatesAMissingFile() {
        Path missing = tmp.resolve("fehlt.db");
        DoltDatabase db = new DoltDatabase("lite", Kind.DOLTLITE, tmp.toString(), missing.toString(), "", null, "");
        assertThatThrownBy(() -> DoltliteBackend.open(db, program, 10)).hasMessageContaining("nicht gefunden");
        assertThat(Files.exists(missing)).isFalse();
    }
}
