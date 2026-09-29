package systems.grebe.devtools.mcp.modules.ticket;

import java.util.List;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.modules.ticket.spi.TicketSystem;

import static systems.grebe.devtools.mcp.modules.ticket.TicketTools.PROJECT;
import static systems.grebe.devtools.mcp.modules.ticket.TicketTools.PROVIDER;

/** Titel, Beschreibung, Labels ändern (Schalter {@code allowEdit}). */
public class TicketEditTools {

    private final TicketEnvironment env;

    TicketEditTools(TicketEnvironment env) {
        this.env = env;
    }

    @Tool(name = "update", description = "Ändert Titel, Beschreibung und/oder Labels eines Tickets. Nicht angegebene "
            + "Felder bleiben unverändert; labels ersetzt die Label-Liste vollständig. Beschreibung vorher mit ticket_get "
            + "lesen, sie wird ersetzt. Nur auf ausdrückliche Anweisung des Nutzers." + ShellHints.TICKET)
    public String update(
            @ToolParam(description = "Ticket-Schlüssel oder URL") String key,
            @ToolParam(required = false, description = "Neuer Titel") String title,
            @ToolParam(required = false, description = "Neue Beschreibung (ersetzt die bisherige vollständig)") String description,
            @ToolParam(required = false, description = "Neue Label-Liste (ersetzt die bisherige; [] = alle entfernen)") List<String> labels,
            @ToolParam(required = false, description = PROJECT) String project,
            @ToolParam(required = false, description = PROVIDER) String provider) {
        TicketSystem.TicketUpdate u = new TicketSystem.TicketUpdate(blankToNull(title), description, labels);
        if (u.isEmpty()) {
            throw new IllegalArgumentException("Nichts zu ändern – title, description oder labels angeben.");
        }
        TicketEnvironment.Entry e = env.resolve(provider, key);
        env.checkWrite(e, key, project, "Bearbeiten");
        return TicketTools.written(e.system().update(key.trim(), e.project(project), u));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
