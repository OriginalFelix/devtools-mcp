package systems.grebe.devtools.mcp.modules.memories;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;

/** Lesende Memory-Tools: suchen und laden. */
public class MemoryReadTools {

    static final String ID = "Nummer der Memory (aus memories_search, z.B. 12 für #12)";

    private final MemoryBackend service;

    MemoryReadTools(MemoryBackend service) {
        this.service = service;
    }

    @Tool(name = "search", description = "VOR einer Aufgabe: frühere Aktionen des Nutzers suchen. "
            + "Durchsucht die Memories (was bei früheren Aufgaben getan, entschieden und herausgefunden wurde) über "
            + "Titel, Inhalt, Tags, Bezug, Projekt und Skill; mehrere Suchbegriffe werden nach Trefferzahl gewichtet. "
            + "Ohne Suchtext: die neuesten. Aufrufen, wenn ein Ticket, PR, Fehler oder Thema schon einmal Thema "
            + "gewesen sein könnte, und nach skills_view mit skill=<name> für frühere Durchläufe dieses Ablaufs."
            + ShellHints.MEMORIES)
    public String search(
            @ToolParam(required = false, description = "Suchbegriffe, z.B. 'ABC-123' oder 'heap wildfly'") String query,
            @ToolParam(required = false, description = "Nur dieses Projekt (Name aus projects_list)") String project,
            @ToolParam(required = false, description = "Nur Memories zu diesem Skill, z.B. 'ticket-review'") String skill,
            @ToolParam(required = false, description = "Nur mit diesem Tag") String tag,
            @ToolParam(required = false, description = "Nur die letzten N Tage") Integer days,
            @ToolParam(required = false, description = "Max. Treffer (Standard 10, höchstens 50)") Integer limit) {
        return service.search(query, project, skill, tag, days, limit);
    }

    @Tool(name = "view", description = "Lädt eine gespeicherte Memory (frühere Aktion) vollständig: Titel, Projekt, "
            + "Skill, Bezug, Tags, Zeitpunkte und Inhalt." + ShellHints.MEMORIES)
    public String view(@ToolParam(description = ID) long id) {
        return service.view(id);
    }
}
