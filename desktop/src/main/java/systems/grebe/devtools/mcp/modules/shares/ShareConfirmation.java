package systems.grebe.devtools.mcp.modules.shares;

import java.util.ArrayList;
import java.util.List;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import systems.grebe.devtools.mcp.core.UserConfirmation;

/**
 * Gemeinsames der Tools {@code skills_share} und {@code memories_share}: Parameter lesen und – bevor das LLM etwas
 * für andere freigibt – den Nutzer fragen (MCP-Client oder Dialog der App). Zurücknehmen und Anzeigen brauchen keine
 * Rückfrage.
 */
public final class ShareConfirmation {

    public static final String USERS = "Benutzer auf dem Team-Server: Anmeldename oder E-Mail, z.B. ['anna']";
    public static final String ROLES = "Rollen, deren Benutzer es sehen sollen, z.B. ['Entwickler']";
    public static final String EVERYONE = "true = für alle Benutzer (braucht das Recht „Mit allen teilen“)";
    public static final String REVOKE = "true = die angegebenen Freigaben zurücknehmen";

    private ShareConfirmation() {
    }

    public static ShareViews.Request request(List<String> users, List<String> roles, Boolean everyone) {
        return new ShareViews.Request(users, roles, Boolean.TRUE.equals(everyone));
    }

    /**
     * Fragt beim Freigeben nach; wirft, wenn der Nutzer ablehnt oder niemand gefragt werden kann.
     *
     * @param what z.B. „Skill 'heap-leak'“
     */
    public static void confirm(UserConfirmation confirmation, McpSyncServerExchange exchange, String what,
                               ShareViews.Request request, boolean revoke) {
        if (revoke || request.empty()) {
            return;
        }
        String refused = what + " nicht geteilt";
        if (confirmation == null) {
            throw new IllegalStateException(refused + ": keine Rückfrage beim Nutzer möglich.");
        }
        UserConfirmation.Result r = confirmation.ask(exchange, UserConfirmation.Channel.AUTO, what + " teilen?",
                what + " wird sichtbar für " + targets(request) + ". Die Empfänger sehen immer den aktuellen Stand "
                        + "(schreibgeschützt), bis die Freigabe zurückgenommen wird.");
        switch (r.answer()) {
            case GRANTED -> { }
            case DECLINED -> throw new IllegalStateException(refused + ": vom Nutzer abgelehnt (" + r.via() + "). "
                    + "Nicht erneut versuchen, ohne dass der Nutzer es ausdrücklich will.");
            default -> throw new IllegalStateException(refused + ": keine Rückfrage möglich (" + r.via() + "). Der "
                    + "Nutzer kann es in der App unter Skills bzw. Memories mit „Teilen…“ selbst freigeben.");
        }
    }

    static String targets(ShareViews.Request request) {
        List<String> parts = new ArrayList<>();
        if (request.everyone()) {
            parts.add("alle Benutzer");
        }
        request.users().forEach(parts::add);
        request.roles().forEach(r -> parts.add("Rolle " + r));
        return String.join(", ", parts);
    }
}
