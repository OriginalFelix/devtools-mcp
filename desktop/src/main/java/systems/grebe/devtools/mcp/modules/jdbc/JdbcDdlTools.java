package systems.grebe.devtools.mcp.modules.jdbc;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.ToolHints;

/** Struktur ändern (Schalter „Struktur ändern“). */
@ToolHints(destructive = true)
public class JdbcDdlTools {

    private final JdbcRunner runner;

    JdbcDdlTools(JdbcRunner runner) {
        this.runner = runner;
    }

    @Tool(name = "ddl", description = "Ändert die Datenbankstruktur: CREATE, ALTER, DROP, TRUNCATE, RENAME, COMMENT – "
            + "Tabellen, Spalten, Indizes, Views, Sequenzen, Constraints. Wird sofort festgeschrieben (viele Datenbanken "
            + "können DDL nicht zurückrollen). Vorher mit jdbc_describe den Ist-Stand ansehen. Genau eine Anweisung je "
            + "Aufruf; Prozeduren und Trigger mit ; im Rumpf über jdbc_execute. Nur auf ausdrückliche Anweisung des "
            + "Nutzers." + ShellHints.JDBC)
    public String ddl(
            @ToolParam(required = false, description = JdbcTools.CONNECTION) String connection,
            @ToolParam(description = "SQL, z.B. \"ALTER TABLE kunden ADD COLUMN email VARCHAR(200)\"") String sql) {
        return runner.ddl(connection, sql);
    }
}
