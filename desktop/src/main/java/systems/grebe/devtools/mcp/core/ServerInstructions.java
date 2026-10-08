package systems.grebe.devtools.mcp.core;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.customizer.McpSyncServerCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import systems.grebe.devtools.mcp.config.SettingsStore;
import systems.grebe.devtools.mcp.modules.scripts.ScriptManager;
import systems.grebe.devtools.mcp.plugin.PluginManager;

/**
 * Baut die MCP-{@code instructions}, die der Server beim {@code initialize} an jeden Client schickt: ein allgemeiner
 * Vorrang-Hinweis („diese Tools statt Shell-Befehlen“) plus die {@link ToolModule#instructions()} aller Module.
 *
 * <p>Clients wie Claude Code übernehmen die Instructions in den System-Prompt. Nur so erfährt das LLM <em>vor</em>
 * dem ersten Tool-Aufruf, dass es z.B. {@code git_status} statt {@code git status} im Terminal verwenden soll –
 * Tool-Beschreibungen allein reichen dafür nicht, weil sie mit den eingebauten Werkzeugen des Clients konkurrieren.
 *
 * <p>Der Text wird bei jedem {@code initialize} neu gebaut ({@link LiveInstructionsTransport}) – neue Client-Sessions
 * sehen Plugin-Änderungen sofort, bestehende behalten ihren Stand (MCP kennt keine Änderungsbenachrichtigung für
 * Instructions); dasselbe gilt für Module aus Groovy-Skripten. Aufgenommen werden alle Module, auch deaktivierte;
 * die Texte sind bedingt formuliert („wenn angeboten“) und die tatsächlich verfügbaren Tools liefert weiterhin
 * {@code tools/list}.
 */
@Configuration(proxyBeanMethods = false)
public class ServerInstructions {

    private static final Logger LOG = LoggerFactory.getLogger(ServerInstructions.class);

    static final String PREAMBLE = """
            # DevTools MCP – lokale Entwickler-Werkzeuge

            Dieser Server ist die bevorzugte Schnittstelle für die unten genannten Aufgaben. Tool-Namen sind nach \
            Modul präfixiert (git_*, build_*, container_*, jvm_*, …).

            Grundregeln:
            - Wenn für eine Aufgabe ein passendes Tool dieses Servers angeboten wird (siehe tools/list), verwende es \
            statt eines Shell-/Terminal-Befehls oder eines anderen Werkzeugs – auch wenn der Nutzer den Befehl \
            ausdrücklich nennt (z.B. „mach git status“).
            - Auf die Shell nur ausweichen, wenn das Tool fehlt (abgeschaltet oder nicht freigegeben), die Aufgabe \
            nicht abdeckt oder mit einer Fehlermeldung ablehnt, die sich nicht beheben lässt. Sag dem Nutzer dann \
            kurz, warum du die Shell verwendest.
            - Meldet ein Tool „nicht freigegeben“, liegt das Ziel außerhalb der Freigaben in der DevTools-App. \
            Nicht still per Shell umgehen, sondern den Nutzer darauf hinweisen – oder, wenn angeboten, mit \
            `permissions_check` prüfen, was fehlt, und es mit `permissions_request` beim Nutzer anfragen.
            - Tools nicht raten: welche angeboten werden, hängt von den Schaltern in der App ab und kann sich zur \
            Laufzeit ändern (notifications/tools/list_changed).
            """;

    /** Kompakte Fassung: Hinweise zum Sparen von Kontext, gilt nur mit dem Modul „Kontext sparen“. */
    static final String CONTEXT_RULES = """
            Kontext sparen: Lange Ergebnisse kürzt der Server und legt sie vollständig unter einem Handle ab \
            (`context_slice(handle, grep=…, from_line=…)` liest gezielt nach, `context_digest(handle)` fasst \
            zusammen). „Unverändert seit …“ heißt: dasselbe Ergebnis steht schon weiter oben – nicht erneut abrufen.""";

    static final String LAZY_RULES = """
            Angeboten werden nur wenige Tools direkt. Alle übrigen aktiven Tools: `context_find(query)` sucht \
            (Name, Zweck, Parameter), `context_call(name, arguments)` ruft auf.""";

    /** Instructions bis zu dieser Länge übernehmen kompakte Instructions vollständig. */
    static final int SHORT_INSTRUCTIONS = 400;

    private final List<ToolModule> modules;
    private final Supplier<List<ToolModule>> pluginModules;
    private final Supplier<ContextSettings> context;
    private volatile Supplier<ContextSettings> live;
    private final String base;

    public ServerInstructions(List<ToolModule> modules, String base) {
        this(modules, List::of, () -> ContextSettings.OFF, base);
    }

    /**
     * @param plugins  Module der jeweils aktiven Plugins (siehe {@code PluginManager})
     * @param scripts  Module aus den geladenen Groovy-Skripten (siehe {@code ScriptManager})
     * @param settings Einstellungen von „Kontext sparen“ – gelesen aus der lokalen Ablage, weil die Registry beim
     *                 Serveraufbau noch nicht existiert (sie braucht den Server)
     */
    @Autowired
    public ServerInstructions(List<ToolModule> modules, ObjectProvider<PluginManager> plugins,
                              ObjectProvider<ScriptManager> scripts, ObjectProvider<SettingsStore> settings,
                              @Value("${spring.ai.mcp.server.instructions:}") String base) {
        this(modules, () -> {
            List<ToolModule> runtime = new ArrayList<>();
            PluginManager pm = plugins.getIfAvailable();
            // lädt nichts nach: beim Serveraufbau sind noch keine Plugins aktiv, danach die jeweils aktiven
            if (pm != null) {
                runtime.addAll(pm.activeModules());
            }
            ScriptManager sm = scripts.getIfAvailable();
            if (sm != null) {
                runtime.addAll(sm.modules());
            }
            return runtime;
        }, () -> {
            SettingsStore store = settings.getIfAvailable();
            return store == null ? ContextSettings.OFF
                    : ContextSettings.of(store.module(ContextSettings.ID).orElse(null));
        }, base);
    }

    ServerInstructions(List<ToolModule> modules, Supplier<List<ToolModule>> pluginModules,
                       Supplier<ContextSettings> context, String base) {
        this.modules = modules;
        this.pluginModules = pluginModules;
        this.context = context;
        this.base = base;
    }

    ServerInstructions(List<ToolModule> modules, Supplier<List<ToolModule>> pluginModules, String base) {
        this(modules, pluginModules, () -> ContextSettings.OFF, base);
    }

    /**
     * Überschreibt die statischen {@code spring.ai.mcp.server.instructions} aus {@code application.properties}. Der
     * Customizer läuft nach dem Setzen der Property (siehe {@code McpServerAutoConfiguration}), der Property-Text wird
     * als erster Absatz übernommen – so lassen sich Hinweise auch ohne Codeänderung ergänzen.
     *
     * <p>{@code @Primary}, weil die Servlet-Autokonfiguration einen eigenen Customizer mitbringt und der Server nur
     * einen übernimmt – dessen {@code immediateExecution(true)} (Tool-Aufrufe im Request-Thread) steht deshalb hier.
     */
    @Bean
    @Primary
    McpSyncServerCustomizer instructionsCustomizer() {
        return builder -> builder.immediateExecution(true).instructions(build());
    }

    /**
     * Baut die Instructions bei jedem {@code initialize} neu (siehe {@link LiveInstructionsTransport}): Plugins,
     * die zur Laufzeit installiert, aktiviert oder entfernt werden, sind so für jede neue Client-Session sofort
     * berücksichtigt. {@code @Primary}, damit der MCP-Server diese Hülle bekommt; die Router-Funktion hängt weiter am
     * eigentlichen WebMVC-Transport.
     */
    @Bean
    @Primary
    LiveInstructionsTransport liveInstructionsTransport(WebMvcStreamableServerTransportProvider transport) {
        return new LiveInstructionsTransport(transport, this::build);
    }

    /**
     * Liefert den Instructions-Text: ausführlich mit allen Modul-Hinweisen oder – mit „Kontext sparen“ und
     * {@link ContextSettings#compactInstructions} – eine Zeile je Modul; den Rest liefert {@link #guide}.
     */
    public String build() {
        ContextSettings ctx = settings();
        StringBuilder sb = new StringBuilder(PREAMBLE.strip());
        if (base != null && !base.isBlank()) {
            sb.append("\n\n").append(base.strip());
        }
        if (ctx.enabled()) {
            sb.append("\n\n").append(CONTEXT_RULES.strip());
            if (ctx.lazyTools()) {
                sb.append("\n").append(LAZY_RULES.strip());
            }
        }
        if (ctx.enabled() && ctx.compactInstructions()) {
            sb.append("\n\nJe Modul folgt nur das Wichtigste. Ausführliche Hinweise (Abläufe, Tabellen, Sonderfälle) "
                    + "liefert `context_guide(module=<id>)` – vor der ersten Nutzung eines Moduls mit eigenen Abläufen "
                    + "lesen.");
            for (ToolModule m : all()) {
                String brief = brief(m);
                String hint = ctx.shellHintsOnce() ? ShellHints.forModule(m.id()) : null;
                if (brief == null && hint == null) {
                    continue;
                }
                sb.append("\n\n").append(heading(m)).append('\n').append(brief == null ? "" : brief);
                if (hint != null) {
                    sb.append(brief == null ? "" : " ").append(hint);
                }
            }
            return sb.toString();
        }
        for (ToolModule m : all()) {
            String text = text(m);
            String hint = ctx.shellHintsOnce() ? ShellHints.forModule(m.id()) : null;
            if (!blank(text) || hint != null) {
                sb.append("\n\n").append(heading(m)).append('\n').append(blank(text) ? "" : text.strip());
                if (hint != null) {
                    sb.append(blank(text) ? "" : "\n").append(hint);
                }
            }
        }
        return sb.toString();
    }

    /** Ausführliche Hinweise eines Moduls (für {@code context_guide}), leer wenn es das Modul nicht gibt. */
    public java.util.Optional<String> guide(String moduleId) {
        return all().stream().filter(m -> m.id().equals(moduleId)).findFirst().map(m -> {
            String text = text(m);
            String hint = ShellHints.forModule(m.id());
            StringBuilder sb = new StringBuilder(heading(m)).append('\n');
            sb.append(blank(text) ? m.description() : text.strip());
            if (hint != null) {
                sb.append('\n').append(hint);
            }
            return sb.toString();
        });
    }

    /** IDs und Namen aller Module mit Hinweisen (für Fehlermeldungen von {@code context_guide}). */
    public List<String> moduleIds() {
        return all().stream().map(ToolModule::id).toList();
    }

    /**
     * Ab jetzt die wirksamen Einstellungen (Registry, inkl. Backend bzw. Team-Server) statt der lokalen Ablage – sobald
     * die Registry steht; beim Serveraufbau gibt es sie noch nicht.
     */
    public void useContext(Supplier<ContextSettings> effective) {
        this.live = effective;
    }

    private ContextSettings settings() {
        try {
            Supplier<ContextSettings> l = live;
            return l != null ? l.get() : context.get();
        } catch (RuntimeException e) {
            LOG.warn("Einstellungen von „Kontext sparen“ nicht lesbar – ausführliche Instructions", e);
            return ContextSettings.OFF;
        }
    }

    /** Alle Module in der Reihenfolge der Modulliste und Tool-Registrierung ({@link ToolRegistry}). */
    private List<ToolModule> all() {
        List<ToolModule> all = new ArrayList<>(modules);
        try {
            all.addAll(pluginModules.get());
        } catch (RuntimeException e) {
            LOG.warn("Plugin- und Skript-Module für die Instructions nicht ermittelbar", e);
        }
        all.sort(ToolRegistry.MODULE_ORDER);
        return all;
    }

    private static String heading(ToolModule m) {
        return "## " + m.displayName() + " – Tools `" + m.id() + "_*`";
    }

    /** Instructions des Moduls; ein fehlerhaftes Modul darf den Serveraufbau nicht verhindern. */
    private static String text(ToolModule m) {
        try {
            return m.instructions();
        } catch (RuntimeException e) {
            LOG.warn("Instructions des Moduls {} konnten nicht erzeugt werden", m.id(), e);
            return null;
        }
    }

    /**
     * Kurzfassung des Moduls; ohne eigene Kurzfassung kurze Instructions vollständig (Plugins und Skripte schreiben
     * meist nur ein, zwei Sätze), sonst der erste Satz der Beschreibung. {@code null} bei Modulen ohne Hinweise.
     */
    private static String brief(ToolModule m) {
        try {
            String b = m.briefInstructions();
            if (!blank(b)) {
                return b.strip().replaceAll("\\s*\\R\\s*", " ");
            }
            String full = text(m);
            if (blank(full)) {
                return null;
            }
            if (full.strip().length() <= SHORT_INSTRUCTIONS) {
                return full.strip();
            }
            return firstSentenceOf(blank(m.description()) ? full : m.description());
        } catch (RuntimeException e) {
            LOG.warn("Kurzfassung des Moduls {} nicht ermittelbar", m.id(), e);
            return null;
        }
    }

    /** Satzende – nicht nach Abkürzungen wie „z.B.“, „bzw.“ oder „ggf.“. */
    private static final java.util.regex.Pattern SENTENCE_END = java.util.regex.Pattern.compile(
            "(?<!\\b\\p{L})(?<!\\b(?:bzw|ggf|etc|ca|vgl|sog|evtl|inkl|max|min|Nr))[.!?](?=\\s|$)");

    /** Erster Satz (bis {@code .!?} vor Leerraum oder Ende), Leerraum zusammengefasst. */
    public static String firstSentenceOf(String s) {
        if (blank(s)) {
            return null;
        }
        String t = s.strip().replaceAll("\\s+", " ");
        java.util.regex.Matcher end = SENTENCE_END.matcher(t);
        return end.find() ? t.substring(0, end.start() + 1) : t;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
