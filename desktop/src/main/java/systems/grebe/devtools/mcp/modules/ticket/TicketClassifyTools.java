package systems.grebe.devtools.mcp.modules.ticket;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.Text;
import systems.grebe.devtools.mcp.modules.ticket.TicketClassifier.Complexity;
import systems.grebe.devtools.mcp.modules.ticket.spi.TicketSystem;

/** Pre-Classifier: Komplexität eines Tickets mit Claude Opus 5.5 einschätzen und das Modell für die Umsetzung empfehlen. */
class TicketClassifyTools {

    /** Kommentare, die in die Einschätzung eingehen – mehr als bei ticket_get, Diskussionen verraten oft den Umfang. */
    private static final int COMMENTS = 10;

    private final TicketEnvironment env;
    private final TicketClassifier classifier;

    TicketClassifyTools(TicketEnvironment env, TicketClassifier classifier) {
        this.env = env;
        this.classifier = classifier;
    }

    @Tool(name = "classify", description = "Pre-Classifier vor der Umsetzung eines Tickets: schätzt die Komplexität "
            + "(Titel, Beschreibung, Typ, Priorität, Story Points, Labels, Kommentare, Verknüpfungen, Projekt und "
            + "Architektur-Kontext) immer mit Claude Opus 5.5 ein und empfiehlt das Modell für die Umsetzung: einfach → "
            + "Haiku, normal → Sonnet, komplex → Opus (Zuordnung in der App einstellbar). Mit dem empfohlenen Modell den "
            + "Subagenten bzw. die Sitzung für die Umsetzung wählen. Ohne 'key' für Aufgaben außerhalb eines "
            + "Ticket-Systems: 'title' und 'description' angeben. Sendet die Ticket-Inhalte an die Claude API."
            + ShellHints.TICKET)
    public String classify(
            @ToolParam(required = false, description = "Ticket-Schlüssel oder URL (ABC-123, owner/repo#12, #123). "
                    + "Leer = Aufgabe aus 'title'/'description'.") String key,
            @ToolParam(required = false, description = TicketTools.PROJECT) String project,
            @ToolParam(required = false, description = "Titel – ohne 'key' Pflicht, mit 'key' ignoriert") String title,
            @ToolParam(required = false, description = "Beschreibung – ohne 'key' die Aufgabe; mit 'key' als Ergänzung "
                    + "angehängt") String description,
            @ToolParam(required = false, description = "Story Points bzw. Schätzung, falls das System sie nicht liefert "
                    + "oder sie abweichen") String storyPoints,
            @ToolParam(required = false, description = "Architektur- und Projektkontext aus dem Code: betroffene Module, "
                    + "Schichten, Schnittstellen, Technologien, Größe des Repositories, bekannte Fallstricke. Je genauer, "
                    + "desto besser die Einschätzung (z.B. aus graph_report oder dem Lesen des Codes).") String context,
            @ToolParam(required = false, description = TicketTools.PROVIDER) String provider) {
        TicketClassifier.Input in = blank(key)
                ? freeText(project, title, description, storyPoints, context)
                : fromTicket(key.trim(), project, description, storyPoints, context, provider);
        TicketClassifier.Result r = classifier.classify(in);
        return format(in, r, classifier.settings());
    }

    private TicketClassifier.Input freeText(String project, String title, String description, String storyPoints,
                                            String context) {
        if (blank(title)) {
            throw new IllegalArgumentException("Weder 'key' noch 'title' angegeben – Ticket-Schlüssel oder Titel und "
                    + "Beschreibung der Aufgabe übergeben.");
        }
        return new TicketClassifier.Input(null, trimOrNull(project), title.trim(), null, null, null, List.of(),
                trimOrNull(storyPoints), null, description, List.of(), List.of(), context);
    }

    private TicketClassifier.Input fromTicket(String key, String project, String description, String storyPoints,
                                              String context, String provider) {
        TicketEnvironment.Entry e = env.resolve(provider, key);
        String p = e.project(project);
        TicketSystem.TicketDetails d = e.system().ticket(key, p, COMMENTS);
        TicketSystem.Ticket t = d.ticket();
        List<String> comments = d.comments().stream()
                .filter(c -> !blank(c.body()))
                .map(c -> Text.orDash(c.author()) + ": " + c.body().strip()).toList();
        String desc = blank(description) ? d.description()
                : (blank(d.description()) ? "" : d.description().strip() + "\n\n") + "Ergänzung: " + description.strip();
        String proj = e.system().projectOf(key, p);
        return new TicketClassifier.Input(t.key(), proj == null ? p : proj, t.title(), t.type(), t.priority(), t.status(),
                t.labels(), trimOrNull(storyPoints), d.fields(), desc, comments, links(e, key, p), context);
    }

    /** Verknüpfungen, soweit das System sie liefert – fehlen sie, wird ohne eingeschätzt. */
    private static List<String> links(TicketEnvironment.Entry e, String key, String project) {
        try {
            return e.system().links(key, project).stream()
                    .map(l -> l.relation() + ": " + l.key() + " [" + Text.orDash(l.status()) + "] " + Text.orDash(l.title()))
                    .toList();
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    static String format(TicketClassifier.Input in, TicketClassifier.Result r, TicketClassifier.Settings s) {
        StringBuilder sb = new StringBuilder();
        sb.append(in.key() == null ? "Aufgabe" : in.key()).append(": ").append(in.title()).append('\n');
        sb.append("Komplexität: ").append(r.complexity().label()).append(" (Sicherheit: ").append(confidence(r.confidence()))
                .append(")\n");
        sb.append("Empfohlenes Modell: ").append(r.model()).append('\n');
        if (!blank(r.summary())) {
            sb.append("\n").append(r.summary()).append('\n');
        }
        list(sb, "Faktoren", r.factors());
        list(sb, "Risiken", r.risks());
        list(sb, "Offene Fragen", r.openQuestions());
        List<String> stages = new ArrayList<>();
        for (Complexity c : Complexity.values()) {
            stages.add(c.label() + " → " + s.model(c));
        }
        sb.append("\nEingeschätzt mit ").append(TicketClassifier.MODEL).append(" (effort ").append(r.effort())
                .append(", ").append(r.inputTokens()).append(" Token ein / ").append(r.outputTokens()).append(" aus). ")
                .append("Zuordnung: ").append(String.join(", ", stages)).append('.');
        return sb.toString();
    }

    private static String confidence(String c) {
        return switch (c == null ? "" : c) {
            case "low" -> "niedrig";
            case "high" -> "hoch";
            default -> "mittel";
        };
    }

    private static void list(StringBuilder sb, String title, List<String> items) {
        if (!items.isEmpty()) {
            sb.append('\n').append(title).append(":\n");
            items.forEach(i -> sb.append("- ").append(i).append('\n'));
        }
    }

    private static String trimOrNull(String s) {
        return blank(s) ? null : s.trim();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
