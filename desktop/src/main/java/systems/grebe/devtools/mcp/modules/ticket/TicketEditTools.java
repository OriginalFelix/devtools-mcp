package systems.grebe.devtools.mcp.modules.ticket;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.modules.ticket.spi.TicketSystem;

import static systems.grebe.devtools.mcp.modules.ticket.TicketTools.PROJECT;
import static systems.grebe.devtools.mcp.modules.ticket.TicketTools.PROVIDER;

/** Titel, Beschreibung, Labels und weitere Felder ändern (Schalter {@code allowEdit}). */
public class TicketEditTools {

    private final TicketEnvironment env;

    TicketEditTools(TicketEnvironment env) {
        this.env = env;
    }

    @Tool(name = "update", description = "Ändert Titel, Beschreibung, Labels und/oder weitere Felder eines Tickets. "
            + "Nicht angegebene Felder bleiben unverändert; labels ersetzt die Label-Liste vollständig. Beschreibung "
            + "vorher mit ticket_get lesen, sie wird ersetzt. fields setzt weitere Felder per Name oder ID (Jira: Custom "
            + "Fields wie 'Tester', Lösungsversionen, Komponenten); leerer Wert leert das Feld. Nur auf ausdrückliche "
            + "Anweisung des Nutzers." + ShellHints.TICKET)
    public String update(
            @ToolParam(description = "Ticket-Schlüssel oder URL") String key,
            @ToolParam(required = false, description = "Neuer Titel") String title,
            @ToolParam(required = false, description = "Neue Beschreibung (ersetzt die bisherige vollständig)") String description,
            @ToolParam(required = false, description = "Neue Label-Liste (ersetzt die bisherige; [] = alle entfernen)") List<String> labels,
            @ToolParam(required = false, description = "Weitere Felder: Name oder Feld-ID → Wert, z.B. {\"Tester\": \"\"} "
                    + "(leert), {\"customfield_10368\": \"me\"}, {\"Lösungsversionen\": \"43.1, 42.6\"}. Benutzer per "
                    + "Name/E-Mail oder me, Listen als Kommaliste; Werte, die mit { oder [ beginnen, gehen als JSON hinaus") Map<String, String> fields,
            @ToolParam(required = false, description = PROJECT) String project,
            @ToolParam(required = false, description = PROVIDER) String provider) {
        TicketSystem.TicketUpdate u = new TicketSystem.TicketUpdate(blankToNull(title), description, labels);
        boolean withFields = fields != null && !fields.isEmpty();
        if (u.isEmpty() && !withFields) {
            throw new IllegalArgumentException("Nichts zu ändern – title, description, labels oder fields angeben.");
        }
        TicketEnvironment.Entry e = env.resolve(provider, key);
        env.checkWrite(e, key, project, "Bearbeiten");
        List<String> out = new ArrayList<>();
        if (!u.isEmpty()) {
            out.add(TicketTools.written(e.system().update(key.trim(), e.project(project), u)));
        }
        if (withFields) {
            out.add(TicketTools.written(e.system().updateFields(key.trim(), e.project(project), fields)));
        }
        return String.join("\n", out);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
