package systems.grebe.devtools.mcp.modules.dolt;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import systems.grebe.devtools.mcp.core.Text;
import systems.grebe.devtools.mcp.modules.dolt.DoltDatabase.Kind;
import systems.grebe.devtools.mcp.modules.jdbc.JdbcConnection;
import systems.grebe.devtools.mcp.modules.jdbc.JdbcModule;

/**
 * Dolt (MySQL-Protokoll) und Doltgres (PostgreSQL-Protokoll) über JDBC; Treiber kommen aus dem JDBC-Modul.
 *
 * <p>Neue Verbindungen ohne Branch-Angabe landen auf dem Branch der Systemvariable {@code <datenbank>_default_branch}.
 * Dolt setzt sie mit {@code SET PERSIST} (gilt sofort und nach Neustarts). Zeigt sie auf einen Branch, den es nicht
 * gibt, lehnt Dolt jede neue Verbindung auf die Datenbank ab – deshalb wird ein fehlender Branch immer zuerst angelegt.
 * Doltgres kennt die Variable, nimmt sie im {@code SET} aber (noch) nicht an; dort bleibt nur das Anlegen.
 */
final class DoltServerBackend implements DoltBackend {

    private final DoltDatabase db;
    private final DoltDatabase.Server server;
    private final Connection con;
    private final int timeoutSeconds;

    private DoltServerBackend(DoltDatabase db, DoltDatabase.Server server, Connection con, int timeoutSeconds) {
        this.db = db;
        this.server = server;
        this.con = con;
        this.timeoutSeconds = timeoutSeconds;
    }

    static DoltServerBackend open(DoltDatabase db, JdbcModule jdbc, int timeoutSeconds) {
        DoltDatabase.Server server = db.server();
        JdbcConnection c = new JdbcConnection("dolt:" + db.name(), url(db.kind(), server), db.username(), db.password(),
                JdbcConnection.Access.ALL, "", "", "");
        try {
            Connection con = jdbc.connect(c, timeoutSeconds);
            con.setAutoCommit(true);
            return new DoltServerBackend(db, server, con, timeoutSeconds);
        } catch (SQLException e) {
            throw new IllegalStateException(describe(db, e), e);
        }
    }

    /**
     * JDBC-URL: Dolt ohne Datenbank (sie wird per {@code USE} mit Branch gewählt – sonst scheiterte schon der
     * Verbindungsaufbau, wenn der Standard-Branch nicht auflösbar ist), Doltgres mit Datenbank.
     */
    static String url(Kind kind, DoltDatabase.Server s) {
        String params = s.params().isEmpty() ? "" : "?" + s.params();
        if (kind == Kind.DOLTGRES) {
            return "jdbc:postgresql://" + s.host() + ":" + s.port() + "/"
                    + URLEncoder.encode(s.database(), StandardCharsets.UTF_8).replace("+", "%20") + params;
        }
        return "jdbc:mysql://" + s.host() + ":" + s.port() + "/" + params;
    }

    private boolean postgres() {
        return db.kind() == Kind.DOLTGRES;
    }

    @Override
    public String version() {
        return db.kind().label + " " + queryString("SELECT dolt_version()");
    }

    @Override
    public String defaultBranch() {
        try (Statement st = statement()) {
            if (!postgres()) {
                st.execute("USE " + ident(server.database())); // wie eine neue Verbindung ohne Branch-Angabe
            }
            try (ResultSet rs = st.executeQuery("SELECT active_branch()")) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            return null;
        }
    }

    @Override
    public List<String> branches() {
        String table = postgres() ? "dolt_branches" : ident(server.database()) + ".dolt_branches";
        List<String> out = new ArrayList<>();
        try (Statement st = statement(); ResultSet rs = st.executeQuery("SELECT name FROM " + table + " ORDER BY name")) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException(describe(db, e), e);
        }
    }

    @Override
    public void create(String branch, String from) {
        try {
            if (postgres()) {
                execute("SELECT dolt_branch(?, ?)", branch, from);
            } else {
                try (Statement st = statement()) {
                    st.execute("USE " + ident(server.database() + "/" + from));
                }
                execute("CALL DOLT_BRANCH(?, ?)", branch, from);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(describe(db, e), e);
        }
    }

    @Override
    public boolean setDefault(String branch) {
        String variable = server.database() + "_default_branch";
        try {
            if (postgres()) {
                // SET nimmt keine Parameter; Branch-Namen enthalten kein Backslash (Git verbietet es)
                try (Statement st = statement()) {
                    st.execute("SET \"" + variable.replace("\"", "\"\"") + "\" TO " + literal(branch));
                }
            } else {
                execute("SET PERSIST " + ident(variable) + " = ?", branch);
            }
            return true;
        } catch (SQLException e) {
            String msg = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
            if (postgres() && (msg.contains("unrecognized configuration parameter") || "42704".equals(e.getSQLState()))) {
                return false;
            }
            throw new IllegalStateException(describe(db, e), e);
        }
    }

    @Override
    public String connectHint(String branch) {
        String base = server.host() + ":" + server.port() + "/" + server.database() + "/" + branch;
        return postgres()
                ? "postgresql://" + base + " (pgJDBC: jdbc:postgresql://" + server.host() + ":" + server.port() + "/"
                + URLEncoder.encode(server.database() + "/" + branch, StandardCharsets.UTF_8) + ")"
                : "Datenbank `" + server.database() + "/" + branch + "` (jdbc:mysql://" + base + ")";
    }

    @Override
    public void close() {
        try {
            con.close();
        } catch (SQLException e) {
            // ignorieren
        }
    }

    // ------------------------------------------------------------------ intern

    private Statement statement() throws SQLException {
        Statement st = con.createStatement();
        st.setQueryTimeout(timeoutSeconds);
        return st;
    }

    private void execute(String sql, String... params) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setQueryTimeout(timeoutSeconds);
            for (int i = 0; i < params.length; i++) {
                ps.setString(i + 1, params[i]);
            }
            ps.execute();
        }
    }

    private String queryString(String sql) {
        try (Statement st = statement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : "";
        } catch (SQLException e) {
            throw new IllegalStateException(describe(db, e), e);
        }
    }

    /** MySQL-Bezeichner in Backticks. */
    static String ident(String name) {
        return "`" + name.replace("`", "``") + "`";
    }

    /** SQL-Zeichenkette mit verdoppelten Hochkommas. */
    static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    static String describe(DoltDatabase db, SQLException e) {
        StringBuilder sb = new StringBuilder("Datenbank '").append(db.name()).append("' (").append(db.kind().label)
                .append(", ").append(db.safeLocation()).append("): ");
        sb.append(e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName()
                : Text.firstLine(e.getMessage()));
        String state = e.getSQLState() == null ? "" : e.getSQLState();
        if (!state.isEmpty()) {
            sb.append(" [SQLState ").append(state).append(']');
        }
        if (state.startsWith("08")) {
            sb.append(" – läuft der Server? Host und Port prüfen.");
        } else if (state.startsWith("28")) {
            sb.append(" – Benutzer und Passwort muss der Nutzer in der DevTools-App prüfen.");
        }
        String out = Text.maskCredentials(sb.toString());
        return db.password() != null && db.password().length() >= 4 ? out.replace(db.password(), "****") : out;
    }
}
