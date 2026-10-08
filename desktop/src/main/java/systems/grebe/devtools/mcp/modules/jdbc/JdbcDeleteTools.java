package systems.grebe.devtools.mcp.modules.jdbc;

import java.util.List;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.ToolHints;
import systems.grebe.devtools.mcp.modules.jdbc.SqlStatements.Kind;

/** Datensätze löschen (Schalter „Datensätze löschen“). */
@ToolHints(destructive = true)
public class JdbcDeleteTools {

    private final JdbcRunner runner;

    JdbcDeleteTools(JdbcRunner runner) {
        this.runner = runner;
    }

    @Tool(name = "delete", description = "Löscht Datensätze mit einer DELETE-Anweisung und meldet die Zahl der gelöschten "
            + "Zeilen. Werte als Platzhalter ? mit 'params'. In einer Transaktion; dryRun=true zählt nur und rollt "
            + "zurück. DELETE ohne WHERE wird nur mit allRows=true ausgeführt (Tabelle leeren: besser TRUNCATE über "
            + "jdbc_ddl). Nur auf ausdrückliche Anweisung des Nutzers." + ShellHints.JDBC)
    public String delete(
            @ToolParam(required = false, description = JdbcTools.CONNECTION) String connection,
            @ToolParam(description = "SQL, z.B. \"DELETE FROM bestellungen WHERE status = ? AND erstellt < ?\"") String sql,
            @ToolParam(required = false, description = JdbcQueryTools.PARAMS) List<Object> params,
            @ToolParam(required = false, description = "true = nur prüfen: ausführen und zurückrollen") Boolean dryRun,
            @ToolParam(required = false, description = "true = DELETE ohne WHERE (alle Zeilen) ist gewollt") Boolean allRows) {
        return runner.dml(connection, Kind.DELETE, sql, params, Boolean.TRUE.equals(dryRun), Boolean.TRUE.equals(allRows));
    }
}
