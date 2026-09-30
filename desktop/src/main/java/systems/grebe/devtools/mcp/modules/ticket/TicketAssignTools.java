package systems.grebe.devtools.mcp.modules.ticket;

import java.util.List;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;

import static systems.grebe.devtools.mcp.modules.ticket.TicketTools.PROJECT;
import static systems.grebe.devtools.mcp.modules.ticket.TicketTools.PROVIDER;

/** Zuweisen (Schalter {@code allowAssign}). */
public class TicketAssignTools {

    private final TicketEnvironment env;

    TicketAssignTools(TicketEnvironment env) {
        this.env = env;
    }

    @Tool(name = "assign", description = "Setzt die Zuständigen eines Tickets (ersetzt die bisherigen). me = angemeldeter "
            + "Benutzer, leere Liste oder none = niemand. Jira erlaubt genau einen Zuständigen. Nur auf ausdrückliche "
            + "Anweisung des Nutzers." + ShellHints.TICKET)
    public String assign(
            @ToolParam(description = "Ticket-Schlüssel oder URL") String key,
            @ToolParam(description = "Benutzernamen (GitHub-Login, GitLab-Username, Jira-Benutzer/E-Mail/Anzeigename), "
                    + "me oder none; leer = Zuweisung entfernen") List<String> assignees,
            @ToolParam(required = false, description = PROJECT) String project,
            @ToolParam(required = false, description = PROVIDER) String provider) {
        TicketEnvironment.Entry e = env.resolve(provider, key);
        env.checkWrite(e, key, project, "Zuweisen");
        List<String> who = assignees == null ? List.of()
                : assignees.stream().filter(a -> a != null && !a.isBlank()).map(String::trim).toList();
        return TicketTools.written(e.system().assign(key.trim(), e.project(project), who));
    }
}
