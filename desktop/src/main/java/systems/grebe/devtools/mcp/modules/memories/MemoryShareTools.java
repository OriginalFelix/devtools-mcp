package systems.grebe.devtools.mcp.modules.memories;

import java.util.List;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.ToolHints;
import systems.grebe.devtools.mcp.core.UserConfirmation;
import systems.grebe.devtools.mcp.modules.shares.ShareConfirmation;
import systems.grebe.devtools.mcp.modules.shares.ShareViews;

/** Teilen eigener Memories mit Benutzern, Rollen oder allen auf dem Team-Server (eigener Schalter). */
public class MemoryShareTools {

    private final MemoryBackend service;
    private final UserConfirmation confirmation;

    MemoryShareTools(MemoryBackend service, UserConfirmation confirmation) {
        this.service = service;
        this.confirmation = confirmation;
    }

    @Tool(name = "share", description = "Teilt eine eigene Memory mit Benutzern, Rollen oder allen auf dem "
            + "Team-Server – sie finden sie mit memories_search und lesen sie samt Dateien, schreibgeschützt. Ohne "
            + "Ziele: aktuelle Freigaben; revoke=true nimmt zurück. Der Nutzer bestätigt das Teilen. Nur auf "
            + "ausdrücklichen Wunsch." + ShellHints.MEMORIES)
    @ToolHints(destructive = false, openWorld = true)
    public String share(
            @ToolParam(description = MemoryReadTools.ID) long id,
            @ToolParam(required = false, description = ShareConfirmation.USERS) List<String> users,
            @ToolParam(required = false, description = ShareConfirmation.ROLES) List<String> roles,
            @ToolParam(required = false, description = ShareConfirmation.EVERYONE) Boolean everyone,
            @ToolParam(required = false, description = ShareConfirmation.REVOKE) Boolean revoke,
            ToolContext toolContext) {
        ShareViews.Request request = ShareConfirmation.request(users, roles, everyone);
        boolean back = Boolean.TRUE.equals(revoke);
        ShareConfirmation.confirm(confirmation, UserConfirmation.exchange(toolContext), "Memory #" + id, request,
                back);
        return service.share(id, request, back);
    }
}
