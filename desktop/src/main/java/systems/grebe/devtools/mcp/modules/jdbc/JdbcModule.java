package systems.grebe.devtools.mcp.modules.jdbc;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.ConnectionTestResult;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.Text;
import systems.grebe.devtools.mcp.core.ToolBeans;
import systems.grebe.devtools.mcp.core.ToolModule;
import systems.grebe.devtools.mcp.core.ToolScope;
import systems.grebe.devtools.mcp.modules.maven.MavenVersions;
import systems.grebe.devtools.mcp.plugin.store.MavenPluginResolver;

/**
 * Datenbanken über JDBC: hinterlegte Verbindungen (Name, JDBC-URL, Benutzer, Passwort) für das LLM freigeben – Struktur
 * ansehen, Datensätze lesen und, je Schalter, einfügen, ändern, löschen, die Struktur ändern oder beliebiges SQL
 * ausführen. Jeder Schalter ist eine Berechtigung, die das Modul „Berechtigungen“ kennt und die das LLM mit
 * {@code permissions_request} anfragen kann; je Verbindung lässt sich der Zugriff zusätzlich deckeln.
 *
 * <p>Zugangsdaten werden verschlüsselt gespeichert; das LLM sieht nur Name, URL ohne Zugangsdaten, Benutzer und
 * Beschreibung. Treiber kommen aus dem Klassenpfad, aus JAR-Dateien oder per Maven (siehe {@link JdbcDrivers}).
 */
@Component
public class JdbcModule implements ToolModule {

    public static final String ID = "jdbc";

    static final String CONNECTIONS = "connections";
    static final String ALLOW_QUERY = "allowQuery";
    static final String ALLOW_INSERT = "allowInsert";
    static final String ALLOW_UPDATE = "allowUpdate";
    static final String ALLOW_DELETE = "allowDelete";
    static final String ALLOW_DDL = "allowDdl";
    static final String ALLOW_EXECUTE = "allowExecute";
    static final String MAX_ROWS = "maxRows";
    static final String MAX_CELL_CHARS = "maxCellChars";
    static final String QUERY_TIMEOUT = "queryTimeoutSeconds";
    static final String CONNECT_TIMEOUT = "connectTimeoutSeconds";

    private static final String STATE = ID + ".state";

    private final JdbcDrivers drivers;

    @Autowired
    public JdbcModule(ObjectProvider<MavenPluginResolver> resolver) {
        this(new JdbcDrivers(coordinates -> {
            MavenPluginResolver r = resolver.getIfAvailable();
            if (r == null) {
                throw new IllegalStateException("Maven-Zugriff nicht verfügbar");
            }
            String[] p = coordinates.split(":");
            String version = p.length >= 3 ? p[2] : MavenVersions.latest(r.versions(p[0], p[1]), false);
            if (version == null) {
                throw new IllegalStateException(p[0] + ":" + p[1] + " liegt in keinem Repository des Plugin-Stores");
            }
            return r.resolveWithDependencies(List.of(p[0] + ":" + p[1] + ":" + version));
        }));
    }

    /** Für Tests: eigene Treiberauflösung. */
    JdbcModule(JdbcDrivers drivers) {
        this.drivers = drivers;
    }

    /** Umgebung mit den Verbindungen des laufenden Scopes (außerhalb eines Tool-Aufrufs: lokal). */
    JdbcEnvironment environment(ModuleConfig config) {
        return new JdbcEnvironment(config, sessions(config, state(ToolScope.current())), drivers);
    }

    /**
     * Freie Verbindungen je Benutzer/Profil. Bei geänderten Werten (andere URL, anderes Passwort …) wird der Pool
     * ersetzt; das An- und Abschalten einzelner Tools baut die Tools ebenfalls neu, behält aber die Verbindungen.
     */
    private static JdbcSessions sessions(ModuleConfig config, ScopeState state) {
        synchronized (state) {
            if (!config.rawValues().equals(state.lastValues)) {
                state.sessions.close();
                state.sessions = new JdbcSessions();
                state.lastValues = config.rawValues();
            }
            return state.sessions;
        }
    }

    private static ScopeState state(ToolScope scope) {
        return scope.state(STATE, ScopeState::new);
    }

    private static final class ScopeState implements AutoCloseable {
        JdbcSessions sessions = new JdbcSessions();
        Map<String, String> lastValues;

        @Override
        public synchronized void close() {
            sessions.close();
        }
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Datenbanken (JDBC)";
    }

    @Override
    public String description() {
        return "Hinterlegte Datenbanken (PostgreSQL, MySQL/MariaDB, SQL Server, Oracle, DB2, H2, SQLite … – jede mit "
                + "JDBC-Treiber): Struktur ansehen, Datensätze lesen, je Schalter einfügen, ändern, löschen, Tabellen "
                + "anlegen und ändern oder beliebiges SQL ausführen. Zugangsdaten bleiben in der App.";
    }

    @Override
    public String instructions() {
        return """
                Für Datenbanken, die in der DevTools-App als JDBC-Verbindung hinterlegt sind, diese Tools statt `psql`, \
                `mysql`, `sqlplus`, `sqlcmd` o.ä. in der Shell verwenden – die Zugangsdaten kennt nur die App:
                - `jdbc_connections`: welche Verbindungen es gibt (Name, URL, was dort erlaubt ist, Beschreibung).
                - Struktur: `jdbc_databases` (Kataloge/Schemas), `jdbc_tables` (Tabellen, Views), `jdbc_describe` \
                (Spalten, Schlüssel, Fremdschlüssel, Indizes) – vor dem Schreiben von SQL die echten Namen und Typen \
                nachsehen statt zu raten.
                - `jdbc_query` (wenn angeboten): lesende Anweisungen (SELECT, WITH, SHOW, EXPLAIN); Werte immer als \
                Platzhalter `?` mit `params`, nie ins SQL einsetzen.
                - `jdbc_insert`, `jdbc_update`, `jdbc_delete` (nur wenn angeboten): Datensätze ändern – nur auf \
                ausdrückliche Anweisung des Nutzers; bei mehr als einer Handvoll Zeilen vorher mit `dryRun=true` die \
                Anzahl prüfen.
                - `jdbc_ddl` (Struktur: CREATE, ALTER, DROP, TRUNCATE) und `jdbc_execute` (beliebiges SQL: Prozeduren, \
                Blöcke, GRANT) nur wenn angeboten und nur auf ausdrückliche Anweisung – beides wird sofort festgeschrieben.
                Fehlt ein Tool, mit `permissions_request` anfragen. Ist eine Verbindung auf „nur lesen“ oder „lesen und \
                Datensätze ändern“ gedeckelt, kann das nur der Nutzer in der App ändern – nicht über ein anderes Tool \
                umgehen. Ist eine Datenbank nicht konfiguriert, den Nutzer bitten, sie in der App anzulegen – nicht nach \
                Passwörtern fragen.""";
    }

    @Override
    public int order() {
        return 172;
    }

    @Override
    public List<ConfigField> configSchema() {
        return List.of(
                ConfigField.records(CONNECTIONS, "Verbindungen",
                                ConfigField.of(JdbcConnection.NAME, "Name", FieldType.STRING).asRequired()
                                        .withHelp("Eindeutiger Name, über den das LLM die Verbindung anspricht, z.B. crm-test."),
                                ConfigField.of(JdbcConnection.URL, "JDBC-URL", FieldType.STRING).asRequired()
                                        .withHelp("z.B. jdbc:postgresql://localhost:5432/app, "
                                                + "jdbc:sqlserver://host:1433;databaseName=app;encrypt=false, "
                                                + "jdbc:oracle:thin:@//host:1521/XEPDB1, jdbc:mysql://host:3306/app. "
                                                + "Das Passwort gehört in das Feld darunter, nicht in die URL."),
                                ConfigField.of(JdbcConnection.USERNAME, "Benutzer", FieldType.STRING),
                                ConfigField.of(JdbcConnection.PASSWORD, "Passwort", FieldType.SECRET)
                                        .withHelp("Wird verschlüsselt gespeichert und nie an das LLM gegeben."),
                                ConfigField.of(JdbcConnection.ACCESS, "Zugriff höchstens", FieldType.ENUM)
                                        .withOptions(JdbcConnection.Access.keys()).withDefault("all")
                                        .withHelp("Deckel für diese Verbindung, zusätzlich zu den Schaltern des Moduls. "
                                                + "read = nur lesen (schreibgeschützte Verbindung, z.B. für Produktion), "
                                                + "write = lesen und Datensätze ändern, all = alles, was die Schalter "
                                                + "erlauben. Das LLM kann den Deckel nicht anfragen."),
                                ConfigField.of(JdbcConnection.DRIVER, "Treiber", FieldType.STRING)
                                        .withHelp("Leer = automatisch: H2 ist eingebaut, PostgreSQL, MySQL, MariaDB, SQL "
                                                + "Server, Oracle, DB2, SQLite, HSQLDB … lädt die App beim ersten Zugriff "
                                                + "aus den Maven-Repositories des Plugin-Stores (neueste stabile Version). "
                                                + "Sonst Maven-Koordinaten groupId:artifactId[:version], z.B. "
                                                + "com.oracle.database.jdbc:ojdbc8:19.3.0.0 für alte Server, oder Pfade "
                                                + "zu JAR-Dateien bzw. Verzeichnissen, getrennt durch ;."),
                                ConfigField.of(JdbcConnection.DRIVER_CLASS, "Treiberklasse", FieldType.STRING)
                                        .withHelp("Nur nötig, wenn sich der Treiber nicht selbst anmeldet (sehr alte "
                                                + "Treiber), z.B. com.ibm.as400.access.AS400JDBCDriver."),
                                ConfigField.of(JdbcConnection.DESCRIPTION, "Beschreibung", FieldType.STRING)
                                        .withHelp("Hinweis für das LLM, z.B. „CRM-Testdatenbank, Schema crm, "
                                                + "Kundenstammdaten“."))
                        .withHelp("Name, JDBC-URL, Benutzer und Passwort je Datenbank. Das LLM sieht Name, URL "
                                + "(Zugangsdaten darin maskiert), Benutzer und Beschreibung, nie Passwörter. Am "
                                + "sichersten ist ein Datenbankbenutzer, der nur die nötigen Rechte hat."),
                ConfigField.of(ALLOW_QUERY, "Datensätze lesen (SELECT)", FieldType.BOOLEAN).withDefault("true")
                        .withHelp("jdbc_query: lesende Anweisungen in einer schreibgeschützten Transaktion, die immer "
                                + "zurückgerollt wird. Struktur ansehen (Tabellen, Spalten) geht auch ohne."),
                ConfigField.of(ALLOW_INSERT, "Datensätze einfügen (INSERT)", FieldType.BOOLEAN).withDefault("false")
                        .withHelp("jdbc_insert: Zeilen als Objekte oder INSERT-Anweisung, in einer Transaktion."),
                ConfigField.of(ALLOW_UPDATE, "Datensätze ändern (UPDATE, MERGE)", FieldType.BOOLEAN).withDefault("false")
                        .withHelp("jdbc_update: UPDATE, MERGE, REPLACE – auch nötig für Upserts (INSERT … ON CONFLICT "
                                + "DO UPDATE)."),
                ConfigField.of(ALLOW_DELETE, "Datensätze löschen (DELETE)", FieldType.BOOLEAN).withDefault("false")
                        .withHelp("jdbc_delete: DELETE; ohne WHERE nur mit ausdrücklicher Bestätigung (allRows)."),
                ConfigField.of(ALLOW_DDL, "Struktur ändern (CREATE, ALTER, DROP, TRUNCATE)", FieldType.BOOLEAN)
                        .withDefault("false")
                        .withHelp("jdbc_ddl: Tabellen, Spalten, Indizes, Views anlegen, ändern und löschen – wird sofort "
                                + "festgeschrieben."),
                ConfigField.of(ALLOW_EXECUTE, "Beliebiges SQL ausführen", FieldType.BOOLEAN).withDefault("false")
                        .withHelp("jdbc_execute: SQL unverändert an die Datenbank – Prozeduren, PL/SQL- und T-SQL-Blöcke, "
                                + "GRANT, datenbankspezifische Befehle. Umfasst praktisch alle anderen Rechte."),
                ConfigField.of(MAX_ROWS, "Max. Zeilen je Ergebnis", FieldType.INT).withDefault("200"),
                ConfigField.of(MAX_CELL_CHARS, "Max. Zeichen je Wert", FieldType.INT).withDefault("500")
                        .withHelp("Längere Texte werden in der Ausgabe gekürzt."),
                ConfigField.of(QUERY_TIMEOUT, "Max. Dauer einer Anweisung (Sekunden)", FieldType.INT).withDefault("60"),
                ConfigField.of(CONNECT_TIMEOUT, "Verbindungs-Timeout (Sekunden)", FieldType.INT).withDefault("15"));
    }

    @Override
    public List<ToolCallback> createTools(ModuleConfig config) {
        return createTools(config, ToolScope.LOCAL);
    }

    @Override
    public List<ToolCallback> createTools(ModuleConfig config, ToolScope scope) {
        JdbcEnvironment env = new JdbcEnvironment(config, sessions(config, state(scope)), drivers);
        JdbcRunner runner = new JdbcRunner(env);
        List<Object> beans = new ArrayList<>(List.of(new JdbcTools(env)));
        if (config.getBoolean(ALLOW_QUERY)) {
            beans.add(new JdbcQueryTools(runner));
        }
        if (config.getBoolean(ALLOW_INSERT)) {
            beans.add(new JdbcInsertTools(runner));
        }
        if (config.getBoolean(ALLOW_UPDATE)) {
            beans.add(new JdbcUpdateTools(runner));
        }
        if (config.getBoolean(ALLOW_DELETE)) {
            beans.add(new JdbcDeleteTools(runner));
        }
        if (config.getBoolean(ALLOW_DDL)) {
            beans.add(new JdbcDdlTools(runner));
        }
        if (config.getBoolean(ALLOW_EXECUTE)) {
            beans.add(new JdbcExecuteTools(runner));
        }
        return ToolBeans.callbacks(beans.toArray());
    }

    @Override
    public ConnectionTestResult testConnection(ModuleConfig config) {
        List<String> errors = config.validate();
        if (!errors.isEmpty()) {
            return ConnectionTestResult.failed(String.join("\n", errors));
        }
        JdbcEnvironment env = new JdbcEnvironment(config, new JdbcSessions(), drivers);
        if (env.connections().isEmpty()) {
            return ConnectionTestResult.failed("Keine Verbindung angelegt.");
        }
        StringBuilder sb = new StringBuilder();
        boolean allOk = env.duplicates().isEmpty();
        if (!allOk) {
            sb.append("Mehrfach vergebene Namen: ").append(env.duplicates()).append('\n');
        }
        for (JdbcConnection c : env.connections()) {
            sb.append(c.name()).append(" (").append(c.safeUrl()).append("): ");
            try (Connection con = env.open(c)) {
                DatabaseMetaData m = con.getMetaData();
                sb.append("verbunden, ").append(Text.firstLine(m.getDatabaseProductName() + " "
                                + m.getDatabaseProductVersion()))
                        .append(", Treiber ").append(m.getDriverName()).append(' ').append(m.getDriverVersion())
                        .append(", erlaubt: ").append(env.accessSummary(c));
            } catch (SQLException e) {
                allOk = false;
                sb.append("FEHLER – ").append(env.describe(c, e));
            } catch (RuntimeException e) {
                allOk = false;
                sb.append("FEHLER – ").append(env.mask(c, String.valueOf(e.getMessage())));
            }
            sb.append('\n');
        }
        String msg = sb.toString().strip();
        return allOk ? ConnectionTestResult.ok(msg) : ConnectionTestResult.failed(msg);
    }
}
