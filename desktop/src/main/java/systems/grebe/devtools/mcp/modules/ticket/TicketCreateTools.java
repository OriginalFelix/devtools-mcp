package systems.grebe.devtools.mcp.modules.ticket;

import java.util.List;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.modules.ticket.spi.TicketSystem;

import static systems.grebe.devtools.mcp.modules.ticket.TicketTools.PROJECT;
import static systems.grebe.devtools.mcp.modules.ticket.TicketTools.PROVIDER;

/** Tickets anlegen (Schalter {@code allowCreate}). */
public class TicketCreateTools {

    private final TicketEnvironment env;

    TicketCreateTools(TicketEnvironment env) {
        this.env = env;
    }

    @Tool(name = "create", description = "Legt ein neues Ticket an (Titel, Beschreibung, Typ, Labels, Zuständige, weitere "
            + "Felder) und liefert Schlüssel und Link. fields setzt weitere Felder per Name oder ID gleich beim Anlegen "
            + "(Jira: Pflichtfelder wie Komponenten, Priorität, Custom Fields). Vorher mit ticket_search nach Duplikaten "
            + "suchen. Nur auf ausdrückliche Anweisung des Nutzers." + ShellHints.TICKET)
    public String create(
            @ToolParam(description = "Titel") String title,
            @ToolParam(required = false, description = "Beschreibung (Jira: Wiki-Markup, sonst Markdown)") String description,
            @ToolParam(required = false, description = PROJECT) String project,
            @ToolParam(required = false, description = "Typ: Jira-Issue-Typ (Standard Task), GitHub-Issue-Typ der "
                    + "Organisation, GitLab issue/incident/task, YouTrack-Feld Type, OpenProject-Typ") String type,
            @ToolParam(required = false, description = "Labels (YouTrack: Tags; OpenProject kennt keine)") List<String> labels,
            @ToolParam(required = false, description = "Zuständige (me, Benutzernamen)") List<String> assignees,
            @ToolParam(required = false, description = "Weitere Felder: Name oder Feld-ID → Wert, z.B. {\"Komponenten\": "
                    + "\"Transform\"}, {\"Priorität\": \"Low\"}, {\"customfield_10368\": \"me\"}. Benutzer per Name/E-Mail "
                    + "oder me, Listen als Kommaliste; Werte, die mit { oder [ beginnen, gehen als JSON hinaus. Jira nimmt die "
                    + "Felder des Create-Screens; andere Systeme setzen sie nach dem Anlegen wie ticket_update") Map<String, String> fields,
            @ToolParam(required = false, description = PROVIDER) String provider) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Titel fehlt ('title').");
        }
        TicketEnvironment.Entry e = env.resolve(provider, null);
        String target = env.checkWrite(e, null, project, "Anlegen");
        return TicketTools.written(env.remember(e, e.system().create(target,
                new TicketSystem.NewTicket(title.trim(), description, type, labels, assignees), fields), true));
    }
}
