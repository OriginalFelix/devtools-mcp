package systems.grebe.devtools.mcp.modules.jdbc;

import java.util.List;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.ToolHints;

/** Datensätze lesen (Schalter „Datensätze lesen“). */
@ToolHints(readOnly = true)
public class JdbcQueryTools {

    static final String PARAMS = "Werte für die Platzhalter ? in Reihenfolge, z.B. [42, \"Müller\", null]; Datum als "
            + "\"2024-05-01\" bzw. \"2024-05-01 12:30:00\"";

    private final JdbcRunner runner;

    JdbcQueryTools(JdbcRunner runner) {
        this.runner = runner;
    }

    @Tool(name = "query", description = "Führt eine lesende SQL-Anweisung aus (SELECT, WITH, VALUES, SHOW, EXPLAIN) "
            + "und liefert die Zeilen als Tabelle, CSV oder JSON – begrenzt auf maxRows. Werte als Platzhalter ? mit "
            + "'params' übergeben statt sie ins SQL einzusetzen. Läuft in einer schreibgeschützten Transaktion, die "
            + "immer zurückgerollt wird; ändernde Anweisungen werden abgelehnt. Genau eine Anweisung je Aufruf."
            + ShellHints.JDBC)
    public String query(
            @ToolParam(required = false, description = JdbcTools.CONNECTION) String connection,
            @ToolParam(description = "SQL, z.B. \"SELECT id, name FROM kunden WHERE ort = ? ORDER BY name\"") String sql,
            @ToolParam(required = false, description = PARAMS) List<Object> params,
            @ToolParam(required = false, description = "Höchstens so viele Zeilen (Standard und Maximum: Einstellung "
                    + "im Modul)") Integer maxRows,
            @ToolParam(required = false, description = "table (Standard), csv oder json") String format) {
        return runner.query(connection, sql, params, maxRows, format);
    }
}
