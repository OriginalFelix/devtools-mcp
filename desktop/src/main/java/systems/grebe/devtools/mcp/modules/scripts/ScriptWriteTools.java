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

    @Tool(name = "save", description = "Legt ein Skript (Groovy, Java oder Gherkin) an oder ersetzt es und lädt es sofort als "
            + "Modul mit eigenen Tools (<name>_<tool>). Vorher die Referenz mit scripts_view ohne Namen lesen. Das Skript wird vor "
            + "dem Speichern übersetzt und ausgewertet; Fehler kommen mit Zeilenangabe zurück, gespeichert wird dann "
            + "nichts. Nur auf ausdrücklichen Wunsch des Nutzers – Skripte laufen mit allen Rechten der App."
            + ShellHints.SCRIPTS)
    public String save(
            @ToolParam(description = ScriptReadTools.NAME) String name,
            @ToolParam(description = "Vollständiger Quelltext: Groovy mit module { … } und tool(…) { … }, Java mit "
                    + "einer public class, die ToolModule implementiert, bzw. Gherkin mit Funktionalität und Szenarien")
            String content,
            @ToolParam(required = false, description = "'groovy', 'java' oder 'gherkin'; leer = Sprache des "
                    + "vorhandenen Skripts, neu: groovy") String language,
            @ToolParam(required = false, description = "Kurz, warum geändert wurde (erscheint in der Historie)") String note,
            @ToolParam(required = false, description = "Optional: Revision, auf der die Änderung beruht (aus "
                    + "scripts_view) – weicht sie ab, wird abgelehnt statt fremde Änderungen zu überschreiben") Integer expected_revision) {
        return scripts.save(name, ScriptsModule.language(language), content, note, expected_revision);
    }
}
