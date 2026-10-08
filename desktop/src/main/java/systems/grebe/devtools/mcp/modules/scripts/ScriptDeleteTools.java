package systems.grebe.devtools.mcp.modules.scripts;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.ToolHints;

/** Löschen von Skripten (nur mit Schalter „LLM darf Skripte löschen“). */
@ToolHints(destructive = true, idempotent = true, openWorld = false)
public class ScriptDeleteTools {

    private final ScriptManager scripts;

    ScriptDeleteTools(ScriptManager scripts) {
        this.scripts = scripts;
    }

    @Tool(name = "delete", description = "Löscht ein eigenes Groovy-Skript samt Historie; seine Tools verschwinden "
            + "sofort. Nur auf ausdrücklichen Wunsch des Nutzers. Globale Vorlagen lassen sich nicht löschen."
            + ShellHints.SCRIPTS)
    public String delete(@ToolParam(description = ScriptReadTools.NAME) String name) {
        return scripts.delete(name);
    }
}
