package systems.grebe.devtools.mcp.modules.decide;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.config.Home;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.ConnectionTestResult;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.core.ModuleAction;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolBeans;
import systems.grebe.devtools.mcp.core.ToolModule;
import systems.grebe.devtools.mcp.core.ToolScope;
import systems.grebe.devtools.mcp.modules.decide.DecisionSchema.Field;

/**
 * Entscheidungen („System One“): typisierte Entscheidungen mit Wahrscheinlichkeiten, lokal in reinem Java gerechnet –
 * schnell mit Laya (Encoder, github.com/NandhaKishorM/laya), bei Unsicherheit mit einem kleinen LLM (Qwen, GGUF).
 * Lernt aus Rückmeldungen (Kalibrierung, Beispiele). Keine API, keine nativen Bibliotheken.
 */
@Component
public class DecideModule implements ToolModule {

    public static final String ID = "decide";

    static final String MODE = "mode";
    static final String THRESHOLD = "threshold";
    static final String LAYA_MODEL = "layaModel";
    static final String LAYA_URL = "layaUrl";
    static final String LLM_URL = "llmUrl";
    static final String MODELS_DIR = "modelsDir";
    static final String AUTO_DOWNLOAD = "autoDownload";
    static final String THREADS = "threads";
    static final String MAX_LLM_TOKENS = "maxLlmTokens";
    static final String ALLOW_DELETE = "allowDelete";

    private static final String STATE = "decide.log";

    private final DecideEngines engines;

    public DecideModule(DecideEngines engines) {
        this.engines = engines;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Entscheidungen (System One)";
    }

    @Override
    public String description() {
        return "Typisierte Entscheidungen lokal und ohne API: unstrukturierte Eingabe rein, je Feld ein Wert aus dem Schema "
                + "mit Wahrscheinlichkeiten raus (klassifizieren, routen, bewerten, prüfen). Gerechnet mit Laya (Encoder, "
                + "Apache-2.0) in reinem Java auf der CPU, optional zusätzlich mit einem LLM im GGUF-Format. Lernt aus "
                + "Rückmeldungen. Das Modell (644 MB) wird einmalig heruntergeladen.";
    }

    @Override
    public String instructions() {
        return """
                Wenn `decide_run` angeboten wird: für kleine, klar umrissene Entscheidungen über einen Text oder Zustand \
                (Kategorie, Priorität, ja/nein, passende Labels, Stufe auf einer Skala) – z.B. viele Tickets, Logzeilen \
                oder Datensätze sortieren (`decide_batch`), statt jede Eingabe selbst zu lesen. Felder mit festen Optionen \
                und kurzer Beschreibung je Option angeben; das Ergebnis ist immer ein Wert aus dem Schema mit \
                Wahrscheinlichkeit. Bei `uncertain: true` das Ergebnis selbst prüfen. Nicht für offene Fragen, Begründungen \
                oder Textproduktion. Ist die richtige Antwort bekannt (vom Nutzer bestätigt oder korrigiert), mit \
                `decide_feedback` melden – das kalibriert die Aufgabe. Wiederkehrende Entscheidungen als Aufgabe \
                speichern (`decide_task_save`) und über `task` aufrufen.""";
    }

    @Override
    public String briefInstructions() {
        return "decide_run/decide_batch für kleine typisierte Entscheidungen (Kategorie, Priorität, ja/nein) über Texte, "
                + "lokal mit Wahrscheinlichkeiten; richtige Antworten mit decide_feedback melden.";
    }

    @Override
    public int order() {
        return 166;
    }

    @Override
    public List<ConfigField> configSchema() {
        return List.of(
                ConfigField.of(MODE, "Modus", FieldType.ENUM).withDefault("laya").withOptions("laya", "hybrid", "llm")
                        .withHelp("laya = nur das schnelle Modell (empfohlen). hybrid = ist Laya unsicherer als die Schwelle, "
                                + "bewertet zusätzlich das LLM die Optionen; beide werden kombiniert, das Gewicht lernt jede "
                                + "Aufgabe aus Rückmeldungen. llm = nur das LLM. Mit dem Standard-LLM (Qwen2.5-0.5B) ist laya "
                                + "genauer – hybrid lohnt sich erst mit einem größeren GGUF (z.B. Qwen2.5-1.5B/3B-Instruct)."),
                ConfigField.of(THRESHOLD, "Schwelle (%)", FieldType.INT).withDefault("80")
                        .withHelp("Unter dieser Wahrscheinlichkeit gilt eine Antwort als unsicher: im Ergebnis steht "
                                + "uncertain=true, im Modus hybrid wird das LLM hinzugenommen."),
                ConfigField.of(LAYA_MODEL, "Laya-Modell", FieldType.ENUM).withDefault("multilingual")
                        .withOptions("multilingual", "english", "typed-decisions")
                        .withHelp("multilingual = mmBERT, 100+ Sprachen inkl. Deutsch (644 MB). english = ModernBERT-large, "
                                + "nur Englisch (843 MB). typed-decisions = english, feinjustiert auf vier Workflows."),
                ConfigField.of(LAYA_URL, "Laya-Quelle", FieldType.URL)
                        .withHelp("Leer = Hugging Face convaiinnovations/<modell> (gepinnte Revision, SHA-256-geprüft). "
                                + "Sonst Basis-URL eines Mirrors mit denselben Dateien."),
                ConfigField.of(LLM_URL, "LLM (GGUF-URL)", FieldType.URL)
                        .withHelp("Nur für die Modi hybrid und llm. Leer = Qwen2.5-0.5B-Instruct Q8_0 (676 MB, "
                                + "SHA-256-geprüft). Unterstützt: qwen2, qwen3, llama in F32/F16/BF16/Q8_0/Q4_0."),
                ConfigField.of(MODELS_DIR, "Modellordner", FieldType.DIRECTORY)
                        .withHelp("Leer = ~/.devtools-mcp/models."),
                ConfigField.of(AUTO_DOWNLOAD, "Modelle automatisch herunterladen", FieldType.BOOLEAN).withDefault("false")
                        .withHelp("Fehlende Modelle beim ersten Aufruf laden (dauert je nach Leitung Minuten). Sonst über "
                                + "die Aktion „Modelle herunterladen“."),
                ConfigField.of(THREADS, "Rechen-Threads", FieldType.INT).withDefault("0")
                        .withHelp("0 = alle Prozessorkerne."),
                ConfigField.of(MAX_LLM_TOKENS, "Kontext des LLM (Tokens)", FieldType.INT).withDefault("2048")
                        .withHelp("Längere Eingaben werden für das LLM gekürzt. Mehr Kontext = mehr Speicher und Rechenzeit."),
                ConfigField.of(ALLOW_DELETE, "Aufgaben löschen erlauben", FieldType.BOOLEAN).withDefault("false")
                        .withHelp("Schaltet decide_task_delete frei."));
    }

    static Path storeDir(ToolScope scope) {
        return Home.defaultHome().resolve("decide").resolve(scope.id().replaceAll("[^A-Za-z0-9_-]", "_"));
    }

    @Override
    public List<ToolCallback> createTools(ModuleConfig config) {
        return createTools(config, ToolScope.LOCAL);
    }

    @Override
    public List<ToolCallback> createTools(ModuleConfig config, ToolScope scope) {
        DecideSettings settings = DecideSettings.of(config);
        TaskStore store = new TaskStore(storeDir(scope));
        DecisionLog log = scope.state(STATE, DecisionLog::new);
        List<Object> beans = new ArrayList<>(List.of(new DecideTools(settings, engines, store, log)));
        if (config.getBoolean(ALLOW_DELETE)) {
            beans.add(new DecideDeleteTools(store));
        }
        return ToolBeans.callbacks(beans.toArray());
    }

    @Override
    public List<ModuleAction> actions() {
        return List.of(new ModuleAction() {
            @Override
            public String id() {
                return "download";
            }

            @Override
            public String label() {
                return "Modelle herunterladen";
            }

            @Override
            public String description() {
                return "Lädt die Modelle des gewählten Modus (Laya und/oder LLM) einmalig in den Modellordner.";
            }

            @Override
            public String describe(ModuleConfig config, String target) {
                DecideSettings s = DecideSettings.of(config);
                return "Laya (" + s.laya().id() + "): " + (ModelFiles.layaPresent(s) ? "vorhanden" : "fehlt")
                        + " · LLM: " + (ModelFiles.llmPresent(s) ? "vorhanden" : "fehlt");
            }

            @Override
            public ActionResult run(ModuleConfig config, String target, Set<String> flags, Progress progress) {
                DecideSettings s = DecideSettings.of(config);
                try {
                    if (s.usesLaya(s.mode())) {
                        ModelFiles.ensureLaya(s, progress);
                    }
                    if (s.usesLlm(s.mode())) {
                        ModelFiles.ensureLlm(s, progress);
                    }
                    engines.unload();
                    return ActionResult.ok("Modelle vorhanden unter " + s.modelsDir());
                } catch (IOException e) {
                    return ActionResult.failed(e.getMessage());
                }
            }
        });
    }

    @Override
    public ConnectionTestResult testConnection(ModuleConfig config) {
        List<String> errors = config.validate();
        if (!errors.isEmpty()) {
            return ConnectionTestResult.failed(String.join("\n", errors));
        }
        DecideSettings s = DecideSettings.of(config);
        List<String> missing = new ArrayList<>();
        if (s.usesLaya(s.mode()) && !ModelFiles.layaPresent(s)) {
            missing.add("Laya (" + s.laya().id() + ") unter " + ModelFiles.layaDir(s));
        }
        if (s.usesLlm(s.mode()) && !ModelFiles.llmPresent(s)) {
            missing.add("LLM unter " + ModelFiles.llmPath(s));
        }
        if (!missing.isEmpty()) {
            return ConnectionTestResult.failed("Es fehlen: " + String.join(", ", missing)
                    + ". Aktion „Modelle herunterladen“ ausführen.");
        }
        try {
            List<Field> fields = DecisionSchema.validate(List.of(new DecisionSchema.FieldSpec("kind", "choice",
                    "Um welche Art von Anliegen geht es?", List.of("fehler", "wunsch", "frage"),
                    java.util.Map.of("fehler", "etwas funktioniert nicht", "wunsch", "neue Funktion gewünscht",
                            "frage", "Frage zur Bedienung"))));
            Decider.Outcome o = new Decider(s, engines).decide("Die App stürzt beim Speichern ab.", fields, null, null,
                    s.mode(), false);
            return ConnectionTestResult.ok("Probe-Entscheidung in " + o.millis() + " ms (inkl. Laden): "
                    + o.decisions().get("kind") + "\n" + engines.describe() + "\nRechenkerne: "
                    + systems.grebe.devtools.mcp.modules.decide.engine.Kernels.simd());
        } catch (RuntimeException e) {
            return ConnectionTestResult.failed(e.getMessage());
        }
    }
}
