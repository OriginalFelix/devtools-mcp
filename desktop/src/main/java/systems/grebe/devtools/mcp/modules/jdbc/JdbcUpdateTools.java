package systems.grebe.devtools.mcp.modules.jdbc;

import java.util.List;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.ToolHints;
import systems.grebe.devtools.mcp.modules.jdbc.SqlStatements.Kind;

/** Datensätze ändern (Schalter „Datensätze ändern“). */
@ToolHints(destructive = true)
public class JdbcUpdateTools {

    private final JdbcRunner runner;

    JdbcUpdateTools(JdbcRunner runner) {
        this.runner = runner;
    }

    @Tool(name = "update", description = "Ändert Datensätze mit einer UPDATE-, MERGE- oder REPLACE-Anweisung und meldet "
            + "die Zahl der betroffenen Zeilen. Werte als Platzhalter ? mit 'params'. In einer Transaktion; dryRun=true "
            + "zählt nur und rollt zurück. UPDATE ohne WHERE wird nur mit allRows=true ausgeführt. Nur auf ausdrückliche "
            + "Anweisung des Nutzers." + ShellHints.JDBC)
    public String update(
            @ToolParam(required = false, description = JdbcTools.CONNECTION) String connection,
            @ToolParam(description = "SQL, z.B. \"UPDATE kunden SET ort = ? WHERE id = ?\"") String sql,
            @ToolParam(required = false, description = JdbcQueryTools.PARAMS) List<Object> params,
            @ToolParam(required = false, description = "true = nur prüfen: ausführen und zurückrollen") Boolean dryRun,
            @ToolParam(required = false, description = "true = UPDATE ohne WHERE (alle Zeilen) ist gewollt") Boolean allRows) {
        return runner.dml(connection, Kind.UPDATE, sql, params, Boolean.TRUE.equals(dryRun), Boolean.TRUE.equals(allRows));
    }
}
