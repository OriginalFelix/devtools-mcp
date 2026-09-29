package systems.grebe.devtools.mcp.modules.ticket;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.modules.ticket.spi.TicketSystem;

import static systems.grebe.devtools.mcp.modules.ticket.TicketTools.PROJECT;
import static systems.grebe.devtools.mcp.modules.ticket.TicketTools.PROVIDER;

/**
 * Löschen (Schalter {@code allowDelete}). Mit {@code deleteOnlyOwn} (Standard an) nur Tickets und Kommentare, die über
 * {@code ticket_create} bzw. {@code ticket_comment} angelegt wurden – wie {@code container_rm} mit {@code removeOnlyOwn}.
 */
public class TicketDeleteTools {

    private final TicketEnvironment env;
    private final boolean onlyOwn;

    TicketDeleteTools(TicketEnvironment env, boolean onlyOwn) {
        this.env = env;
        this.onlyOwn = onlyOwn;
    }

    @Tool(name = "delete_comment", description = "Löscht einen Kommentar eines Tickets (ID aus ticket_get). Standardmäßig nur "
            + "Kommentare, die über ticket_comment angelegt wurden. Nur auf ausdrückliche Anweisung des Nutzers."
            + ShellHints.TICKET)
    public String deleteComment(
            @ToolParam(description = "Ticket-Schlüssel oder URL") String key,
            @ToolParam(description = "Kommentar-ID (in ticket_get hinter 'Kommentar')") String commentId,
            @ToolParam(required = false, description = PROJECT) String project,
            @ToolParam(required = false, description = PROVIDER) String provider) {
        if (commentId == null || commentId.isBlank()) {
            throw new IllegalArgumentException("Kommentar-ID fehlt ('commentId', aus ticket_get).");
        }
        TicketEnvironment.Entry e = env.resolve(provider, key);
        env.checkWrite(e, key, project, "Kommentar löschen");
        String k = env.canonical(e, key, project);
        String id = commentId.trim();
        String instance = e.system().instance();
        if (onlyOwn && !env.ownership().ownsComment(e.provider().id(), instance, k, id)) {
            throw new IllegalStateException("Kommentar " + id + " an " + k + " wurde nicht über ticket_comment angelegt – "
                    + "gelöscht werden nur eigene Kommentare. Fremde Kommentare löschen nur, wenn der Nutzer in der "
                    + "DevTools-App 'Nur selbst angelegte löschen' abschaltet.");
        }
        TicketSystem.WriteResult r = e.system().deleteComment(key.trim(), e.project(project), id);
        env.ownership().removeComment(e.provider().id(), instance, k, id);
        return TicketTools.written(r);
    }

    @Tool(name = "delete", description = "Löscht ein Ticket endgültig (nicht wiederherstellbar). Standardmäßig nur Tickets, "
            + "die über ticket_create angelegt wurden. Meist ist Schließen (ticket_transition) richtig. Nur auf "
            + "ausdrückliche Anweisung des Nutzers." + ShellHints.TICKET)
    public String delete(
            @ToolParam(description = "Ticket-Schlüssel oder URL") String key,
            @ToolParam(required = false, description = PROJECT) String project,
            @ToolParam(required = false, description = PROVIDER) String provider) {
        TicketEnvironment.Entry e = env.resolve(provider, key);
        env.checkWrite(e, key, project, "Ticket löschen");
        String k = env.canonical(e, key, project);
        String instance = e.system().instance();
        if (onlyOwn && !env.ownership().ownsTicket(e.provider().id(), instance, k)) {
            throw new IllegalStateException(k + " wurde nicht über ticket_create angelegt – gelöscht werden nur eigene "
                    + "Tickets. Stattdessen schließen (ticket_transition), oder der Nutzer schaltet in der DevTools-App "
                    + "'Nur selbst angelegte löschen' ab.");
        }
        TicketSystem.WriteResult r = e.system().delete(key.trim(), e.project(project));
        env.ownership().removeTicket(e.provider().id(), instance, k);
        return TicketTools.written(r);
    }
}
