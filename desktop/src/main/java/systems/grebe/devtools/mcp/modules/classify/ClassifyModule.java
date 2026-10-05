package systems.grebe.devtools.mcp.modules.classify;

import java.util.List;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.ConnectionTestResult;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolModule;

/**
 * Modellwahl: Pre-Classifier, der die Komplexität einer Aufgabe mit Claude Opus 5.5 einschätzt und das Modell für die
 * Umsetzung empfiehlt. Die Einstellungen (API-Key, Modelle je Stufe, Regeln) nutzt auch {@code ticket_classify}.
 */
@Component
public class ClassifyModule implements ToolModule {

    public static final String ID = "classify";

    static final String API_KEY = "apiKey";
    static final String BASE_URL = "baseUrl";
    static final String EFFORT = "effort";
    static final String MODEL_SIMPLE = "modelSimple";
    static final String MODEL_NORMAL = "modelNormal";
    static final String MODEL_COMPLEX = "modelComplex";
    static final String RULES = "rules";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Modellwahl";
    }

    @Override
    public String description() {
        return "Pre-Classifier: schätzt die Komplexität einer Aufgabe mit Claude Opus 5.5 ein und empfiehlt das Modell für die "
                + "Umsetzung (einfach → Haiku, normal → Sonnet, komplex → Opus). Die Einstellungen gelten auch für "
                + "ticket_classify im Modul Tickets.";
    }

    @Override
    public String instructions() {
        return """
                Wenn `classify_task` angeboten wird: vor einer größeren Aufgabe (Feature, Bugfix, Refactoring, Analyse, \
                Konzept, längerer Text) deren Komplexität einschätzen lassen – läuft immer auf Claude Opus 5.5 – und das \
                empfohlene Modell für die Umsetzung verwenden, z.B. als Modell des Subagenten. Die Aufgabe vollständig \
                beschreiben und Kontext (Projekt, Architektur, betroffene Module, Randbedingungen) in `context` mitgeben. \
                Für Tickets aus einem Ticket-System `ticket_classify` verwenden (lädt das Ticket selbst).""";
    }

    @Override
    public int order() {
        return 165;
    }

    @Override
    public List<ConfigField> configSchema() {
        return List.of(
                ConfigField.of(API_KEY, "Claude API-Key", FieldType.SECRET)
                        .withHelp("console.anthropic.com → API Keys. Leer = ANTHROPIC_API_KEY bzw. Anmeldung per "
                                + "`ant auth login`. Wird verschlüsselt gespeichert. Die Aufgaben gehen an die Claude API "
                                + "(kostenpflichtig)."),
                ConfigField.of(BASE_URL, "Claude API-URL", FieldType.URL)
                        .withHelp("Leer = https://api.anthropic.com. Nur für ein Gateway/Proxy der Firma."),
                ConfigField.of(EFFORT, "Gründlichkeit der Einschätzung", FieldType.ENUM).withDefault("high")
                        .withOptions(TaskClassifier.EFFORTS.toArray(String[]::new))
                        .withHelp("Effort von Claude Opus 5.5: höher = gründlicher, langsamer und teurer. Die Einschätzung "
                                + "selbst läuft immer auf " + TaskClassifier.MODEL + "."),
                ConfigField.of(MODEL_SIMPLE, "Modell für einfache Aufgaben", FieldType.STRING)
                        .withDefault(TaskClassifier.DEFAULT_SIMPLE),
                ConfigField.of(MODEL_NORMAL, "Modell für normale Aufgaben", FieldType.STRING)
                        .withDefault(TaskClassifier.DEFAULT_NORMAL)
                        .withHelp("z.B. claude-sonnet-4-5 oder claude-sonnet-5-5"),
                ConfigField.of(MODEL_COMPLEX, "Modell für komplexe Aufgaben", FieldType.STRING)
                        .withDefault(TaskClassifier.DEFAULT_COMPLEX),
                ConfigField.of(RULES, "Regeln für die Einschätzung", FieldType.STRING_LIST)
                        .withHelp("Eine Regel je Zeile, gehen den allgemeinen Kriterien vor, z.B. „Änderungen am Lohnmodul "
                                + "sind immer komplex“ oder „Reine Übersetzungen sind einfach“."));
    }

    /** Einstellungen des Classifiers – auch für das Modul Tickets. */
    public static TaskClassifier.Settings settings(ModuleConfig config) {
        return new TaskClassifier.Settings(config.get(API_KEY).orElse(null), config.get(BASE_URL).orElse(null),
                config.getString(EFFORT, "high"),
                TaskClassifier.Settings.models(config.getString(MODEL_SIMPLE, ""), config.getString(MODEL_NORMAL, ""),
                        config.getString(MODEL_COMPLEX, "")),
                config.getList(RULES));
    }

    @Override
    public List<ToolCallback> createTools(ModuleConfig config) {
        return List.of(ToolCallbacks.from(new ClassifyTools(new TaskClassifier(settings(config)))));
    }

    @Override
    public ConnectionTestResult testConnection(ModuleConfig config) {
        List<String> errors = config.validate();
        if (!errors.isEmpty()) {
            return ConnectionTestResult.failed(String.join("\n", errors));
        }
        try {
            String name = new TaskClassifier(settings(config)).probe();
            return ConnectionTestResult.ok("Claude API erreichbar, " + name + " (" + TaskClassifier.MODEL + ") verfügbar.");
        } catch (RuntimeException e) {
            return ConnectionTestResult.failed(e.getMessage());
        }
    }
}
