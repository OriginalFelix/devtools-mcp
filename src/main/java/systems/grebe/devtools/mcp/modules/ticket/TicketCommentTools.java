package systems.grebe.devtools.mcp.modules.ticket;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;

import static systems.grebe.devtools.mcp.modules.ticket.TicketTools.PROJECT;
import static systems.grebe.devtools.mcp.modules.ticket.TicketTools.PROVIDER;

/** Kommentieren (Schalter {@code allowComment}). */
public class TicketCommentTools {

    private final TicketEnvironment env;

    TicketCommentTools(TicketEnvironment env) {
        this.env = env;
    }

    @Tool(name = "comment", description = "Fügt einem Ticket einen Kommentar hinzu (Jira: Wiki-Markup, GitHub/GitLab: "
            + "Markdown). Nur auf ausdrückliche Anweisung des Nutzers." + ShellHints.TICKET)
    public String comment(
            @ToolParam(description = "Ticket-Schlüssel oder URL") String key,
            @ToolParam(description = "Kommentartext") String body,
            @ToolParam(required = false, description = PROJECT) String project,
            @ToolParam(required = false, description = PROVIDER) String provider) {
        TicketEnvironment.Entry e = env.resolve(provider, key);
        env.checkWrite(e, key, project, "Kommentieren");
        return TicketTools.written(e.system().comment(key.trim(), e.project(project), env.commentBody(body)));
    }
}
