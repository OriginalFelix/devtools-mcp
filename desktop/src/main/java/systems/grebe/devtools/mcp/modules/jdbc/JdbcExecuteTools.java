package systems.grebe.devtools.mcp.modules.jdbc;

import java.util.List;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.ToolHints;

/** Beliebiges SQL (Schalter „Beliebiges SQL ausführen“). */
@ToolHints(destructive = true)
public class JdbcExecuteTools {

    private final JdbcRunner runner;

    JdbcExecuteTools(JdbcRunner runner) {
        this.runner = runner;
    }

    @Tool(name = "execute", description = "Führt beliebiges SQL unverändert aus – für alles, was die anderen jdbc_*-Tools "
            + "nicht abdecken: Prozeduren (CALL/EXEC), PL/SQL- und T-SQL-Blöcke, Prozeduren und Trigger anlegen, GRANT, "
            + "SET, datenbankspezifische Befehle. Liefert Ergebnismengen und Update-Zählungen; Autocommit, nichts wird "
            + "zurückgerollt. Für gewöhnliches Lesen und Ändern die spezifischen Tools verwenden. Nur auf ausdrückliche "
            + "Anweisung des Nutzers." + ShellHints.JDBC)
    public String execute(
            @ToolParam(required = false, description = JdbcTools.CONNECTION) String connection,
            @ToolParam(description = "SQL – geht unverändert an den Treiber (Oracle: kein ; am Ende einfacher "
                    + "Anweisungen, aber END; bei Blöcken)") String sql,
            @ToolParam(required = false, description = JdbcQueryTools.PARAMS) List<Object> params,
            @ToolParam(required = false, description = "Höchstens so viele Zeilen je Ergebnismenge") Integer maxRows) {
        return runner.execute(connection, sql, params, maxRows);
    }
}
