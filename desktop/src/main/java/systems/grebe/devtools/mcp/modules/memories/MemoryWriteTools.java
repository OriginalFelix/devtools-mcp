package systems.grebe.devtools.mcp.modules.memories;

import java.util.List;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;

/** Schreibende Memory-Tools (nur registriert, wenn in der Konfiguration erlaubt). */
public class MemoryWriteTools {

    private final MemoryBackend service;
    private final int maxContentChars;

    MemoryWriteTools(MemoryBackend service, int maxContentChars) {
        this.service = service;
        this.maxContentChars = maxContentChars;
    }

    @Tool(name = "save", description = "Hält eine abgeschlossene Aktion als Memory fest. Nach einer nennenswerten "
            + "Aktion aufrufen – Ticket reviewt, Fehler analysiert oder behoben, PR erstellt, Deployment, "
            + "Entscheidung mit dem Nutzer: was getan wurde, Ergebnis, Begründung, offene Punkte. Anders als Skills "
            + "(wiederverwendbare Abläufe) gehören hier die konkreten Details hinein: Ticket-/PR-Nummern, Datum, "
            + "betroffene Klassen. Gibt es zur selben Aktion schon eine Memory, stattdessen memories_update mit "
            + "append. Niemals Passwörter, Tokens oder andere Geheimnisse." + ShellHints.MEMORIES)
    public String save(
            @ToolParam(description = "Eine Zeile, was getan wurde (max. 200 Zeichen), z.B. 'Ticket ABC-123 reviewt: "
                    + "Akzeptanzkriterien fehlen, an PO zurück'") String title,
            @ToolParam(description = "Details als Markdown: Ausgangslage, Vorgehen, Ergebnis, Entscheidungen, offene "
                    + "Punkte") String content,
            @ToolParam(required = false, description = "Projekt (Name aus projects_list)") String project,
            @ToolParam(required = false, description = "Skill, nach dem gearbeitet wurde, z.B. 'ticket-review'") String skill,
            @ToolParam(required = false, description = "Bezug: Ticket-Key, PR-Nummer/-URL oder Commit, z.B. 'ABC-123'") String reference,
            @ToolParam(required = false, description = "Schlagwörter für die Suche, z.B. ['review','abgelehnt']") List<String> tags) {
        return service.save(title, content, project, skill, reference, tags, maxContentChars);
    }

    @Tool(name = "update", description = "Ergänzt eine Memory um Nachtrag oder Korrektur. Mit append einen datierten "
            + "Nachtrag anhängen (z.B. Ergebnis nach Rückmeldung, Folgeaktion), mit content den Inhalt ersetzen; "
            + "Titel, Projekt, Skill, Bezug und Tags lassen sich einzeln ändern, leerer Text entfernt sie. Nicht "
            + "angegebene Felder bleiben." + ShellHints.MEMORIES)
    public String update(
            @ToolParam(description = MemoryReadTools.ID) long id,
            @ToolParam(required = false, description = "Nachtrag, der datiert angehängt wird") String append,
            @ToolParam(required = false, description = "Neuer vollständiger Inhalt (statt append)") String content,
            @ToolParam(required = false, description = "Neuer Titel") String title,
            @ToolParam(required = false, description = "Neues Projekt") String project,
            @ToolParam(required = false, description = "Neuer Skill") String skill,
            @ToolParam(required = false, description = "Neuer Bezug") String reference,
            @ToolParam(required = false, description = "Neue Tags (ersetzt alle)") List<String> tags) {
        return service.update(id, title, content, append, project, skill, reference, tags, maxContentChars);
    }
}
