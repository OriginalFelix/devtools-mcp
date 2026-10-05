package systems.grebe.devtools.mcp.modules.memories;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;

/** Löschen einer Memory (eigener Schalter, standardmäßig aus). */
public class MemoryDeleteTools {

    private final MemoryBackend service;

    MemoryDeleteTools(MemoryBackend service) {
        this.service = service;
    }

    @Tool(name = "delete", description = "Löscht eine Memory endgültig. Nur auf ausdrücklichen Wunsch; sonst "
            + "memories_update." + ShellHints.MEMORIES)
    public String delete(@ToolParam(description = MemoryReadTools.ID) long id) {
        return service.delete(id);
    }
}
