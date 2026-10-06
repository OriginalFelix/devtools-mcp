package systems.grebe.devtools.mcp.modules.jdbc;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.Text;
import systems.grebe.devtools.mcp.core.ToolScope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcToolsTest {

    private static final String PASSWORD = "geheim-123";

    private final List<String> resolved = new ArrayList<>();
    private JdbcModule module;
    private String url;
    private Connection keepAlive;

    @BeforeEach
    void setUp() throws SQLException {
        module = new JdbcModule(new JdbcDrivers(coordinates -> {
            resolved.add(coordinates);
            return List.of(h2Jar());
        }));
        url = "jdbc:h2:mem:jdbc" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1";
        keepAlive = DriverManager.getConnection(url, "sa", PASSWORD);
        try (Statement st = keepAlive.createStatement()) {
            st.execute("CREATE TABLE kunden (id INT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(100) NOT NULL, "
                    + "ort VARCHAR(50), geboren DATE, umsatz DECIMAL(10,2))");
            st.execute("COMMENT ON TABLE kunden IS 'Kundenstamm'");
            st.execute("CREATE TABLE bestellungen (id INT PRIMARY KEY, kunde_id INT NOT NULL, betrag DECIMAL(10,2), "
                    + "CONSTRAINT fk_kunde FOREIGN KEY (kunde_id) REFERENCES kunden(id) ON DELETE CASCADE)");
            st.execute("CREATE INDEX idx_bestellungen_betrag ON bestellungen(betrag DESC)");
            st.execute("CREATE VIEW grosse_kunden AS SELECT * FROM kunden WHERE umsatz > 1000");
            st.execute("INSERT INTO kunden (name, ort, geboren, umsatz) VALUES ('Müller', 'Köln', DATE '1980-02-01', "
                    + "1500.50), ('Schmidt', 'Bonn', NULL, 200), ('Meier', 'Köln', DATE '1990-07-15', 50)");
            st.execute("INSERT INTO bestellungen VALUES (1, 1, 99.90), (2, 1, 10), (3, 2, 5)");
        }
    }

    @AfterEach
    void tearDown() throws SQLException {
        ToolScope.LOCAL.close();
        keepAlive.close();
    }

    /** H2 liegt (über das Backend) nur auf dem Laufzeit-Klassenpfad. */
    private static Path h2Jar() {
        try {
            return Path.of(Class.forName("org.h2.Driver").getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ModuleConfig config(Map<String, String> connection, Map<String, String> extra) {
        Map<String, String> record = new LinkedHashMap<>(Map.of("name", "crm", "url", url, "username", "sa",
                "password", PASSWORD, "description", "Testdatenbank"));
        record.putAll(connection);
        Map<String, String> values = new HashMap<>(extra);
        values.put(JdbcModule.CONNECTIONS, ModuleConfig.formatRecords(List.of(record)));
        return ModuleConfig.of(module.configSchema(), values);
    }

    private ModuleConfig config(Map<String, String> extra) {
        return config(Map.of(), extra);
    }

    private static Map<String, String> allowAll() {
        return Map.of(JdbcModule.ALLOW_INSERT, "true", JdbcModule.ALLOW_UPDATE, "true", JdbcModule.ALLOW_DELETE, "true",
                JdbcModule.ALLOW_DDL, "true", JdbcModule.ALLOW_EXECUTE, "true");
    }

    private JdbcRunner runner(ModuleConfig cfg) {
        return new JdbcRunner(module.environment(cfg));
    }

    private int count(String sql) throws SQLException {
        try (Statement st = keepAlive.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    // ------------------------------------------------------------------ Konfiguration und Berechtigungen

    @Test
    void connectionsAreEncryptedAndValidated() {
        ConfigField connections = module.configSchema().getFirst();
        assertThat(connections.secret()).isTrue();
        assertThat(config(Map.of()).validate()).isEmpty();
        String broken = ModuleConfig.formatRecords(List.of(Map.of("name", "x", "access", "alles")));
        assertThat(ModuleConfig.of(module.configSchema(), Map.of(JdbcModule.CONNECTIONS, broken)).validate())
                .anyMatch(e -> e.contains("Eintrag 1") && e.contains("JDBC-URL"))
                .anyMatch(e -> e.contains("Eintrag 1") && e.contains("Zugriff"));
    }

    @Test
    void switchesDecideWhichToolsExist() {
        List<String> standard = module.createTools(config(Map.of())).stream()
                .map(cb -> cb.getToolDefinition().name()).toList();
        assertThat(standard).containsExactlyInAnyOrder("connections", "databases", "tables", "describe", "disconnect",
                "query");
        List<String> all = module.createTools(config(allowAll())).stream().map(cb -> cb.getToolDefinition().name()).toList();
        assertThat(all).contains("insert", "update", "delete", "ddl", "execute");
        assertThat(module.createTools(config(Map.of(JdbcModule.ALLOW_QUERY, "false"))))
                .noneMatch(cb -> cb.getToolDefinition().name().equals("query"));
    }

    @Test
    void readOnlyConnectionRejectsChangesEvenWithSwitchesOn() {
        JdbcRunner runner = runner(config(Map.of("access", "read"), allowAll()));
        assertThatThrownBy(() -> runner.dml("crm", SqlStatements.Kind.DELETE, "DELETE FROM bestellungen WHERE id = 1",
                null, false, false))
                .hasMessageContaining("erlaubt höchstens „nur lesen“").hasMessageContaining("nicht umgehen");
        assertThat(runner.query("crm", "SELECT COUNT(*) AS n FROM kunden", null, null, null)).contains("3");
        assertThat(new JdbcTools(module.environment(config(Map.of("access", "read"), allowAll()))).connections())
                .contains("[Struktur ansehen, lesen; H2").doesNotContain("löschen");
    }

    @Test
    void missingSwitchPointsToPermissionRequest() {
        // Upsert über jdbc_insert braucht auch die Berechtigung zum Ändern
        JdbcRunner runner = runner(config(Map.of(JdbcModule.ALLOW_INSERT, "true")));
        assertThatThrownBy(() -> runner.dml("crm", SqlStatements.Kind.INSERT,
                "MERGE INTO kunden (id, name) KEY (id) VALUES (1, 'Neu')", null, false, false))
                .hasMessageContaining("jdbc_insert führt nur INSERT-Anweisungen aus");
        assertThatThrownBy(() -> runner.dml("crm", SqlStatements.Kind.UPDATE, "UPDATE kunden SET ort = 'X' WHERE id = 1",
                null, false, false))
                .hasMessageContaining("„Datensätze ändern“ ist im Modul nicht freigegeben (Schalter allowUpdate)")
                .hasMessageContaining("permissions_request tool=jdbc_update");
    }

    // ------------------------------------------------------------------ Struktur

    @Test
    void listsConnectionsWithoutPasswords() {
        JdbcEnvironment env = module.environment(config(Map.of("url", url + ";PASSWORD=" + PASSWORD), Map.of()));
        String out = new JdbcTools(env).connections();
        assertThat(out).contains("crm", "Benutzer sa", "Testdatenbank", "Struktur ansehen, lesen", "PASSWORD=****")
                .doesNotContain(PASSWORD);
    }

    @Test
    void credentialsInUrlsAreMasked() {
        assertThat(Text.maskCredentials("jdbc:postgresql://db:5432/app?user=bob&password=s3cr3t&ssl=true"))
                .isEqualTo("jdbc:postgresql://db:5432/app?user=bob&password=****&ssl=true");
        assertThat(Text.maskCredentials("jdbc:sqlserver://h;databaseName=x;password={a;b};encrypt=false"))
                .isEqualTo("jdbc:sqlserver://h;databaseName=x;password=****;encrypt=false");
        assertThat(Text.maskCredentials("jdbc:mysql://bob:s3cr3t@db:3306/app?trustStorePassword=k"))
                .isEqualTo("jdbc:mysql://bob:****@db:3306/app?trustStorePassword=****");
        assertThat(Text.maskCredentials("jdbc:oracle:thin:scott/tiger@//db:1521/XE"))
                .isEqualTo("jdbc:oracle:thin:scott/****@//db:1521/XE");
        assertThat(Text.maskCredentials("jdbc:h2:mem:x;DB_CLOSE_DELAY=-1")).isEqualTo("jdbc:h2:mem:x;DB_CLOSE_DELAY=-1");
    }

    @Test
    void databasesTablesAndDescribe() {
        JdbcTools tools = new JdbcTools(module.environment(config(Map.of())));
        assertThat(tools.databases(null)).contains("H2", "angemeldet als SA", "Schema PUBLIC", "PUBLIC (aktuell)",
                "INFORMATION_SCHEMA");
        assertThat(tools.tables(null, null, null, null, null))
                .contains("KUNDEN", "BESTELLUNGEN", "GROSSE_KUNDEN", "VIEW", "Kundenstamm")
                .doesNotContain("INFORMATION_SCHEMA");
        assertThat(tools.tables("crm", null, "best%", List.of("TABLE", "BASE TABLE"), null))
                .contains("BESTELLUNGEN").doesNotContain("KUNDEN");

        String kunden = tools.describe("CRM", "kunden", null); // Schreibweise egal, H2 speichert groß
        assertThat(kunden).contains("PUBLIC.KUNDEN – Kundenstamm", "Spalten (5)", "NAME", "CHARACTER VARYING(100)",
                "nein", "DECIMAL(10,2)", "PK, automatisch", "Primärschlüssel", "(ID)", "Referenziert von (1)",
                "PUBLIC.BESTELLUNGEN (KUNDE_ID) → (ID) ON DELETE CASCADE");
        assertThat(tools.describe(null, "public.bestellungen", null))
                .contains("Fremdschlüssel (1)", "(KUNDE_ID) → PUBLIC.KUNDEN (ID) ON DELETE CASCADE [FK_KUNDE]",
                        "IDX_BESTELLUNGEN_BETRAG (BETRAG DESC)");
        assertThatThrownBy(() -> tools.describe(null, "gibtsnicht", null)).hasMessageContaining("nicht gefunden")
                .hasMessageContaining("jdbc_tables");
    }

    // ------------------------------------------------------------------ Lesen

    @Test
    void queryWithParamsLimitsAndFormats() {
        JdbcRunner runner = runner(config(Map.of()));
        String out = runner.query(null, "SELECT name, umsatz, geboren FROM kunden WHERE ort = ? ORDER BY name",
                List.of("Köln"), null, null);
        assertThat(out).startsWith("2 Zeilen (crm,").contains("NAME", "Meier", "Müller", "1500.50", "1980-02-01")
                .doesNotContain("Schmidt");
        assertThat(runner.query(null, "SELECT name FROM kunden ORDER BY name", null, 2, null))
                .startsWith("Erste 2 Zeilen – weitere vorhanden");
        assertThat(runner.query(null, "SELECT id, ort FROM kunden WHERE id = ?", List.of(2), null, "json"))
                .contains("[{\"ID\":2,\"ORT\":\"Bonn\"}]");
        assertThat(runner.query(null, "SELECT name, ort FROM kunden WHERE geboren IS NULL", null, null, "csv"))
                .contains("NAME,ORT\nSchmidt,Bonn");
        // Zahl als Text in eine INTEGER-Spalte: der Typ des Platzhalters entscheidet
        assertThat(runner.query(null, "SELECT name FROM kunden WHERE id = ?", List.of("3"), null, null)).contains("Meier");
    }

    @Test
    void queryRejectsChangesAndSeveralStatements() {
        JdbcRunner runner = runner(config(allowAll()));
        assertThatThrownBy(() -> runner.query(null, "DELETE FROM kunden", null, null, null))
                .hasMessageContaining("nur lesende Anweisungen").hasMessageContaining("jdbc_delete");
        assertThatThrownBy(() -> runner.query(null, "SELECT 1; DROP TABLE kunden", null, null, null))
                .hasMessageContaining("genau eine Anweisung");
        assertThatThrownBy(() -> runner.query(null, "SELECT * FROM kunden WHERE id = ?", List.of(1, 2), null, null))
                .hasMessageContaining("1 Platzhalter").hasMessageContaining("2 Werte");
    }

    @Test
    void sqlErrorsAreExplainedWithSqlState() {
        JdbcRunner runner = runner(config(Map.of()));
        assertThatThrownBy(() -> runner.query(null, "SELECT nix FROM kunden", null, null, null))
                .hasMessageContaining("Datenbank 'crm'").hasMessageContaining("NIX").hasMessageContaining("SQLState 42");
    }

    @Test
    void wrongPasswordIsReportedWithoutSecrets() {
        JdbcTools tools = new JdbcTools(module.environment(config(Map.of("password", "falsch-999"), Map.of())));
        assertThatThrownBy(() -> tools.databases(null)).hasMessageContaining("Anmeldung fehlgeschlagen")
                .hasMessageNotContaining("falsch-999");
        assertThat(module.testConnection(config(Map.of("password", "falsch-999"), Map.of())).message())
                .contains("FEHLER").doesNotContain("falsch-999");
        assertThat(module.testConnection(config(Map.of())).success()).isTrue();
    }

    // ------------------------------------------------------------------ Ändern

    @Test
    void insertRowsConvertsValuesAndReturnsKeys() throws SQLException {
        JdbcRunner runner = runner(config(allowAll()));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("Name", "Neu");
        row.put("ort", null);
        row.put("geboren", "2001-12-24");
        row.put("umsatz", "12.5");
        String out = runner.insertRows("crm", null, "kunden", List.of(row, Map.of("name", "Zweiter")), false);
        assertThat(out).startsWith("2 Zeilen in PUBLIC.KUNDEN eingefügt").contains("festgeschrieben", "Erzeugte Schlüssel: 4, 5");
        assertThat(count("SELECT COUNT(*) FROM kunden WHERE geboren = DATE '2001-12-24' AND umsatz = 12.50")).isEqualTo(1);

        assertThat(runner.insertRows("crm", null, "kunden", List.of(Map.of("name", "Probe")), true))
                .startsWith("Probelauf: 1 Zeile würde in PUBLIC.KUNDEN eingefügt").contains("zurückgerollt");
        assertThat(count("SELECT COUNT(*) FROM kunden WHERE name = 'Probe'")).isZero();

        assertThatThrownBy(() -> runner.insertRows("crm", null, "kunden", List.of(Map.of("nam", "x")), false))
                .hasMessageContaining("Unbekannte Spalte 'nam'").hasMessageContaining("NAME");
        // Fehler in Zeile 2: auch Zeile 1 wird nicht eingefügt
        assertThatThrownBy(() -> runner.insertRows("crm", null, "kunden",
                List.of(Map.of("name", "Erste"), Map.of("ort", "ohne Namen")), false))
                .hasMessageContaining("Zeile 2");
        assertThat(count("SELECT COUNT(*) FROM kunden WHERE name = 'Erste'")).isZero();
    }

    @Test
    void updateAndDeleteWithDryRunAndWhereGuard() throws SQLException {
        JdbcRunner runner = runner(config(allowAll()));
        assertThat(runner.dml(null, SqlStatements.Kind.UPDATE, "UPDATE kunden SET ort = ? WHERE ort = ?",
                List.of("Berlin", "Köln"), true, false))
                .startsWith("Probelauf: 2 Zeilen wären betroffen").contains("nichts geändert");
        assertThat(count("SELECT COUNT(*) FROM kunden WHERE ort = 'Köln'")).isEqualTo(2);

        assertThat(runner.dml(null, SqlStatements.Kind.UPDATE, "UPDATE kunden SET ort = ? WHERE ort = ?;",
                List.of("Berlin", "Köln"), false, false)).startsWith("2 Zeilen betroffen").contains("festgeschrieben");
        assertThat(count("SELECT COUNT(*) FROM kunden WHERE ort = 'Berlin'")).isEqualTo(2);

        assertThatThrownBy(() -> runner.dml(null, SqlStatements.Kind.DELETE, "DELETE FROM bestellungen", null, false, false))
                .hasMessageContaining("ohne WHERE").hasMessageContaining("allRows=true");
        // auch ein DELETE im CTE (PostgreSQL) betrifft ohne WHERE alle Zeilen
        assertThatThrownBy(() -> runner.dml(null, SqlStatements.Kind.DELETE,
                "WITH d AS (DELETE FROM bestellungen RETURNING *) SELECT * FROM d", null, false, false))
                .hasMessageContaining("DELETE ohne WHERE");
        assertThatThrownBy(() -> runner.dml(null, SqlStatements.Kind.UPDATE, "DELETE FROM bestellungen WHERE id = 1",
                null, false, false)).hasMessageContaining("jdbc_update führt nur UPDATE");
        assertThat(runner.dml(null, SqlStatements.Kind.DELETE, "DELETE FROM bestellungen", null, false, true))
                .startsWith("3 Zeilen betroffen");
        assertThat(count("SELECT COUNT(*) FROM bestellungen")).isZero();
    }

    @Test
    void ddlAndExecute() throws SQLException {
        JdbcRunner runner = runner(config(allowAll()));
        assertThat(runner.ddl(null, "ALTER TABLE kunden ADD COLUMN email VARCHAR(200)")).startsWith("ALTER ausgeführt");
        assertThat(new JdbcTools(module.environment(config(allowAll()))).describe(null, "kunden", null)).contains("EMAIL");
        assertThatThrownBy(() -> runner.ddl(null, "SELECT 1")).hasMessageContaining("nur Strukturänderungen");

        String out = runner.execute(null, "SELECT name FROM kunden WHERE id = ?", List.of(1), null);
        assertThat(out).startsWith("Ausgeführt (crm,").contains("Ergebnis: 1 Zeile", "Müller");
        assertThat(runner.execute(null, "CREATE TABLE log (id INT); INSERT INTO log VALUES (1)", null, null))
                .startsWith("Ausgeführt");
        assertThat(count("SELECT COUNT(*) FROM log")).isEqualTo(1);
    }

    @Test
    void connectionsArePooledAndCanBeClosed() {
        JdbcEnvironment env = module.environment(config(Map.of()));
        JdbcRunner runner = new JdbcRunner(env);
        JdbcTools tools = new JdbcTools(env);
        assertThat(tools.disconnect(null)).isEqualTo("Verbindung crm war nicht offen.");
        runner.query(null, "SELECT 1", null, null, null);
        assertThat(tools.connections()).contains("; H2 ", "; verbunden]");
        assertThat(tools.disconnect("crm")).isEqualTo("Verbindung crm getrennt.");
        assertThat(tools.connections()).doesNotContain("verbunden]");
        assertThat(runner.query(null, "SELECT 2 AS x", null, null, null)).contains("1 Zeile");
    }

    // ------------------------------------------------------------------ Treiber und MCP-Aufrufe

    @Test
    void driverFromJarFileAndMavenCoordinates() {
        String jar = h2Jar().toString();
        JdbcTools fromJar = new JdbcTools(module.environment(config(Map.of("driver", jar), Map.of())));
        assertThat(fromJar.databases(null)).contains("H2");

        JdbcTools fromMaven = new JdbcTools(module.environment(config(Map.of("driver", "com.h2database:h2",
                "driverClass", "org.h2.Driver"), Map.of())));
        assertThat(fromMaven.databases(null)).contains("H2");
        assertThat(resolved).containsExactly("com.h2database:h2");

        JdbcTools unknown = new JdbcTools(module.environment(config(Map.of("url", "jdbc:gibtsnicht://x"), Map.of())));
        assertThatThrownBy(() -> unknown.databases(null)).hasMessageContaining("kein JDBC-Treiber für jdbc:gibtsnicht:")
                .hasMessageContaining("Maven-Koordinaten");
    }

    @Test
    void toolsCanBeCalledWithJsonArguments() {
        Map<String, ToolCallback> tools = new HashMap<>();
        module.createTools(config(allowAll())).forEach(cb -> tools.put(cb.getToolDefinition().name(), cb));
        assertThat(tools.get("query").getToolDefinition().inputSchema()).contains("\"params\"", "\"array\"");

        String inserted = tools.get("insert").call("""
                {"table": "kunden", "rows": [{"name": "Json", "umsatz": 0.1, "geboren": "1999-01-31"}]}""");
        assertThat(inserted).contains("1 Zeile in PUBLIC.KUNDEN eingefügt");
        String queried = tools.get("query").call("""
                {"sql": "SELECT umsatz, geboren FROM kunden WHERE name = ? AND umsatz < ?", "params": ["Json", 1]}""");
        assertThat(queried).contains("0.10", "1999-01-31");
    }
}
