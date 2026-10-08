package systems.grebe.devtools.mcp.modules.jdbc;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.Text;
import systems.grebe.devtools.mcp.modules.jdbc.SqlStatements.Kind;
import systems.grebe.devtools.mcp.core.NamedEntries;

/**
 * Ausgewertete Konfiguration des JDBC-Moduls: Verbindungen, freigegebene Arten von Anweisungen, Grenzen – und der
 * Umgang mit Datenbankverbindungen (öffnen, ausleihen, zurückgeben) und Fehlermeldungen.
 */
final class JdbcEnvironment {

    /** Wie eine Verbindung für einen Aufruf vorbereitet wird. */
    enum Mode {
        /** Schreibgeschützt in einer Transaktion, die immer zurückgerollt wird. */
        READ,
        /** Eine Transaktion; der Aufruf schreibt sie selbst fest, sonst wird zurückgerollt. */
        TRANSACTION,
        /** Autocommit – für DDL und freies SQL (manches läuft nicht in einer Transaktion, z.B. VACUUM). */
        AUTOCOMMIT
    }

    @FunctionalInterface
    interface ConnectionAction<T> {
        T run(Connection connection) throws SQLException;
    }

    private static final ExecutorService CONNECTOR = Executors.newCachedThreadPool(
            Thread.ofPlatform().daemon().name("jdbc-connect-", 0).factory());

    private final NamedEntries<JdbcConnection> connections = new NamedEntries<>(JdbcConnection::name,
            new NamedEntries.Messages("Keine Datenbankverbindung konfiguriert – in der DevTools-App unter Module → "
                    + "Datenbanken (JDBC) eine Verbindung anlegen (Name, JDBC-URL, Benutzer, Passwort).",
                    names -> "Mehrere Datenbankverbindungen konfiguriert – 'connection' angeben: " + names
                            + " (siehe jdbc_connections).",
                    name -> "Der Verbindungsname '" + name + "' ist mehrfach vergeben – der Nutzer muss "
                            + "ihn in der DevTools-App eindeutig machen.",
                    (name, names) -> "Unbekannte Datenbankverbindung '" + name + "'. Konfiguriert: " + names
                            + ". Neue Verbindungen legt der Nutzer in der DevTools-App an."));
    private final JdbcSessions sessions;
    private final JdbcDrivers drivers;
    private final Set<Kind> allowed = EnumSet.noneOf(Kind.class);
    private final int maxRows;
    private final int maxCellChars;
    private final int queryTimeoutSeconds;
    private final int connectTimeoutSeconds;

    JdbcEnvironment(ModuleConfig c, JdbcSessions sessions, JdbcDrivers drivers) {
        this.sessions = sessions;
        this.drivers = drivers;
        for (Map<String, String> r : c.getRecords(JdbcModule.CONNECTIONS)) {
            JdbcConnection conn = JdbcConnection.of(r);
            if (conn.name().isEmpty()) {
                continue;
            }
            connections.add(conn);
        }
        for (Kind k : Kind.values()) {
            if (c.getBoolean(k.setting)) {
                allowed.add(k);
            }
        }
        this.maxRows = Math.max(1, c.getInt(JdbcModule.MAX_ROWS, 200));
        this.maxCellChars = Math.max(20, c.getInt(JdbcModule.MAX_CELL_CHARS, 500));
        this.queryTimeoutSeconds = Math.max(1, c.getInt(JdbcModule.QUERY_TIMEOUT, 60));
        this.connectTimeoutSeconds = Math.max(3, c.getInt(JdbcModule.CONNECT_TIMEOUT, 15));
    }

    // ------------------------------------------------------------------ Verbindungen

    List<JdbcConnection> connections() {
        return connections.all();
    }

    List<String> duplicates() {
        return connections.duplicates();
    }

    /** Verbindung nach Name (ohne Groß-/Kleinschreibung); ohne Name die einzige konfigurierte. */
    JdbcConnection resolve(String name) {
        return connections.resolve(name);
    }

    boolean isOpen(JdbcConnection c) {
        return sessions.isOpen(c.name());
    }

    /** Schließt die freien Verbindungen; {@code true}, wenn eine offen war. */
    boolean disconnect(JdbcConnection c) {
        return sessions.evict(c.name());
    }

    /** Zuletzt gesehenes Datenbankprodukt mit Version, oder {@code null}. */
    String product(JdbcConnection c) {
        return sessions.product(c.name());
    }

    int maxRows() {
        return maxRows;
    }

    int maxCellChars() {
        return maxCellChars;
    }

    int queryTimeoutSeconds() {
        return queryTimeoutSeconds;
    }

    // ------------------------------------------------------------------ Berechtigungen

    /** Ob Modul (Schalter) und Verbindung (Zugriff) diese Art von Anweisung erlauben. */
    boolean permits(JdbcConnection c, Kind kind) {
        return allowed.contains(kind) && c.access().permits(kind);
    }

    /** Wirft mit verständlicher Meldung, wenn eine der Arten nicht erlaubt ist. */
    void require(JdbcConnection c, Set<Kind> kinds) {
        List<String> problems = new ArrayList<>();
        for (Kind k : kinds) {
            if (!allowed.contains(k)) {
                problems.add("„" + k.label + "“ ist im Modul nicht freigegeben (Schalter " + k.setting + "). Ist es für "
                        + "den Auftrag nötig, mit permissions_request tool=" + k.tool + " beim Nutzer anfragen.");
            } else if (!c.access().permits(k)) {
                problems.add("Die Verbindung '" + c.name() + "' erlaubt höchstens „" + c.access().label + "“ (Zugriff "
                        + c.access().key + "). Das kann nur der Nutzer in der DevTools-App ändern – nicht umgehen.");
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Nicht erlaubt:\n- " + String.join("\n- ", problems));
        }
    }

    /** Was auf der Verbindung erlaubt ist, z.B. „lesen, einfügen, ändern“. */
    String accessSummary(JdbcConnection c) {
        List<String> out = new ArrayList<>();
        for (Kind k : Kind.values()) {
            if (permits(c, k)) {
                out.add(k.shortLabel);
            }
        }
        return out.isEmpty() ? "nur Struktur ansehen" : "Struktur ansehen, " + String.join(", ", out);
    }

    // ------------------------------------------------------------------ Ausführung

    /**
     * Führt {@code action} mit einer (wiederverwendeten) Verbindung aus. Eine offene Transaktion wird danach immer
     * zurückgerollt – festschreiben muss der Aufruf selbst. Verbindungen, die danach nicht mehr brauchbar sind, werden
     * geschlossen statt zurückgegeben.
     */
    <T> T withConnection(JdbcConnection c, Mode mode, ConnectionAction<T> action) {
        Connection con = borrow(c);
        boolean reusable = false;
        try {
            prepare(con, c, mode);
            T result = action.run(con);
            reusable = true;
            return result;
        } catch (SQLException e) {
            reusable = usable(con);
            throw new IllegalStateException(describe(c, e), e);
        } catch (RuntimeException e) {
            reusable = usable(con);
            throw e;
        } finally {
            release(c, con, reusable);
        }
    }

    private void prepare(Connection con, JdbcConnection c, Mode mode) throws SQLException {
        try {
            // Schreibschutz vor dem Beginn der Transaktion setzen (PostgreSQL: BEGIN READ ONLY)
            con.setReadOnly(mode == Mode.READ || c.access() == JdbcConnection.Access.READ);
        } catch (SQLException e) {
            // nur ein Hinweis an den Treiber – nicht jeder unterstützt ihn
        }
        con.setAutoCommit(mode == Mode.AUTOCOMMIT);
    }

    private Connection borrow(JdbcConnection c) {
        Connection con = sessions.take(c);
        if (con != null) {
            try {
                if (con.isValid(3)) {
                    return con;
                }
            } catch (SQLException | RuntimeException e) {
                // tot – neu verbinden
            }
            closeQuietly(con);
        }
        try {
            return open(c);
        } catch (SQLException e) {
            throw new IllegalStateException(describe(c, e), e);
        }
    }

    private void release(JdbcConnection c, Connection con, boolean reusable) {
        try {
            if (!con.isClosed() && !con.getAutoCommit()) {
                con.rollback();
            }
            if (reusable) {
                con.setAutoCommit(true);
                sessions.give(c, con);
                return;
            }
        } catch (SQLException | RuntimeException e) {
            // nicht mehr brauchbar
        }
        closeQuietly(con);
    }

    private static boolean usable(Connection con) {
        try {
            return !con.isClosed() && con.isValid(2);
        } catch (SQLException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Baut eine neue Verbindung auf (ohne Pool). Der Treiber läuft in einem eigenen Thread, damit ein hängender
     * Verbindungsaufbau nach dem Timeout abgebrochen werden kann – unabhängig davon, ob der Treiber selbst einen kennt.
     */
    Connection open(JdbcConnection c) throws SQLException {
        if (c.url().isEmpty()) {
            throw new IllegalStateException("Verbindung '" + c.name() + "': JDBC-URL fehlt.");
        }
        Driver driver = drivers.driver(c);
        Properties props = new Properties();
        if (!c.username().isEmpty()) {
            props.setProperty("user", c.username());
        }
        if (c.password() != null) {
            props.setProperty("password", c.password());
        }
        CompletableFuture<Connection> future = CompletableFuture.supplyAsync(() -> {
            Thread t = Thread.currentThread();
            ClassLoader before = t.getContextClassLoader();
            t.setContextClassLoader(driver.getClass().getClassLoader()); // manche Treiber laden Ressourcen darüber
            try {
                return driver.connect(c.url(), props);
            } catch (SQLException e) {
                throw new CompletionException(e);
            } finally {
                t.setContextClassLoader(before);
            }
        }, CONNECTOR);
        Connection con;
        try {
            con = future.get(connectTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.thenAccept(JdbcEnvironment::closeQuietly);
            throw new IllegalStateException("Datenbank '" + c.name() + "' (" + c.safeUrl() + "): Zeitüberschreitung beim "
                    + "Verbinden (" + connectTimeoutSeconds + " s) – Host, Port und Firewall prüfen.");
        } catch (InterruptedException e) {
            future.thenAccept(JdbcEnvironment::closeQuietly);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Abgebrochen.", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() instanceof CompletionException ce && ce.getCause() != null
                    ? ce.getCause() : e.getCause();
            if (cause instanceof SQLException se) {
                throw se;
            }
            throw new IllegalStateException("Datenbank '" + c.name() + "': " + mask(c, String.valueOf(cause)), cause);
        }
        if (con == null) {
            throw new IllegalStateException("Datenbank '" + c.name() + "': der Treiber " + driver.getClass().getName()
                    + " nimmt die URL " + c.safeUrl() + " nicht an – URL prüfen.");
        }
        try {
            DatabaseMetaData m = con.getMetaData();
            sessions.product(c.name(), Text.firstLine(m.getDatabaseProductName() + " " + m.getDatabaseProductVersion()));
        } catch (SQLException | RuntimeException e) {
            // nur zur Anzeige
        }
        return con;
    }

    /** Zeitlimit für eine Anweisung (nicht jeder Treiber unterstützt es). */
    void configure(Statement st) {
        try {
            st.setQueryTimeout(queryTimeoutSeconds);
        } catch (SQLException | RuntimeException e) {
            // ignorieren
        }
    }

    // ------------------------------------------------------------------ Meldungen

    /** Verständliche Meldung für das LLM, mit SQLState und Folgefehlern; nennt nie Zugangsdaten. */
    String describe(JdbcConnection c, SQLException e) {
        StringBuilder sb = new StringBuilder("Datenbank '").append(c.name()).append("': ");
        SQLException cur = e;
        for (int n = 0; cur != null && n < 3; n++) {
            if (n > 0) {
                sb.append("\n  Folgefehler: ");
            }
            String msg = cur.getMessage() == null || cur.getMessage().isBlank()
                    ? cur.getClass().getSimpleName() : cur.getMessage().strip();
            sb.append(msg);
            if (cur.getSQLState() != null) {
                sb.append(" [SQLState ").append(cur.getSQLState())
                        .append(cur.getErrorCode() != 0 ? ", Code " + cur.getErrorCode() : "").append(']');
            }
            SQLException next = cur.getNextException();
            cur = next == cur ? null : next;
        }
        String hint = hint(e);
        if (hint != null) {
            sb.append("\n").append(hint);
        }
        return mask(c, sb.toString());
    }

    private String hint(SQLException e) {
        String state = e.getSQLState() == null ? "" : e.getSQLState();
        if (e instanceof SQLTimeoutException || state.equals("57014")) {
            return "Abgebrochen nach dem Zeitlimit (" + queryTimeoutSeconds + " s) – Anweisung eingrenzen.";
        }
        if (state.startsWith("28")) {
            return "Anmeldung fehlgeschlagen – Benutzer und Passwort muss der Nutzer in der DevTools-App prüfen.";
        }
        if (state.startsWith("08")) {
            return "Keine Verbindung zur Datenbank – URL, Host und Port prüfen (in der DevTools-App); läuft die Datenbank?";
        }
        if (state.equals("42501")) {
            return "Dem Datenbankbenutzer fehlt das Recht dazu (Rechte in der Datenbank, nicht in DevTools).";
        }
        if (state.equals("40001") || state.equals("40P01")) {
            return "Konflikt mit einer anderen Transaktion (Deadlock oder Serialisierung) – erneut versuchen.";
        }
        return null;
    }

    /** Entfernt Passwort und Zugangsdaten in URLs aus einem Text. */
    String mask(JdbcConnection c, String text) {
        String out = Text.maskCredentials(text);
        if (c.password() != null && c.password().length() >= 4) {
            out = out.replace(c.password(), "****");
        }
        return out;
    }

    /** Warnungen der Datenbank (z.B. NOTICE, PRINT) als Zeilen; leer, wenn keine. */
    static String warnings(Statement st) {
        StringBuilder sb = new StringBuilder();
        try {
            SQLWarning w = st.getWarnings();
            for (int n = 0; w != null && n < 10; n++) {
                if (w.getMessage() != null && !w.getMessage().isBlank()) {
                    sb.append("\nHinweis der Datenbank: ").append(w.getMessage().strip());
                }
                w = w.getNextWarning();
            }
        } catch (SQLException | RuntimeException e) {
            // keine Warnungen verfügbar
        }
        return sb.toString();
    }

    static void closeQuietly(Connection con) {
        if (con == null) {
            return;
        }
        try {
            con.close();
        } catch (SQLException | RuntimeException e) {
            // ignorieren
        }
    }
}
