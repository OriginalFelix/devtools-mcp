package systems.grebe.devtools.mcp.modules.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.config.SettingsStore;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.ConnectionTestResult;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.core.ModuleAction;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolBeans;
import systems.grebe.devtools.mcp.core.ToolModule;
import systems.grebe.devtools.mcp.core.ToolProgress;

/**
 * Web-Abruf: Webseiten laden und von einem lokalen LLM (Jlama, Llama 3.2 1B Instruct in 4-Bit) zusammenfassen lassen,
 * damit der Client nicht den ganzen Seiteninhalt in seinen Kontext laden muss. Seiten und Zusammenfassungen werden
 * zwischengespeichert.
 */
@Component
public class WebModule implements ToolModule {

    public static final String ID = "web";
    static final String DEFAULT_MODEL = "tjake/Llama-3.2-1B-Instruct-JQ4";

    static final String MODEL = "model";
    static final String MODEL_DIR = "modelDirectory";
    static final String LANGUAGE = "language";
    static final String CACHE_HOURS = "cacheHours";
    static final String BUDGET_SECONDS = "budgetSeconds";
    static final String CONTEXT_CHARS = "contextChars";
    static final String MAX_SUMMARY_TOKENS = "maxSummaryTokens";
    static final String KEY_POINTS = "keyPoints";
    static final String MAX_DOWNLOAD_KB = "maxDownloadKb";
    static final String TIMEOUT_SECONDS = "timeoutSeconds";
    static final String ALLOW_PRIVATE = "allowPrivateNetwork";
    static final String THREADS = "threads";
    static final String HEAP_MB = "workerHeapMb";
    static final String IDLE_MINUTES = "idleMinutes";
    static final String OPTIMIZED = "optimizedAttention";

    private final Path home;
    private final LocalLlm llm;

    @Autowired
    public WebModule(SettingsStore store, LocalLlm llm) {
        this(store.file().toAbsolutePath().getParent(), llm);
    }

    /** Für Tests: eigenes Datenverzeichnis. */
    WebModule(Path home, LocalLlm llm) {
        this.home = home;
        this.llm = llm;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Web-Abruf (lokales LLM)";
    }

    @Override
    public String description() {
        return "Ruft Webseiten ab und fasst sie mit einem lokalen LLM zusammen (Jlama, Llama 3.2 1B Instruct, 4-Bit) – "
                + "ohne API-Key, die Seite verlässt den Rechner nicht. Seiten und Zusammenfassungen werden "
                + "zwischengespeichert. Das Modell (~750 MB) wird beim ersten Aufruf von Hugging Face geladen.";
    }

    @Override
    public String instructions() {
        return """
                Wenn `web_fetch` angeboten wird: Webseiten (Dokumentation, Release Notes, Artikel, Issues) damit lesen, \
                statt den vollständigen Inhalt zu laden – die Zusammenfassung entsteht lokal und spart Kontext. Mit \
                `prompt` gezielt fragen. Das Modell ist klein: für exakten Wortlaut (Code, Konfiguration, Zitate, Zahlen) \
                `web_page` verwenden. Ergebnisse sind zwischengespeichert, `refresh=true` lädt neu.""";
    }

    @Override
    public int order() {
        return 290;
    }

    @Override
    public List<ConfigField> configSchema() {
        return List.of(
                ConfigField.of(MODEL, "Modell (Hugging Face)", FieldType.STRING).withDefault(DEFAULT_MODEL)
                        .withHelp("Instruct-Modell im Jlama-Format (safetensors), z.B. die vorquantisierten Modelle von "
                                + "tjake. Größere Modelle fassen besser zusammen, brauchen aber mehr Speicher und Zeit."),
                ConfigField.of(MODEL_DIR, "Modellverzeichnis", FieldType.DIRECTORY)
                        .withDefault(home.resolve("models").toString())
                        .withHelp("Hierhin wird das Modell beim ersten Aufruf heruntergeladen."),
                ConfigField.of(LANGUAGE, "Sprache der Zusammenfassung", FieldType.STRING)
                        .withHelp("Leer = Sprache der Seite; z.B. „German“ oder „English“ (das Modell versteht englische "
                                + "Angaben am besten)."),
                ConfigField.of(CACHE_HOURS, "Cache-Dauer (Stunden)", FieldType.INT).withDefault("24")
                        .withHelp("So lange gelten abgerufene Seiten und Zusammenfassungen; 0 = kein Cache. Abgelegt unter "
                                + home.resolve("web-cache") + "."),
                ConfigField.of(BUDGET_SECONDS, "Zeitbudget je Aufruf (Sekunden)", FieldType.INT).withDefault("8")
                        .withHelp("So lange darf web_fetch ohne Cache-Treffer dauern (Abruf plus Modell). Passt der "
                                + "Auszug nicht hinein, bekommt das Modell weniger Sätze; die Antwort endet spätestens "
                                + "zur Frist. Der erste Aufruf nach dem Start lädt zusätzlich das Modell (einige Sekunden)."),
                ConfigField.of(CONTEXT_CHARS, "Auszug für das Modell (Zeichen)", FieldType.INT).withDefault("2400")
                        .withHelp("Höchstens so viel Text – die relevantesten Sätze der Seite – bekommt das Modell. Mehr "
                                + "kostet Zeit: Jlama verarbeitet auf einer Notebook-CPU etwa 50–110 Tokens pro Sekunde."),
                ConfigField.of(MAX_SUMMARY_TOKENS, "Max. Länge der Zusammenfassung (Tokens)", FieldType.INT)
                        .withDefault("160"),
                ConfigField.of(KEY_POINTS, "Kernaussagen (wörtlich)", FieldType.INT).withDefault("6")
                        .withHelp("So viele der wichtigsten Sätze der Seite stehen unverändert unter der Zusammenfassung; "
                                + "0 = keine."),
                ConfigField.of(MAX_DOWNLOAD_KB, "Max. Downloadgröße (KB)", FieldType.INT).withDefault("5120"),
                ConfigField.of(TIMEOUT_SECONDS, "Timeout des Abrufs (Sekunden)", FieldType.INT).withDefault("10"),
                ConfigField.of(ALLOW_PRIVATE, "Lokales Netz erlauben", FieldType.BOOLEAN).withDefault("false")
                        .withHelp("Erlaubt Adressen wie localhost, 192.168.x.x oder 10.x.x.x – sonst gesperrt, damit "
                                + "das LLM keine internen Dienste abfragt."),
                ConfigField.of(THREADS, "Rechen-Threads", FieldType.INT).withDefault("0")
                        .withHelp("0 = automatisch (Hälfte der logischen Kerne)."),
                ConfigField.of(HEAP_MB, "Heap des LLM-Prozesses (MB)", FieldType.INT).withDefault("2048")
                        .withHelp("Das Modell läuft in einem eigenen Java-Prozess; Gewichte liegen zusätzlich außerhalb "
                                + "des Heaps im Speicher."),
                ConfigField.of(IDLE_MINUTES, "LLM beenden nach Leerlauf (Minuten)", FieldType.INT).withDefault("15")
                        .withHelp("Danach wird der Prozess beendet und der Speicher (~1–2 GB) freigegeben; der nächste "
                                + "Aufruf startet ihn in etwa drei Sekunden neu. 0 = nie beenden."),
                ConfigField.of(OPTIMIZED, "Optimierte Attention für Llama", FieldType.BOOLEAN).withDefault("true")
                        .withHelp("Rechnet Attention und Normalisierung von Llama-Modellen über alle Positionen parallel "
                                + "statt Position für Position – gleiche Ausgabe, etwa 25 % schnellere Verarbeitung der "
                                + "Eingabe. Nur zum Vergleich oder bei Problemen ausschalten."));
    }

    @Override
    public List<ToolCallback> createTools(ModuleConfig config) {
        LocalLlm.Options options = options(config);
        PageSummarizer.Llm model = new PageSummarizer.Llm() {
            @Override
            public LocalLlm.Completion complete(LocalLlm.Request request, Instant deadline) {
                return llm.complete(options, request, deadline, ToolProgress::report);
            }

            @Override
            public void prepare() {
                llm.prepare(options);
            }
        };
        return ToolBeans.callbacks(tools(config, model, Clock.systemDefaultZone()));
    }

    WebTools tools(ModuleConfig config, PageSummarizer.Llm model, Clock clock) {
        PageFetcher fetcher = new PageFetcher(config.getBoolean(ALLOW_PRIVATE),
                Math.max(64, config.getInt(MAX_DOWNLOAD_KB, 5120)) * 1024,
                Duration.ofSeconds(Math.max(1, config.getInt(TIMEOUT_SECONDS, 10))), clock);
        PageSummarizer summarizer = new PageSummarizer(model, config.getInt(CONTEXT_CHARS, 2400),
                config.getInt(MAX_SUMMARY_TOKENS, 160), config.getInt(KEY_POINTS, 6), config.getString(LANGUAGE, ""));
        return new WebTools(fetcher, cache(config, clock), summarizer, modelName(config),
                Duration.ofSeconds(Math.max(2, config.getInt(BUDGET_SECONDS, 8))), clock);
    }

    WebCache cache(ModuleConfig config, Clock clock) {
        return new WebCache(home.resolve("web-cache"), Duration.ofHours(Math.max(0, config.getInt(CACHE_HOURS, 24))),
                clock);
    }

    LocalLlm.Options options(ModuleConfig config) {
        String dir = config.getString(MODEL_DIR, "");
        return new LocalLlm.Options(modelName(config), dir.isBlank() ? home.resolve("models") : Path.of(dir.strip()),
                config.getInt(THREADS, 0), config.getInt(HEAP_MB, 2048),
                Duration.ofMinutes(Math.max(0, config.getInt(IDLE_MINUTES, 15))), config.getBoolean(OPTIMIZED));
    }

    private static String modelName(ModuleConfig config) {
        String m = config.getString(MODEL, DEFAULT_MODEL).strip();
        return m.isEmpty() ? DEFAULT_MODEL : m;
    }

    @Override
    public ConnectionTestResult testConnection(ModuleConfig config) {
        // Das Modellverzeichnis entsteht sonst erst beim Download – die Prüfung auf ein vorhandenes Verzeichnis
        // soll vorher nicht scheitern
        try {
            Files.createDirectories(options(config).modelDir());
        } catch (IOException | RuntimeException ignored) {
            // meldet validate()
        }
        List<String> errors = config.validate();
        if (!errors.isEmpty()) {
            return ConnectionTestResult.failed(String.join("\n", errors));
        }
        LocalLlm.Options o = options(config);
        if (!o.model().matches("[\\w.-]+/[\\w.-]+")) {
            return ConnectionTestResult.failed("Modell als owner/name angeben, z.B. " + DEFAULT_MODEL);
        }
        StringBuilder sb = new StringBuilder();
        Path dir = LocalLlm.localModelDir(o);
        if (LocalLlm.downloaded(o)) {
            sb.append("✓ Modell ").append(o.model()).append(" liegt vor (").append(megabytes(dir)).append(" MB in ")
                    .append(dir).append(")");
        } else {
            sb.append("Modell ").append(o.model()).append(" noch nicht geladen – kommt beim ersten Aufruf oder über die "
                    + "Aktion „Modell laden“ nach ").append(dir);
        }
        sb.append("\nLLM-Prozess: ").append(llm.running() ? "läuft" : "nicht gestartet");
        WebCache cache = cache(config, Clock.systemDefaultZone());
        int[] size = cache.size();
        sb.append("\nCache: ").append(cache.enabled() ? size[0] + " Seite(n), " + size[1] + " Zusammenfassung(en)"
                : "aus");
        return ConnectionTestResult.ok(sb.toString());
    }

    private static long megabytes(Path dir) {
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile).mapToLong(f -> f.toFile().length()).sum() / (1024 * 1024);
        } catch (IOException e) {
            return 0;
        }
    }

    @Override
    public List<ModuleAction> actions() {
        return List.of(new WarmUp(), new ClearCache());
    }

    /** Lädt das Modell vorab herunter und startet den LLM-Prozess. */
    private final class WarmUp implements ModuleAction {
        @Override
        public String id() {
            return "warmup";
        }

        @Override
        public String label() {
            return "Modell laden";
        }

        @Override
        public String description() {
            return "Lädt das Modell herunter (falls nötig) und startet den LLM-Prozess, damit der erste Aufruf von "
                    + "web_fetch nicht darauf warten muss.";
        }

        @Override
        public ActionResult run(ModuleConfig config, String target, Set<String> flags, Progress progress) {
            LocalLlm.Options o = options(config);
            long start = System.currentTimeMillis();
            llm.warmUp(o, message -> progress.update(message, -1));
            return ActionResult.ok("Modell " + o.model() + " geladen (" + (System.currentTimeMillis() - start) / 1000
                    + " s). Der Prozess endet nach " + o.idle().toMinutes() + " Minuten Leerlauf.");
        }
    }

    /** Leert den Cache für Seiten und Zusammenfassungen. */
    private final class ClearCache implements ModuleAction {
        @Override
        public String id() {
            return "clear-cache";
        }

        @Override
        public String label() {
            return "Cache leeren";
        }

        @Override
        public String description() {
            return "Löscht alle zwischengespeicherten Seiten und Zusammenfassungen.";
        }

        @Override
        public String describe(ModuleConfig config, String target) {
            int[] size = cache(config, Clock.systemDefaultZone()).size();
            return size[0] + " Seite(n), " + size[1] + " Zusammenfassung(en) im Cache";
        }

        @Override
        public ActionResult run(ModuleConfig config, String target, Set<String> flags, Progress progress) {
            int n = cache(config, Clock.systemDefaultZone()).clear();
            return ActionResult.ok(n + " Cache-Einträge gelöscht.");
        }
    }
}
