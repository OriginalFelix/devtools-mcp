package systems.grebe.devtools.mcp.modules.jdbc;

import java.util.List;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.ToolHints;
import systems.grebe.devtools.mcp.modules.jdbc.SqlStatements.Kind;

/** Datensätze einfügen (Schalter „Datensätze einfügen“). */
@ToolHints(destructive = false)
public class JdbcInsertTools {

    private final JdbcRunner runner;

    JdbcInsertTools(JdbcRunner runner) {
        this.runner = runner;
    }

    @Tool(name = "insert", description = "Fügt Datensätze in eine Tabelle ein – entweder 'rows' als Liste von Objekten "
            + "{spalte: wert} (Spaltennamen und Typen kommen aus der Datenbank, Werte werden passend umgewandelt) oder "
            + "eine INSERT-Anweisung in 'sql' mit 'params' (z.B. INSERT … SELECT). Alles in einer Transaktion: bei einem "
            + "Fehler wird nichts eingefügt. dryRun=true fügt probeweise ein und rollt zurück. Nur auf ausdrückliche "
            + "Anweisung des Nutzers." + ShellHints.JDBC)
    public String insert(
            @ToolParam(required = false, description = JdbcTools.CONNECTION) String connection,
            @ToolParam(required = false, description = "Tabelle für 'rows', optional qualifiziert (schema.tabelle)")
            String table,
            @ToolParam(required = false, description = "Zeilen, z.B. [{\"name\": \"Müller\", \"ort\": \"Köln\"}]; "
                    + "höchstens " + JdbcRunner.MAX_INSERT_ROWS + " je Aufruf") List<Map<String, Object>> rows,
            @ToolParam(required = false, description = "Schema der Tabelle, falls nicht in 'table'") String schema,
            @ToolParam(required = false, description = "Statt 'rows': eine INSERT-Anweisung mit Platzhaltern ?") String sql,
            @ToolParam(required = false, description = JdbcQueryTools.PARAMS) List<Object> params,
            @ToolParam(required = false, description = "true = nur prüfen: ausführen und zurückrollen") Boolean dryRun) {
        boolean probe = Boolean.TRUE.equals(dryRun);
        if (sql != null && !sql.isBlank()) {
            if (rows != null && !rows.isEmpty()) {
                throw new IllegalArgumentException("Entweder 'rows' oder 'sql' angeben, nicht beides.");
            }
            return runner.dml(connection, Kind.INSERT, sql, params, probe, false);
        }
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("'table' und 'rows' angeben – oder eine INSERT-Anweisung in 'sql'.");
        }
        return runner.insertRows(connection, schema, table, rows, probe);
    }
}
