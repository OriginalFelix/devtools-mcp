package systems.grebe.devtools.mcp.modules.ticket;

import java.util.List;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.modules.ticket.spi.TicketSystem;

import static systems.grebe.devtools.mcp.modules.ticket.TicketTools.PROJECT;
import static systems.grebe.devtools.mcp.modules.ticket.TicketTools.PROVIDER;

/**
 * Tickets verknüpfen und Verknüpfungen entfernen (Schalter {@code allowLink}). Die Schreibfreigabe wird für beide Tickets
 * geprüft – je nach System ändert eine Verknüpfung beide (GitHub {@code blocks}, OpenProject Unteraufgabe).
 */
public class TicketLinkTools {

    private final TicketEnvironment env;

    TicketLinkTools(TicketEnvironment env) {
        this.env = env;
    }

    @Tool(name = "link", description = "Verknüpft zwei Tickets desselben Systems: key <relation> target, z.B. ABC-1 blocks "
            + "ABC-2. Arten je System: Jira-Linktypen (blocks, relates to, duplicates, clones …), GitHub Parent/Sub-Issue und "
            + "blocks/is blocked by, GitLab relates to/blocks/is blocked by, YouTrack-Linktypen (relates to, depends on, "
            + "subtask of …), OpenProject-Beziehungen und Parent/Unteraufgabe. Ohne relation: listet die möglichen Arten, "
            + "ohne etwas zu ändern. Nur auf ausdrückliche Anweisung des Nutzers." + ShellHints.TICKET)
    public String link(
            @ToolParam(description = "Ticket-Schlüssel oder URL, von dem aus die Beziehung gilt") String key,
            @ToolParam(required = false, description = "Art aus Sicht von key, Name oder ID (z.B. 'blocks', 'is blocked by', "
                    + "'relates to', 'Parent' = target wird Parent von key). Leer = mögliche Arten auflisten") String relation,
            @ToolParam(required = false, description = "Ziel-Ticket (Schlüssel oder URL, gleiches System)") String target,
            @ToolParam(required = false, description = PROJECT) String project,
            @ToolParam(required = false, description = PROVIDER) String provider) {
        TicketEnvironment.Entry e = env.resolve(provider, key);
        String p = e.project(project);
        if (relation == null || relation.isBlank()) {
            List<TicketSystem.LinkType> types = e.system().linkTypes(p);
            StringBuilder sb = new StringBuilder("Mögliche Verknüpfungen (" + e.provider().id() + ", relation = Name oder ID):\n");
            for (TicketSystem.LinkType t : types) {
                sb.append("- [").append(t.id()).append("]  ").append(key == null || key.isBlank() ? "key" : key.trim())
                        .append(' ').append(t.name()).append(" target");
                if (t.inverse() != null && !t.inverse().equalsIgnoreCase(t.name())) {
                    sb.append("  (Gegenrichtung: ").append(t.inverse()).append(')');
                }
                sb.append('\n');
            }
            return sb.toString().strip();
        }
        String to = requireTarget(e, key, target, p);
        env.checkWrite(e, key, project, "Verknüpfen");
        env.checkWrite(e, to, project, "Verknüpfen");
        TicketSystem.LinkType type = TicketSystem.pickLinkType(e.system().linkTypes(p), relation, e.provider().displayName());
        return TicketTools.written(e.system().link(key.trim(), p, type, to));
    }

    @Tool(name = "unlink", description = "Entfernt eine Verknüpfung zwischen zwei Tickets (wie ticket_links sie zeigt). "
            + "Bei mehreren Verknüpfungen zwischen beiden die gemeinte mit relation angeben. Nur auf ausdrückliche "
            + "Anweisung des Nutzers." + ShellHints.TICKET)
    public String unlink(
            @ToolParam(description = "Ticket-Schlüssel oder URL") String key,
            @ToolParam(description = "Verknüpftes Ticket (Schlüssel oder URL)") String target,
            @ToolParam(required = false, description = "Beziehung aus Sicht von key wie in ticket_links (z.B. 'blocks', "
                    + "'Parent'); leer = die einzige zwischen beiden") String relation,
            @ToolParam(required = false, description = PROJECT) String project,
            @ToolParam(required = false, description = PROVIDER) String provider) {
        TicketEnvironment.Entry e = env.resolve(provider, key);
        String p = e.project(project);
        String to = requireTarget(e, key, target, p);
        env.checkWrite(e, key, project, "Verknüpfung entfernen");
        env.checkWrite(e, to, project, "Verknüpfung entfernen");
        return TicketTools.written(e.system().unlink(key.trim(), p, to,
                relation == null || relation.isBlank() ? null : relation.trim()));
    }

    private String requireTarget(TicketEnvironment.Entry e, String key, String target, String project) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Ticket fehlt ('key').");
        }
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("Ziel-Ticket fehlt ('target').");
        }
        if (env.canonical(e, key, project).equals(env.canonical(e, target, project))) {
            throw new IllegalArgumentException("Ein Ticket lässt sich nicht mit sich selbst verknüpfen.");
        }
        return target.trim();
    }
}
