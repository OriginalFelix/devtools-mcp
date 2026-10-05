package systems.grebe.devtools.mcp.modules.scripts;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.ToolHints;

/** Anlegen und Ändern von Skripten (nur mit Schalter „LLM darf Skripte anlegen und ändern“). */
@ToolHints(destructive = true, idempotent = true, openWorld = false)
public class ScriptWriteTools {

    private final ScriptManager scripts;

    ScriptWriteTools(ScriptManager scripts) {
        this.scripts = scripts;
    }

    @Tool(name = "save", description = "Legt ein Groovy-Skript an oder ersetzt es und lädt es sofort als Modul mit "
            + "eigenen Tools (<name>_<tool>). Vorher die DSL mit scripts_view ohne Namen lesen. Das Skript wird vor "
            + "dem Speichern übersetzt und ausgewertet; Fehler kommen mit Zeilenangabe zurück, gespeichert wird dann "
            + "nichts. Nur auf ausdrücklichen Wunsch des Nutzers – Skripte laufen mit allen Rechten der App."
            + ShellHints.SCRIPTS)
    public String save(
            @ToolParam(description = ScriptReadTools.NAME) String name,
            @ToolParam(description = "Vollständiger Groovy-Quelltext mit module { … } und mindestens einem tool(…) { … }") String content,
            @ToolParam(required = false, description = "Kurz, warum geändert wurde (erscheint in der Historie)") String note,
            @ToolParam(required = false, description = "Optional: Revision, auf der die Änderung beruht (aus "
                    + "scripts_view) – weicht sie ab, wird abgelehnt statt fremde Änderungen zu überschreiben") Integer expected_revision) {
        return scripts.save(name, content, note, expected_revision);
    }
}
