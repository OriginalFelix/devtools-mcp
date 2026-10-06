package systems.grebe.devtools.mcp.modules.dolt;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import systems.grebe.devtools.mcp.core.CommandRunner;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolScope;
import systems.grebe.devtools.mcp.modules.dolt.DoltBranches.Outcome;
import systems.grebe.devtools.mcp.modules.dolt.DoltBranches.Status;
import systems.grebe.devtools.mcp.modules.dolt.DoltDatabase.Kind;
import systems.grebe.devtools.mcp.modules.jdbc.JdbcModule;
import systems.grebe.devtools.mcp.plugin.store.MavenPluginResolver;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dolt- und Doltgres-Server in echten Containern (docker oder podman). Übersprungen, wenn keine Container-Laufzeit
 * oder das Image fehlt ({@code docker.io/dolthub/dolt-sql-server}, {@code docker.io/dolthub/doltgresql}).
 */
class DoltServerContainerTest {

    static final String DOLT_IMAGE = "docker.io/dolthub/dolt-sql-server:latest";
    static final String DOLTGRES_IMAGE = "docker.io/dolthub/doltgresql:latest";
    static final String PASSWORD = "geheim-123";

    static final List<String> started = new ArrayList<>();
    static String cli;

    @TempDir
    Path repo;

    private final JdbcModule jdbc = new JdbcModule(
            new StaticListableBeanFactory().getBeanProvider(MavenPluginResolver.class));

    @AfterAll
    static void removeContainers() {
        started.forEach(name -> CommandRunner.run(List.of(cli, "rm", "-f", name), Duration.ofSeconds(30)));
        ToolScope.LOCAL.close();
    }

    /** Startet den Container auf einem freien Port und wartet, bis er Verbindungen annimmt. */
    private static int start(String name, String image, int containerPort, List<String> env, String url, String user)
            throws Exception {
        if (cli == null) {
            for (String c : List.of("podman", "docker")) {
                try {
                    if (CommandRunner.run(List.of(c, "version"), Duration.ofSeconds(20)).ok()) {
                        cli = c;
                        break;
                    }
                } catch (RuntimeException ignored) {
                    // nicht installiert
                }
            }
        }
        Assumptions.assumeTrue(cli != null, "Keine Container-Laufzeit");
        Assumptions.assumeTrue(CommandRunner.run(List.of(cli, "image", "inspect", image), Duration.ofSeconds(20)).ok(),
                "Image " + image + " fehlt");
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        CommandRunner.run(List.of(cli, "rm", "-f", name), Duration.ofSeconds(30));
        List<String> cmd = new ArrayList<>(List.of(cli, "run", "-d", "--name", name, "-p", port + ":" + containerPort));
        env.forEach(e -> cmd.addAll(List.of("-e", e)));
        cmd.add(image);
        CommandRunner.run(cmd, Duration.ofMinutes(2)).orThrow("Container " + name + " starten");
        started.add(name);
        String jdbcUrl = url.replace("PORT", String.valueOf(port));
        long deadline = System.currentTimeMillis() + 90_000;
        while (true) {
            try (Connection c = DriverManager.getConnection(jdbcUrl, user, PASSWORD); Statement st = c.createStatement()) {
                st.execute("SELECT 1");
                return port;
            } catch (SQLException e) {
                if (System.currentTimeMillis() > deadline) {
                    throw e;
                }
                Thread.sleep(500);
            }
        }
    }

    private static List<String> rows(String url, String user, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(url, user, PASSWORD); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1) + (rs.getMetaData().getColumnCount() > 1 ? "|" + rs.getString(2) : ""));
            }
        }
        return out;
    }

    private static void execute(String url, String user, String... sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, user, PASSWORD); Statement st = c.createStatement()) {
            for (String s : sql) {
                st.execute(s);
            }
        }
    }

    @Test
    void doltFollowsTheGitBranch() throws Exception {
        int port = start("devtools-mcp-dolt-test", DOLT_IMAGE, 3306,
                List.of("DOLT_ROOT_HOST=%", "DOLT_ROOT_PASSWORD=" + PASSWORD, "DOLT_DATABASE=appdb"),
                "jdbc:mysql://localhost:PORT/appdb", "root");
        String url = "jdbc:mysql://localhost:" + port + "/appdb";
        execute(url, "root", "CREATE TABLE kunden (id INT PRIMARY KEY, name VARCHAR(50))",
                "INSERT INTO kunden VALUES (1, 'Müller')", "CALL DOLT_COMMIT('-Am', 'init')");

        // jdbc_query hält eine freie Verbindung auf dem Standard-Branch
        ModuleConfig jdbcConfig = ModuleConfig.of(jdbc.configSchema(), Map.of("connections",
                ModuleConfig.formatRecords(List.of(Map.of("name", "app", "url", url, "username", "root",
                        "password", PASSWORD)))));
        ToolCallback query = jdbc.createTools(jdbcConfig).stream()
                .filter(cb -> cb.getToolDefinition().name().equals("query")).findFirst().orElseThrow();
        String branchQuery = "{\"sql\": \"SELECT active_branch() AS b\"}";
        assertThat(query.call(branchQuery)).contains("main");

        try (Git git = Git.init().setDirectory(repo.toFile()).setInitialBranch("main").call()) {
            git.commit().setMessage("init").setAllowEmpty(true).call();
            DoltDatabase db = new DoltDatabase("app", Kind.DOLT, repo.toString(), "localhost:" + port + "/appdb",
                    "root", PASSWORD, "");
            DoltBranches branches = new DoltBranches(jdbc);
            DoltBranches.Settings settings = new DoltBranches.Settings(5, "");
            assertThat(branches.sync(db, settings).status()).isEqualTo(Status.IN_SYNC);

            git.checkout().setCreateBranch(true).setName("feature/ISSUE-33").call();
            long start = System.nanoTime();
            Outcome o = branches.syncIfNeeded(db, settings, Duration.ZERO).orElseThrow();
            System.out.println("Dolt: Branch anlegen und umstellen in " + (System.nanoTime() - start) / 1_000_000 + " ms");
            assertThat(o.status()).as(o.message()).isEqualTo(Status.SWITCHED);
            assertThat(o.from()).isEqualTo("main");
            assertThat(o.message()).doesNotContain(PASSWORD);

            // neue Verbindung ohne Branch-Angabe: neuer Branch mit den Daten von main
            assertThat(rows(url, "root", "SELECT active_branch(), (SELECT count(*) FROM kunden)"))
                    .containsExactly("feature/ISSUE-33|1");
            // die gepoolte jdbc_*-Verbindung wurde geschlossen und sieht jetzt ebenfalls den neuen Branch
            assertThat(query.call(branchQuery)).contains("feature/ISSUE-33");

            execute(url, "root", "INSERT INTO kunden VALUES (2, 'Schmidt')", "CALL DOLT_COMMIT('-Am', 'feature')");
            git.checkout().setName("main").call();
            assertThat(branches.syncIfNeeded(db, settings, Duration.ZERO).orElseThrow().message())
                    .isEqualTo("Standard-Branch von feature/ISSUE-33 auf main umgestellt.");
            assertThat(rows(url, "root", "SELECT active_branch(), (SELECT count(*) FROM kunden)"))
                    .containsExactly("main|1");

            // der Standard-Branch übersteht einen Neustart des Servers (SET PERSIST)
            git.checkout().setName("feature/ISSUE-33").call();
            branches.syncIfNeeded(db, settings, Duration.ZERO);
            CommandRunner.run(List.of(cli, "restart", "devtools-mcp-dolt-test"), Duration.ofMinutes(1))
                    .orThrow("Neustart");
            long deadline = System.currentTimeMillis() + 60_000;
            List<String> after = null;
            while (after == null) {
                try {
                    after = rows(url, "root", "SELECT active_branch(), (SELECT count(*) FROM kunden)");
                } catch (SQLException e) {
                    if (System.currentTimeMillis() > deadline) {
                        throw e;
                    }
                    Thread.sleep(500);
                }
            }
            assertThat(after).containsExactly("feature/ISSUE-33|2");

            DoltTools tools = new DoltTools(branches, List.of(db), settings);
            assertThat(tools.status(null)).contains("Branch feature/ISSUE-33").contains("(= Git-Branch)")
                    .contains("Branches (2): feature/ISSUE-33, main").doesNotContain(PASSWORD);
        }
    }

    @Test
    void doltgresGetsTheBranchButKeepsItsDefault() throws Exception {
        int port = start("devtools-mcp-doltgres-test", DOLTGRES_IMAGE, 5432,
                List.of("DOLTGRES_PASSWORD=" + PASSWORD, "DOLTGRES_DB=appdb"), "jdbc:postgresql://localhost:PORT/appdb",
                "postgres");
        String url = "jdbc:postgresql://localhost:" + port + "/appdb";
        execute(url, "postgres", "CREATE TABLE kunden (id INT PRIMARY KEY, name TEXT)",
                "INSERT INTO kunden VALUES (1, 'Müller')", "SELECT dolt_commit('-Am', 'init')");

        try (Git git = Git.init().setDirectory(repo.toFile()).setInitialBranch("main").call()) {
            git.commit().setMessage("init").setAllowEmpty(true).call();
            DoltDatabase db = new DoltDatabase("pg", Kind.DOLTGRES, repo.toString(), "localhost:" + port + "/appdb",
                    "postgres", PASSWORD, "");
            DoltBranches branches = new DoltBranches(jdbc);
            DoltBranches.Settings settings = new DoltBranches.Settings(5, "");
            assertThat(branches.sync(db, settings).status()).isEqualTo(Status.IN_SYNC);

            git.checkout().setCreateBranch(true).setName("feature/ISSUE-33").call();
            long start = System.nanoTime();
            Outcome o = branches.syncIfNeeded(db, settings, Duration.ZERO).orElseThrow();
            System.out.println("Doltgres: " + o.status() + " in " + (System.nanoTime() - start) / 1_000_000 + " ms – "
                    + o.message());
            assertThat(o.from()).isEqualTo("main");
            assertThat(rows(url, "postgres", "SELECT name FROM dolt_branches ORDER BY name"))
                    .containsExactly("feature/ISSUE-33", "main");
            // Doltgres 1.4 nimmt <db>_default_branch im SET nicht an; eine neuere Version darf umstellen
            assertThat(o.status()).isIn(Status.NOT_SWITCHABLE, Status.SWITCHED);
            if (o.status() == Status.NOT_SWITCHABLE) {
                assertThat(o.message()).contains("postgresql://localhost:" + port + "/appdb/feature/ISSUE-33");
                assertThat(rows(url, "postgres", "SELECT active_branch()")).containsExactly("main");
            } else {
                assertThat(rows(url, "postgres", "SELECT active_branch()")).containsExactly("feature/ISSUE-33");
            }
            // ausdrücklich auf den Branch verbinden geht immer
            assertThat(rows("jdbc:postgresql://localhost:" + port + "/appdb%2Ffeature%2FISSUE-33", "postgres",
                    "SELECT active_branch(), (SELECT count(*) FROM kunden)")).containsExactly("feature/ISSUE-33|1");
        }
    }
}
