package systems.grebe.devtools.mcp.modules.ticket;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.modules.ticket.spi.TicketSystem;

import static systems.grebe.devtools.mcp.modules.ticket.TicketTools.PROJECT;
import static systems.grebe.devtools.mcp.modules.ticket.TicketTools.PROVIDER;

/** Statuswechsel (Schalter {@code allowTransition}); Kommentar dazu nur mit {@code allowComment}. */
public class TicketTransitionTools {

    private final TicketEnvironment env;
    private final boolean commentAllowed;

    TicketTransitionTools(TicketEnvironment env, boolean commentAllowed) {
        this.env = env;
        this.commentAllowed = commentAllowed;
    }

    @Tool(name = "transition", description = "Wechselt den Status eines Tickets: Jira-Workflow-Übergang, Schließen oder "
            + "Wiedereröffnen, GitHub-Project-Spalte, GitLab-Board-Liste. Ziel per ID, Name oder Zielstatus aus "
            + "ticket_transitions. Nur auf ausdrückliche Anweisung des Nutzers." + ShellHints.TICKET)
    public String transition(
            @ToolParam(description = "Ticket-Schlüssel oder URL") String key,
            @ToolParam(description = "Ziel: ID, Name oder Zielstatus aus ticket_transitions, z.B. 'In Arbeit' oder 'close:completed'") String to,
            @ToolParam(required = false, description = "Kommentar zum Wechsel (braucht zusätzlich die Freigabe Kommentieren)") String comment,
            @ToolParam(required = false, description = PROJECT) String project,
            @ToolParam(required = false, description = PROVIDER) String provider) {
        TicketEnvironment.Entry e = env.resolve(provider, key);
        String k = key.trim();
        String p = e.project(project);
        env.checkWrite(e, k, project, "Statuswechsel");
        boolean withComment = comment != null && !comment.isBlank();
        if (withComment && !commentAllowed) {
            throw new IllegalStateException("Kommentar zum Statuswechsel abgelehnt: Kommentieren ist in der DevTools-App "
                    + "abgeschaltet (Module → Tickets → 'Kommentieren erlauben'). Ohne 'comment' erneut aufrufen oder den "
                    + "Nutzer fragen.");
        }
        TicketSystem.Transition t = TicketSystem.pickTransition(e.system().transitions(k, p), to, e.provider().displayName(), k);
        StringBuilder out = new StringBuilder(TicketTools.written(e.system().transition(k, p, t)));
        if (withComment) {
            try {
                out.append("\n").append(TicketTools.written(env.remember(e, e.system().comment(k, p, env.commentBody(comment)), false)));
            } catch (RuntimeException ex) {
                // der Wechsel ist bereits erfolgt – nicht als Fehlschlag des ganzen Aufrufs melden
                out.append("\nStatus gewechselt, aber Kommentar fehlgeschlagen: ").append(ex.getMessage());
            }
        }
        return out.toString();
    }
}
