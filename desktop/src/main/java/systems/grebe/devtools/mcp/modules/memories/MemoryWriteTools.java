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

    @Tool(name = "save", description = "Hält eine abgeschlossene Aktion als Memory fest. Nach Ticket-Review, "
            + "Fehleranalyse, Fix, PR, Deployment oder Entscheidung: was getan wurde, Ergebnis, Begründung, offene "
            + "Punkte – mit Ticket-/PR-Nummern. Folgeaktion zur selben Sache: memories_update mit append. Keine "
            + "Geheimnisse." + ShellHints.MEMORIES)
    public String save(
            @ToolParam(description = "Eine Zeile, was getan wurde, z.B. 'Ticket ABC-123 reviewt: Kriterien fehlen'") String title,
            @ToolParam(description = "Markdown: Ergebnis, Begründung, offene Punkte") String content,
            @ToolParam(required = false, description = "Projekt aus projects_list") String project,
            @ToolParam(required = false, description = "Skill, nach dem gearbeitet wurde, z.B. 'ticket-review'") String skill,
            @ToolParam(required = false, description = "Ticket-Key, PR oder Commit, z.B. 'ABC-123'") String reference,
            @ToolParam(required = false, description = "Schlagwörter") List<String> tags) {
        return service.save(title, content, project, skill, reference, tags, maxContentChars);
    }

    @Tool(name = "update", description = "Ergänzt eine Memory um Nachtrag oder Korrektur. append hängt einen "
            + "datierten Nachtrag an, content ersetzt den Inhalt; übrige Felder einzeln (leer = entfernen)."
            + ShellHints.MEMORIES)
    public String update(
            @ToolParam(description = MemoryReadTools.ID) long id,
            @ToolParam(required = false, description = "Nachtrag") String append,
            @ToolParam(required = false, description = "Neuer Inhalt statt append") String content,
            @ToolParam(required = false, description = "Neuer Titel") String title,
            @ToolParam(required = false, description = "Neues Projekt") String project,
            @ToolParam(required = false, description = "Neuer Skill") String skill,
            @ToolParam(required = false, description = "Neuer Bezug") String reference,
            @ToolParam(required = false, description = "Neue Tags") List<String> tags) {
        return service.update(id, title, content, append, project, skill, reference, tags, maxContentChars);
    }
}
