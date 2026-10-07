package systems.grebe.devtools.mcp.modules.memories;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;

/**
 * Löschen einer Memory. Immer registriert; ohne den Schalter {@link MemoriesModule#ALLOW_DELETE} (standardmäßig aus)
 * nur für temporäre Memories ({@code temporaryOnly}).
 */
public class MemoryDeleteTools {

    private final MemoryBackend service;
    private final boolean temporaryOnly;

    MemoryDeleteTools(MemoryBackend service, boolean temporaryOnly) {
        this.service = service;
        this.temporaryOnly = temporaryOnly;
    }

    @Tool(name = "delete", description = "Löscht eine Memory endgültig. Temporäre Memories jederzeit (z.B. wenn "
            + "erledigt); dauerhafte nur auf ausdrücklichen Wunsch, sonst memories_update." + ShellHints.MEMORIES)
    public String delete(@ToolParam(description = MemoryReadTools.ID) long id) {
        return service.delete(id, temporaryOnly);
    }
}
