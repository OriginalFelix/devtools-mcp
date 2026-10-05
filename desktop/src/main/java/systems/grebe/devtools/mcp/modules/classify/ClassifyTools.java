package systems.grebe.devtools.mcp.modules.classify;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** Allgemeiner Pre-Classifier für beliebige Aufgaben. */
class ClassifyTools {

    private final TaskClassifier classifier;

    ClassifyTools(TaskClassifier classifier) {
        this.classifier = classifier;
    }

    @Tool(name = "task", description = "Pre-Classifier für eine beliebige Aufgabe (Feature, Bugfix, Refactoring, Review, "
            + "Analyse, Recherche, Text, Konzept): schätzt die Komplexität immer mit Claude Opus 5.5 ein und empfiehlt das "
            + "Modell für die Umsetzung: einfach → Haiku, normal → Sonnet, komplex → Opus (Zuordnung in der App einstellbar). "
            + "Liefert Stufe, Sicherheit, Begründung, Faktoren, Risiken und offene Fragen. Mit dem empfohlenen Modell den "
            + "Subagenten bzw. die Sitzung für die Umsetzung wählen. Für Tickets aus einem Ticket-System ticket_classify "
            + "verwenden. Sendet die Aufgabe an die Claude API.")
    public String task(
            @ToolParam(description = "Die Aufgabe so vollständig wie bekannt: Ziel, gewünschtes Ergebnis, "
                    + "Akzeptanzkriterien, bekannte Details") String task,
            @ToolParam(required = false, description = "Kurztitel der Aufgabe") String title,
            @ToolParam(required = false, description = "Art der Aufgabe, z.B. Feature, Bugfix, Refactoring, Review, "
                    + "Analyse, Recherche, Text, Konzept") String kind,
            @ToolParam(required = false, description = "Umfang, falls bekannt: Schätzung, Story Points, betroffene "
                    + "Dateien/Module, Anzahl Quellen") String scope,
            @ToolParam(required = false, description = "Kontext: Projekt, Architektur, betroffene Module und Schichten, "
                    + "Technologien, Randbedingungen, bekannte Fallstricke. Je genauer, desto besser die Einschätzung.")
            String context) {
        if ((task == null || task.isBlank()) && (title == null || title.isBlank())) {
            throw new IllegalArgumentException("Keine Aufgabe angegeben – 'task' mit der Beschreibung der Aufgabe übergeben.");
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        put(attributes, "Art", kind);
        put(attributes, "Umfang", scope);
        TaskClassifier.Input in = new TaskClassifier.Input(title, task, attributes, null, null, context);
        TaskClassifier.Result r = classifier.classify(in);
        String heading = "Aufgabe" + (title == null || title.isBlank() ? "" : ": " + title.strip());
        return TaskClassifier.format(heading, r, classifier.settings());
    }

    private static void put(Map<String, String> m, String k, String v) {
        if (v != null && !v.isBlank()) {
            m.put(k, v.strip());
        }
    }
}
