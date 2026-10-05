package systems.grebe.devtools.mcp.modules.memories;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;

/** Lesende Memory-Tools: suchen und laden. */
public class MemoryReadTools {

    static final String ID = "Nummer der Memory, z.B. 12 für #12";

    private final MemoryBackend service;

    MemoryReadTools(MemoryBackend service) {
        this.service = service;
    }

    @Tool(name = "search", description = "VOR einer Aufgabe: frühere Aktionen des Nutzers suchen. Durchsucht die "
            + "Memories (was bei früheren Aufgaben getan, entschieden, herausgefunden wurde) nach Ticket-Key oder "
            + "Stichworten; genau ein Treffer kommt direkt vollständig, sonst memories_view(id). Mit skill=<name> "
            + "frühere Durchläufe eines Skills. Nennt ggf. auch passende Skills." + ShellHints.MEMORIES)
    public String search(
            @ToolParam(required = false, description = "Ticket-Key oder Stichworte, z.B. 'ABC-123'") String query,
            @ToolParam(required = false, description = "Nur dieses Projekt") String project,
            @ToolParam(required = false, description = "Nur zu diesem Skill") String skill,
            @ToolParam(required = false, description = "Nur mit diesem Tag") String tag,
            @ToolParam(required = false, description = "Nur die letzten N Tage") Integer days,
            @ToolParam(required = false, description = "Max. Treffer (Standard 5)") Integer limit) {
        return service.search(query, project, skill, tag, days, limit);
    }

    @Tool(name = "view", description = "Lädt eine Memory (frühere Aktion) vollständig." + ShellHints.MEMORIES)
    public String view(@ToolParam(description = ID) long id) {
        return service.view(id);
    }
}
