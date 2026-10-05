package systems.grebe.devtools.mcp.modules.ticket;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.Text;
import systems.grebe.devtools.mcp.modules.classify.TaskClassifier;
import systems.grebe.devtools.mcp.modules.ticket.spi.TicketSystem;

/**
 * Pre-Classifier für Tickets: lädt das Ticket und lässt es vom {@link TaskClassifier} (Claude Opus 5.5) einschätzen.
 * API-Key, Modelle je Stufe und Regeln kommen aus dem Modul Modellwahl.
 */
class TicketClassifyTools {

    /** Kommentare, die in die Einschätzung eingehen – mehr als bei ticket_get, Diskussionen verraten oft den Umfang. */
    private static final int COMMENTS = 10;
    private static final Pattern STORY_POINTS = Pattern.compile("(?i)story[ _-]?points?|^gewicht$|^weight$");

    private final TicketEnvironment env;
    private final Supplier<TaskClassifier> classifier;

    TicketClassifyTools(TicketEnvironment env, Supplier<TaskClassifier> classifier) {
        this.env = env;
        this.classifier = classifier;
    }

    @Tool(name = "classify", description = "Pre-Classifier vor der Umsetzung eines Tickets: schätzt die Komplexität "
            + "(Titel, Beschreibung, Typ, Priorität, Story Points, Labels, Kommentare, Verknüpfungen, Projekt und "
            + "Architektur-Kontext) immer mit Claude Opus 5.5 ein und empfiehlt das Modell für die Umsetzung: einfach → "
            + "Haiku, normal → Sonnet, komplex → Opus (Zuordnung im Modul Modellwahl einstellbar). Mit dem empfohlenen "
            + "Modell den Subagenten bzw. die Sitzung für die Umsetzung wählen. Ohne 'key' für Aufgaben außerhalb eines "
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
        TaskClassifier.Input in;
        String heading;
        if (blank(key)) {
            if (blank(title)) {
                throw new IllegalArgumentException("Weder 'key' noch 'title' angegeben – Ticket-Schlüssel oder Titel und "
                        + "Beschreibung der Aufgabe übergeben.");
            }
            Map<String, String> attributes = new LinkedHashMap<>();
            put(attributes, "Projekt", project);
            put(attributes, "Story Points", storyPoints);
            in = new TaskClassifier.Input(title.trim(), description, attributes, null, null, context);
            heading = "Aufgabe: " + title.trim();
        } else {
            in = fromTicket(key.trim(), project, description, storyPoints, context, provider);
            heading = in.attributes().get("Ticket") + ": " + in.title();
        }
        TaskClassifier c = classifier.get();
        return TaskClassifier.format(heading, c.classify(in), c.settings());
    }

    private TaskClassifier.Input fromTicket(String key, String project, String description, String storyPoints,
                                            String context, String provider) {
        TicketEnvironment.Entry e = env.resolve(provider, key);
        String p = e.project(project);
        TicketSystem.TicketDetails d = e.system().ticket(key, p, COMMENTS);
        TicketSystem.Ticket t = d.ticket();
        String proj = e.system().projectOf(key, p);
        Map<String, String> attributes = new LinkedHashMap<>();
        put(attributes, "Ticket", t.key());
        put(attributes, "System", e.provider().displayName());
        put(attributes, "Projekt", proj == null ? p : proj);
        put(attributes, "Typ", t.type());
        put(attributes, "Priorität", t.priority());
        put(attributes, "Status", t.status());
        put(attributes, "Labels", t.labels().isEmpty() ? null : String.join(", ", t.labels()));
        put(attributes, "Story Points", blank(storyPoints) ? storyPoints(d.fields()) : storyPoints);
        d.fields().forEach((k, v) -> attributes.putIfAbsent(k, v));
        List<String> comments = d.comments().stream()
                .filter(c -> !blank(c.body()))
                .map(c -> Text.orDash(c.author()) + ": " + c.body().strip()).toList();
        String desc = blank(description) ? d.description()
                : (blank(d.description()) ? "" : d.description().strip() + "\n\n") + "Ergänzung: " + description.strip();
        return new TaskClassifier.Input(t.title(), desc, attributes, comments, links(e, key, p), context);
    }

    /** Story Points aus den Feldern des Systems (Jira „Story Points“, YouTrack „Story points“, GitLab „Gewicht“ …). */
    static String storyPoints(Map<String, String> fields) {
        for (Map.Entry<String, String> f : fields.entrySet()) {
            if (STORY_POINTS.matcher(f.getKey().strip()).find() && !blank(f.getValue())) {
                return f.getValue().strip();
            }
        }
        return null;
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

    private static void put(Map<String, String> m, String k, String v) {
        if (!blank(v)) {
            m.put(k, v.strip());
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
