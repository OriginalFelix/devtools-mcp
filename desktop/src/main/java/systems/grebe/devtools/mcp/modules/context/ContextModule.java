package systems.grebe.devtools.mcp.modules.context;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.ConnectionTestResult;
import systems.grebe.devtools.mcp.core.ContextSettings;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ServerInstructions;
import systems.grebe.devtools.mcp.core.ToolBeans;
import systems.grebe.devtools.mcp.core.ToolInvocationLog;
import systems.grebe.devtools.mcp.core.ToolModule;
import systems.grebe.devtools.mcp.core.ToolRegistry;

/**
 * „Kontext sparen“: senkt die Tokens, die DevTools im Kontext des LLM belegt – zentral für alle Module.
 * <ul>
 *   <li>Ergebnisse aufräumen, kürzen und vollständig unter einem Handle ablegen ({@link ResultBudget},
 *   {@code context_slice}, {@code context_digest}).</li>
 *   <li>Gleiche Ergebnisse lesender Tools nicht doppelt senden.</li>
 *   <li>Kompakte Instructions und Shell-Hinweise nur einmal je Modul ({@link ServerInstructions},
 *   {@code context_guide}).</li>
 *   <li>Optional nur wenige Tools direkt anbieten, den Rest über {@code context_find}/{@code context_call}.</li>
 *   <li>Messen, welche Tools wie viel kosten ({@code context_stats}, Reiter „Aufrufe“ in der App).</li>
 * </ul>
 * Die Einstellungen wirken über {@link ContextSettings} auch auf Registry und Instructions.
 */
@Component
public class ContextModule implements ToolModule {

    static final String API_KEY = "apiKey";
    static final String DIGEST_MODEL = "digestModel";

    private final ResultStore store;
    private final ToolInvocationLog log;
    private final ObjectProvider<ToolRegistry> registry;
    private final ObjectProvider<ServerInstructions> instructions;

    public ContextModule(ResultStore store, ToolInvocationLog log, ObjectProvider<ToolRegistry> registry,
                         ObjectProvider<ServerInstructions> instructions) {
        this.store = store;
        this.log = log;
        this.registry = registry;
        this.instructions = instructions;
    }

    /** Instructions folgen ab jetzt den wirksamen Einstellungen (Backend, Team-Server) statt der lokalen Ablage. */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        ServerInstructions si = instructions.getIfAvailable();
        ToolRegistry r = registry.getIfAvailable();
        if (si != null && r != null) {
            si.useContext(r::contextSettings);
        }
    }

    @Override
    public String id() {
        return ContextSettings.ID;
    }

    @Override
    public String displayName() {
        return "Kontext sparen";
    }

    @Override
    public String description() {
        return "Senkt den Token-Verbrauch: lange Ergebnisse kürzen und unter einem Handle ablegen, Logs aufräumen, "
                + "doppelte Ergebnisse vermeiden, kompakte Instructions, optional nur wenige Tools direkt anbieten. "
                + "Misst, welche Tools wie viele Tokens kosten.";
    }

    @Override
    public String instructions() {
        return """
                Gekürzte Ergebnisse enden nicht, sie haben eine Lücke mit Handle (`r12`): `context_slice(handle, \
                grep=…)` sucht darin (Regex, mit Zeilennummern und Umgebung), `from_line`/`to_line` liest einen \
                Bereich. Erst gezielt suchen, nicht alles seitenweise lesen. `context_digest(handle, focus=…)` lässt \
                ein kleines Modell zusammenfassen – für lange Logs, wenn nur der Überblick zählt. Das Tool nicht mit \
                höherem Limit erneut aufrufen, nur um mehr zu sehen.
                „Ergebnis unverändert seit …“: dasselbe Ergebnis steht schon weiter oben im Verlauf.
                `context_guide(module)` liefert die ausführlichen Hinweise eines Moduls, `context_find(query)` sucht \
                aktive Tools, `context_stats` zeigt, welche Tools die meisten Tokens kosten.""";
    }

    @Override
    public String briefInstructions() {
        return "Gekürzte Ergebnisse mit Handle: context_slice(handle, grep=…) liest gezielt nach, context_digest fasst "
                + "zusammen; context_find sucht Tools.";
    }

    @Override
    public boolean enabledByDefault() {
        return true;
    }

    @Override
    public int order() {
        return 2;
    }

    @Override
    public List<ConfigField> configSchema() {
        return List.of(
                ConfigField.of(ContextSettings.MAX_CHARS, "Höchstlänge eines Ergebnisses (Zeichen)", FieldType.INT)
                        .withDefault(String.valueOf(ContextSettings.DEFAULT_MAX_CHARS))
                        .withHelp("Längere Ergebnisse gehen gekürzt (Anfang und Ende) an das LLM und liegen "
                                + "vollständig unter einem Handle. 12.000 Zeichen ≈ 3.000 Tokens. 0 = nie kürzen."),
                ConfigField.of(ContextSettings.COMPACT_OUTPUT, "Logs und Ausgaben aufräumen", FieldType.BOOLEAN)
                        .withDefault("true")
                        .withHelp("Framework-Frames in Stacktraces, gleiche Zeilen in Folge und Leerzeilen-Serien "
                                + "zusammenfassen. Steuerzeichen und Fortschrittsanzeigen fallen immer weg."),
                ConfigField.of(ContextSettings.VERBATIM_TOOLS, "Wortgetreu (nicht aufräumen)", FieldType.STRING_LIST)
                        .withDefault(ContextSettings.DEFAULT_VERBATIM)
                        .withHelp("Tools, deren Ausgabe Zeichen für Zeichen stimmen muss (Dateien, Diffs, "
                                + "Abfragen). Ein Muster je Zeile, * = beliebig."),
                ConfigField.of(ContextSettings.EXEMPT_TOOLS, "Nie kürzen", FieldType.STRING_LIST)
                        .withDefault(ContextSettings.DEFAULT_EXEMPT)
                        .withHelp("Tools, deren Ergebnis immer vollständig gebraucht wird (z.B. Skill-Anleitungen). "
                                + "Ein Muster je Zeile, * = beliebig."),
                ConfigField.of(ContextSettings.DEDUPE, "Gleiche Ergebnisse nicht erneut senden", FieldType.BOOLEAN)
                        .withDefault("true")
                        .withHelp("Liefert ein lesendes Tool in derselben Session mit denselben Argumenten dasselbe "
                                + "wie zuletzt, bekommt das LLM nur einen Verweis (mit Handle)."),
                ConfigField.of(ContextSettings.DEDUPE_MINUTES, "… innerhalb von (Minuten)", FieldType.INT)
                        .withDefault("10"),
                ConfigField.of(ContextSettings.COMPACT_INSTRUCTIONS, "Kompakte Instructions", FieldType.BOOLEAN)
                        .withDefault("true")
                        .withHelp("Statt aller Modul-Hinweise (≈ 8.000 Tokens in jeder Sitzung) eine Zeile je Modul; "
                                + "Details holt das LLM mit context_guide. Gilt für neue Sitzungen."),
                ConfigField.of(ContextSettings.SHELL_HINTS_ONCE, "Shell-Hinweise nur einmal je Modul",
                                FieldType.BOOLEAN)
                        .withDefault("false")
                        .withHelp("„git_* statt git in der Shell“ usw. steht dann einmal in den Instructions statt in "
                                + "jeder Tool-Beschreibung (spart ≈ 6.000 Tokens Tool-Definitionen). Nur einschalten, "
                                + "wenn alle Clients die Instructions übernehmen – Hermes z.B. nicht."),
                ConfigField.of(ContextSettings.LAZY_TOOLS, "Nur Such- und Aufruf-Tools anbieten", FieldType.BOOLEAN)
                        .withDefault("false")
                        .withHelp("Der Client sieht nur die context_*-Tools (plus die Liste unten); alle übrigen "
                                + "findet das LLM mit context_find und ruft sie mit context_call auf. Spart die "
                                + "Tool-Definitionen in Clients, die alle Tools sofort laden. Claude Code lädt "
                                + "MCP-Tools ohnehin erst bei Bedarf."),
                ConfigField.of(ContextSettings.LAZY_KEEP, "… trotzdem direkt anbieten", FieldType.STRING_LIST)
                        .withDefault("")
                        .withHelp("Häufig gebrauchte Tools, z.B. git_status oder build_test. Muster mit *."),
                ConfigField.of(DIGEST_MODEL, "Modell zum Zusammenfassen", FieldType.STRING)
                        .withDefault(Digester.DEFAULT_MODEL)
                        .withHelp("Für context_digest über die Claude API. Bietet der Client Sampling an, wählt er "
                                + "das Modell (Wunsch: dieses)."),
                ConfigField.of(API_KEY, "Claude API-Key", FieldType.SECRET)
                        .withHelp("Nur für context_digest ohne Sampling. Leer = Key aus „Modellwahl“ bzw. "
                                + "ANTHROPIC_API_KEY."));
    }

    @Override
    public List<ToolCallback> createTools(ModuleConfig config) {
        ContextSettings ctx = ContextSettings.of(true, config);
        Digester.Settings digest = new Digester.Settings(config.getString(DIGEST_MODEL, Digester.DEFAULT_MODEL),
                config.get(API_KEY).orElse(null), null);
        ContextTools tools = new ContextTools(store, log, registry, instructions, ctx, digest);
        List<ToolCallback> out = new ArrayList<>(ToolBeans.callbacks(tools));
        if (ctx.lazyTools()) {
            out.addAll(ToolBeans.callbacks(new ContextCallTool(registry)));
        }
        return out;
    }

    @Override
    public ConnectionTestResult testConnection(ModuleConfig config) {
        List<String> errors = config.validate();
        if (!errors.isEmpty()) {
            return ConnectionTestResult.failed(String.join("\n", errors));
        }
        ContextSettings ctx = ContextSettings.of(true, config);
        List<String> lines = new ArrayList<>();
        lines.add(ctx.maxChars() == 0 ? "Ergebnisse werden nicht gekürzt."
                : "Ergebnisse über " + ctx.maxChars() + " Zeichen werden gekürzt (≈ " + ctx.maxChars() / 4
                        + " Tokens).");
        ServerInstructions si = instructions.getIfAvailable();
        if (si != null) {
            lines.add("Instructions jetzt: " + si.build().length() + " Zeichen (≈ " + si.build().length() / 4
                    + " Tokens) – Änderungen gelten für neue Sitzungen.");
        }
        return ConnectionTestResult.ok(String.join("\n", lines));
    }
}
