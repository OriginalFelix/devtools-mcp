package systems.grebe.devtools.mcp.modules.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import systems.grebe.devtools.mcp.modules.jdbc.JdbcEnvironment.Mode;
import systems.grebe.devtools.mcp.modules.jdbc.JdbcMetadata.Column;
import systems.grebe.devtools.mcp.modules.jdbc.JdbcMetadata.TableRef;
import systems.grebe.devtools.mcp.modules.jdbc.SqlStatements.Analysis;
import systems.grebe.devtools.mcp.modules.jdbc.SqlStatements.Kind;

/**
 * Führt Anweisungen aus – für jedes Tool mit den Prüfungen, die zu seiner Berechtigung gehören: jdbc_query nur
 * lesend in einer schreibgeschützten, zurückgerollten Transaktion; DML in einer Transaktion (mit Probelauf); DDL und
 * freies SQL im Autocommit.
 */
final class JdbcRunner {

    /** Höchstens so viele Zeilen je jdbc_insert. */
    static final int MAX_INSERT_ROWS = 1000;
    private static final int MAX_RESULTS = 50;
    /** Anweisungen, deren Bedingung nicht im WHERE steht (ON bei MERGE, Schlüssel bei REPLACE/UPSERT). */
    private static final Set<String> UNCONDITIONAL = Set.of("MERGE", "REPLACE", "UPSERT");

    private final JdbcEnvironment env;

    JdbcRunner(JdbcEnvironment env) {
        this.env = env;
    }

    // ------------------------------------------------------------------ Lesen

    String query(String connection, String sql, List<Object> params, Integer maxRows, String format) {
        JdbcConnection c = env.resolve(connection);
        Analysis a = single(sql, "jdbc_query");
        if (a.primary() != Kind.QUERY || !a.readOnly()) {
            throw new IllegalArgumentException("jdbc_query führt nur lesende Anweisungen aus (SELECT, WITH, VALUES, SHOW, "
                    + "EXPLAIN) – erkannt: " + describe(a) + ". " + toolHint(a));
        }
        env.require(c, a.kinds());
        int limit = maxRows == null || maxRows <= 0 ? env.maxRows() : Math.min(maxRows, env.maxRows());
        JdbcResults.Format f = JdbcResults.Format.of(format);
        return env.withConnection(c, Mode.READ, con -> {
            long start = System.nanoTime();
            try (PreparedStatement ps = con.prepareStatement(a.sql())) {
                env.configure(ps);
                limitRows(ps, limit);
                new JdbcValues.Binder(con).bindAll(ps, params);
                if (!ps.execute()) {
                    return "Keine Ergebnismenge (" + c.name() + ", " + millis(start) + " ms)."
                            + JdbcEnvironment.warnings(ps);
                }
                JdbcResults.Page page;
                try (ResultSet rs = ps.getResultSet()) {
                    page = JdbcResults.read(rs, limit, env.maxCellChars());
                }
                return header(page, c, start) + "\n" + JdbcResults.format(page, f, env.maxCellChars())
                        + JdbcEnvironment.warnings(ps);
            }
        });
    }

    // ------------------------------------------------------------------ DML

    /**
     * INSERT/UPDATE/DELETE/MERGE als SQL. Die Anweisung muss {@code kind} enthalten; alle anderen ändernden Teile (etwa
     * das UPDATE eines Upserts) brauchen ihre eigene Berechtigung.
     */
    String dml(String connection, Kind kind, String sql, List<Object> params, boolean dryRun, boolean allRows) {
        JdbcConnection c = env.resolve(connection);
        Analysis a = single(sql, kind.tool);
        Set<Kind> changes = EnumSet.copyOf(a.kinds());
        changes.remove(Kind.QUERY);
        if (!changes.contains(kind) || changes.stream().anyMatch(k -> !k.dml())) {
            throw new IllegalArgumentException(kind.tool + " führt nur " + statements(kind) + " aus – erkannt: "
                    + describe(a) + ". " + toolHint(a));
        }
        env.require(c, changes);
        // UPDATE/DELETE (auch in einem CTE) ohne WHERE trifft alle Zeilen; Upserts und MERGE haben ihre Bedingung woanders
        boolean everyRow = a.primary() != Kind.INSERT && !UNCONDITIONAL.contains(a.keyword())
                && (changes.contains(Kind.UPDATE) || changes.contains(Kind.DELETE));
        if (everyRow && !a.where() && !allRows) {
            throw new IllegalArgumentException((changes.contains(Kind.DELETE) ? "DELETE" : "UPDATE") + " ohne WHERE "
                    + "betrifft alle Zeilen der Tabelle. Ist das ausdrücklich gewollt, mit allRows=true wiederholen "
                    + "(vorher mit dryRun=true die Anzahl prüfen).");
        }
        return env.withConnection(c, Mode.TRANSACTION, con -> {
            requireTransactions(con, dryRun);
            long start = System.nanoTime();
            try (PreparedStatement ps = con.prepareStatement(a.sql())) {
                env.configure(ps);
                limitRows(ps, env.maxRows());
                new JdbcValues.Binder(con).bindAll(ps, params);
                Outcome o = collect(ps, ps.execute());
                finish(con, dryRun);
                StringBuilder sb = new StringBuilder(dryRun ? "Probelauf: " : "")
                        .append(dryRun ? count(o.updateCounts(), "wäre betroffen", "wären betroffen")
                                : count(o.updateCounts(), "betroffen", "betroffen"))
                        .append(" (").append(c.name()).append(", ").append(millis(start)).append(" ms)");
                sb.append(dryRun ? " – zurückgerollt, nichts geändert." : " – festgeschrieben.");
                sb.append(o.results()).append(JdbcEnvironment.warnings(ps));
                return sb.toString();
            }
        });
    }

    /** Fügt Zeilen als JSON-Objekte ein (Spaltenname → Wert); Spalten und Typen aus den Metadaten. */
    String insertRows(String connection, String schema, String table, List<Map<String, Object>> rows, boolean dryRun) {
        JdbcConnection c = env.resolve(connection);
        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("'rows' ist leer – mindestens eine Zeile als Objekt {\"spalte\": wert} "
                    + "angeben.");
        }
        if (rows.size() > MAX_INSERT_ROWS) {
            throw new IllegalArgumentException("Höchstens " + MAX_INSERT_ROWS + " Zeilen je Aufruf – in Portionen "
                    + "einfügen.");
        }
        env.require(c, EnumSet.of(Kind.INSERT));
        return env.withConnection(c, Mode.TRANSACTION, con -> {
            requireTransactions(con, dryRun);
            long start = System.nanoTime();
            TableRef t = JdbcMetadata.table(con, null, schema, table);
            List<Column> columns = JdbcMetadata.columns(con, t);
            String target = JdbcMetadata.qualified(con, t);
            JdbcValues.Binder binder = new JdbcValues.Binder(con);
            Map<String, PreparedStatement> statements = new LinkedHashMap<>();
            List<String> keys = new ArrayList<>();
            int inserted = 0;
            try {
                for (int r = 0; r < rows.size(); r++) {
                    Map<String, Object> row = rows.get(r);
                    if (row == null || row.isEmpty()) {
                        throw new IllegalArgumentException("Zeile " + (r + 1) + " ist leer.");
                    }
                    List<Column> cols = new ArrayList<>();
                    for (String key : row.keySet()) {
                        cols.add(JdbcMetadata.column(columns, key, t));
                    }
                    String sql = insertSql(con, target, cols);
                    PreparedStatement ps = statements.get(sql);
                    if (ps == null) {
                        ps = prepareWithKeys(con, sql);
                        env.configure(ps);
                        statements.put(sql, ps);
                    }
                    int i = 1;
                    for (Map.Entry<String, Object> e : row.entrySet()) {
                        binder.bind(ps, i, e.getValue(), cols.get(i - 1).type());
                        i++;
                    }
                    try {
                        inserted += Math.max(0, ps.executeUpdate());
                    } catch (SQLException e) {
                        throw new SQLException("Zeile " + (r + 1) + ": " + e.getMessage(), e.getSQLState(),
                                e.getErrorCode(), e);
                    }
                    if (keys.size() < 20) {
                        keys.addAll(generatedKeys(ps));
                    }
                }
                finish(con, dryRun);
            } finally {
                statements.values().forEach(JdbcRunner::closeQuietly);
            }
            StringBuilder sb = new StringBuilder(dryRun ? "Probelauf: " : "")
                    .append(inserted).append(inserted == 1 ? " Zeile" : " Zeilen")
                    .append(dryRun ? (inserted == 1 ? " würde in " : " würden in ") : " in ").append(t.display())
                    .append(" eingefügt (").append(c.name()).append(", ")
                    .append(millis(start)).append(" ms)")
                    .append(dryRun ? " – zurückgerollt, nichts geändert." : " – festgeschrieben.");
            if (!keys.isEmpty()) {
                sb.append("\nErzeugte Schlüssel").append(dryRun ? " (verworfen)" : "").append(": ")
                        .append(String.join(", ", keys.subList(0, Math.min(keys.size(), 20))))
                        .append(keys.size() > 20 || inserted > 20 && keys.size() == 20 ? ", …" : "");
            }
            return sb.toString();
        });
    }

    // ------------------------------------------------------------------ DDL und freies SQL

    String ddl(String connection, String sql) {
        JdbcConnection c = env.resolve(connection);
        Analysis a = single(sql, "jdbc_ddl");
        if (a.primary() != Kind.DDL) {
            throw new IllegalArgumentException("jdbc_ddl führt nur Strukturänderungen aus (CREATE, ALTER, DROP, TRUNCATE, "
                    + "RENAME, COMMENT) – erkannt: " + describe(a) + ". " + toolHint(a));
        }
        env.require(c, EnumSet.of(Kind.DDL));
        return env.withConnection(c, Mode.AUTOCOMMIT, con -> {
            long start = System.nanoTime();
            try (Statement st = con.createStatement()) {
                env.configure(st);
                Outcome o = collect(st, st.execute(a.sql()));
                return a.keyword() + " ausgeführt (" + c.name() + ", " + millis(start) + " ms)"
                        + (o.updateCounts().stream().anyMatch(n -> n > 0)
                        ? ", " + count(o.updateCounts(), "betroffen", "betroffen") : "")
                        + "." + o.results() + JdbcEnvironment.warnings(st);
            }
        });
    }

    /** Text unverändert an den Treiber – auch Prozeduren, PL/SQL-Blöcke und datenbankspezifische Befehle. */
    String execute(String connection, String sql, List<Object> params, Integer maxRows) {
        JdbcConnection c = env.resolve(connection);
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("'sql' fehlt.");
        }
        env.require(c, EnumSet.of(Kind.OTHER));
        int limit = maxRows == null || maxRows <= 0 ? env.maxRows() : Math.min(maxRows, env.maxRows());
        String text = sql.strip();
        return env.withConnection(c, Mode.AUTOCOMMIT, con -> {
            long start = System.nanoTime();
            boolean prepared = params != null && !params.isEmpty();
            try (Statement st = prepared ? con.prepareStatement(text) : con.createStatement()) {
                env.configure(st);
                limitRows(st, limit);
                boolean isResult;
                if (st instanceof PreparedStatement ps) {
                    new JdbcValues.Binder(con).bindAll(ps, params);
                    isResult = ps.execute();
                } else {
                    isResult = st.execute(text);
                }
                Outcome o = collect(st, isResult, limit);
                String counts = o.updateCounts().stream().anyMatch(n -> n >= 0)
                        ? ", " + count(o.updateCounts(), "betroffen", "betroffen") : "";
                return "Ausgeführt (" + c.name() + ", " + millis(start) + " ms" + counts + ")." + o.results()
                        + JdbcEnvironment.warnings(st);
            }
        });
    }

    // ------------------------------------------------------------------ intern

    /** Update-Zählungen und formatierte Ergebnismengen einer Ausführung. */
    private record Outcome(List<Integer> updateCounts, String results) {
    }

    private Outcome collect(Statement st, boolean isResult) throws SQLException {
        return collect(st, isResult, env.maxRows());
    }

    /** Läuft alle Ergebnisse durch: Ergebnismengen (RETURNING, Prozeduren) und Update-Zählungen. */
    private Outcome collect(Statement st, boolean isResult, int limit) throws SQLException {
        List<Integer> counts = new ArrayList<>();
        StringBuilder results = new StringBuilder();
        int sets = 0;
        for (int n = 0; n < MAX_RESULTS; n++) {
            if (isResult) {
                try (ResultSet rs = st.getResultSet()) {
                    JdbcResults.Page page = JdbcResults.read(rs, limit, env.maxCellChars());
                    sets++;
                    results.append("\n\nErgebnis").append(sets > 1 || n > 0 ? " " + sets : "").append(": ")
                            .append(page.rows().size()).append(page.rows().size() == 1 ? " Zeile" : " Zeilen")
                            .append(page.more() ? " (weitere nicht gelesen)" : "").append('\n')
                            .append(JdbcResults.format(page, JdbcResults.Format.TABLE, env.maxCellChars()));
                }
            } else {
                int count = st.getUpdateCount();
                if (count == -1) {
                    break;
                }
                counts.add(count);
            }
            isResult = st.getMoreResults();
        }
        return new Outcome(counts, results.toString());
    }

    /** Ohne Transaktionen ließe sich ein Probelauf nicht zurückrollen. */
    private static void requireTransactions(Connection con, boolean dryRun) throws SQLException {
        if (dryRun && !con.getMetaData().supportsTransactions()) {
            throw new IllegalArgumentException("Probelauf nicht möglich: die Datenbank unterstützt keine Transaktionen, "
                    + "die Änderung ließe sich nicht zurückrollen.");
        }
    }

    private static void finish(Connection con, boolean dryRun) throws SQLException {
        if (dryRun) {
            con.rollback();
        } else {
            con.commit();
        }
    }

    private static String insertSql(Connection con, String target, List<Column> cols) throws SQLException {
        var m = con.getMetaData();
        List<String> names = new ArrayList<>();
        for (Column col : cols) {
            names.add(JdbcMetadata.quote(m, col.name()));
        }
        return "INSERT INTO " + target + " (" + String.join(", ", names) + ") VALUES ("
                + cols.stream().map(x -> "?").collect(Collectors.joining(", ")) + ")";
    }

    private static PreparedStatement prepareWithKeys(Connection con, String sql) throws SQLException {
        try {
            return con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
        } catch (SQLFeatureNotSupportedException | UnsupportedOperationException e) {
            return con.prepareStatement(sql);
        }
    }

    private static List<String> generatedKeys(PreparedStatement ps) {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = ps.getGeneratedKeys()) {
            if (rs == null) {
                return out;
            }
            int columns = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                List<String> parts = new ArrayList<>();
                for (int i = 1; i <= Math.min(columns, 3); i++) {
                    parts.add(String.valueOf(rs.getObject(i)));
                }
                out.add(parts.size() == 1 ? parts.getFirst() : "(" + String.join(", ", parts) + ")");
            }
        } catch (SQLException | RuntimeException e) {
            // nicht jeder Treiber liefert Schlüssel
        }
        return out;
    }

    private static void closeQuietly(Statement st) {
        try {
            st.close();
        } catch (SQLException | RuntimeException e) {
            // ignorieren – ein Fehler beim Ausführen soll nicht überdeckt werden
        }
    }

    private static void limitRows(Statement st, int limit) {
        try {
            st.setMaxRows(limit + 1); // eine mehr, um „weitere vorhanden“ zu erkennen
            st.setFetchSize(Math.min(limit + 1, 500));
        } catch (SQLException | RuntimeException e) {
            // nur eine Optimierung
        }
    }

    /** Genau eine Anweisung, sonst eine Meldung mit Hinweis. */
    private static Analysis single(String sql, String tool) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("'sql' fehlt.");
        }
        Analysis a = SqlStatements.analyze(sql);
        if (a.statements() == 0) {
            throw new IllegalArgumentException("'sql' enthält keine Anweisung (nur Kommentare?).");
        }
        if (a.statements() > 1) {
            throw new IllegalArgumentException(tool + " führt genau eine Anweisung aus – erkannt: " + a.statements()
                    + " (durch ; getrennt). Einzeln aufrufen; Prozeduren, Trigger und Blöcke mit ; im Rumpf über "
                    + "jdbc_execute.");
        }
        return a;
    }

    private static String header(JdbcResults.Page page, JdbcConnection c, long start) {
        int n = page.rows().size();
        String rows = n + (n == 1 ? " Zeile" : " Zeilen");
        return (page.more() ? "Erste " + rows + " – weitere vorhanden (maxRows erhöhen, Abfrage eingrenzen oder mit "
                + "OFFSET/Schlüssel seitenweise lesen)" : rows) + " (" + c.name() + ", " + millis(start) + " ms)";
    }

    /** z.B. „1 Zeile wäre betroffen“ / „3 Zeilen wären betroffen“. */
    private static String count(List<Integer> counts, String singular, String plural) {
        List<Integer> known = counts.stream().filter(n -> n >= 0).toList();
        if (known.isEmpty()) {
            return "Anzahl der Zeilen unbekannt";
        }
        int first = known.getFirst();
        String text = first + (first == 1 ? " Zeile " + singular : " Zeilen " + plural);
        return known.size() > 1 ? text + " (weitere Zählungen, z.B. durch Trigger: "
                + known.subList(1, known.size()) + ")" : text;
    }

    private static String statements(Kind kind) {
        return switch (kind) {
            case INSERT -> "INSERT-Anweisungen";
            case UPDATE -> "UPDATE-, MERGE- und REPLACE-Anweisungen";
            case DELETE -> "DELETE-Anweisungen";
            default -> kind.label;
        };
    }

    /** Was erkannt wurde, z.B. „DELETE“ oder „SELECT mit DELETE“. */
    private static String describe(Analysis a) {
        String keyword = a.keyword().isEmpty() ? "?" : a.keyword();
        Set<Kind> other = EnumSet.copyOf(a.kinds());
        other.remove(a.primary());
        other.remove(Kind.QUERY);
        if (other.isEmpty()) {
            return keyword;
        }
        return keyword + " mit " + other.stream().map(k -> k.shortLabel).collect(Collectors.joining(", "))
                + " (" + other.stream().map(k -> k.tool).collect(Collectors.joining(", ")) + ")";
    }

    /** Welches Tool passt zur erkannten Anweisung. */
    private static String toolHint(Analysis a) {
        if (a.kinds().contains(Kind.OTHER) || a.primary() == Kind.OTHER) {
            return "Nicht eindeutig einzuordnen (Prozeduraufruf, SELECT … INTO, datenbankspezifischer Befehl oder "
                    + "Backslash/Kommentar in Zeichenketten?) – Werte als Parameter (?) übergeben oder, falls "
                    + "freigegeben, jdbc_execute verwenden.";
        }
        if (a.primary() == Kind.DDL) {
            return "Strukturänderungen über jdbc_ddl.";
        }
        Set<Kind> changes = EnumSet.copyOf(a.kinds());
        changes.remove(Kind.QUERY);
        if (!changes.isEmpty()) {
            return "Passendes Tool: " + changes.stream().map(k -> k.tool).collect(Collectors.joining(" bzw. ")) + ".";
        }
        return "Lesende Anweisungen über jdbc_query.";
    }

    private static long millis(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}
