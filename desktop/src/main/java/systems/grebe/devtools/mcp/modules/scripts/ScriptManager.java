package systems.grebe.devtools.mcp.modules.scripts;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.core.ManagedToolCallback;
import systems.grebe.devtools.mcp.core.ToolModule;
import systems.grebe.devtools.mcp.core.ToolRegistry;

/**
 * Hält die Module aus den Skripten (Groovy, Java, Gherkin) des Backends aktuell: Bei jeder Änderung (Subscription
 * {@code scriptsChanged}, auch von anderen Desktop-Apps oder der Web-UI) wird die Liste abgeglichen – neue oder
 * geänderte Skripte werden übersetzt und als Modul registriert, gelöschte entfernt. Verbundene MCP-Clients bekommen
 * dabei {@code tools/list_changed}.
 *
 * <p>Speichern und Löschen (aus der App oder über die {@code scripts_*}-Tools) gehen ebenfalls hierüber: Vor dem
 * Speichern wird das Skript übersetzt und ausgewertet, danach sofort neu geladen – das Ergebnis meldet, welche Tools
 * entstanden sind.
 *
 * <p>Nach jedem Abgleich landet der Stand im {@link ScriptCache} (beim Team-Server eine verschlüsselte Datei). Ist das
 * Backend beim Start nicht erreichbar, lädt die App die Skripte von dort und gleicht ab, sobald es wieder antwortet.
 */
@Component
public class ScriptManager {

    private static final Logger LOG = LoggerFactory.getLogger(ScriptManager.class);
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9]{1,31}");
    static final int DEFAULT_TIMEOUT_SECONDS = 300;

    /**
     * Zustand eines Skripts für Anzeige und LLM.
     *
     * @param cached aus dem {@link ScriptCache} geladen, weil das Backend (noch) nicht erreichbar ist
     */
    public record Status(ScriptViews.Summary summary, String error, boolean enabled, List<String> tools,
                         List<String> activeTools, boolean cached) {

        public String name() {
            return summary.name();
        }
    }

    /** Geladenes Skript; {@code module == null}, wenn die ID schon vergeben war. */
    private record Entry(ScriptViews.Summary summary, String content, ScriptToolModule module, String conflict,
                         boolean cached) {
    }

    private final ScriptBackend backend;
    private final ObjectProvider<ToolRegistry> registry;
    private final ObjectProvider<ScriptCache> cache;
    private final ScriptCompiler compiler = new ScriptCompiler();
    private final JavaScriptCompiler javaCompiler = new JavaScriptCompiler();
    private final GherkinScriptCompiler gherkinCompiler;
    private final Map<String, Entry> loaded = new LinkedHashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "script-reload");
        t.setDaemon(true);
        return t;
    });

    public ScriptManager(ScriptBackend backend, ObjectProvider<ToolRegistry> registry,
                         ObjectProvider<ScriptCache> cache) {
        this.backend = backend;
        this.registry = registry;
        this.cache = cache;
        this.gherkinCompiler = new GherkinScriptCompiler(new RegistryToolCaller(registry::getObject));
    }

    /** Nach der Registrierung der eingebauten Module; den ersten Stand liefert die Subscription bzw. dieser Abgleich. */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        backend.addChangeListener(() -> executor.execute(this::reloadQuietly));
        executor.execute(this::reloadQuietly);
    }

    @PreDestroy
    public void stop() {
        executor.shutdownNow();
        synchronized (this) {
            loaded.values().forEach(e -> {
                if (e.module() != null) {
                    e.module().close();
                }
            });
            loaded.clear();
        }
    }

    // ------------------------------------------------------------------ Lesen

    /** Wird nach jedem Abgleich aufgerufen (beliebiger Thread). */
    public void addChangeListener(Runnable listener) {
        listeners.add(listener);
    }

    /** Die registrierten Skript-Module (für die Server-Instructions). */
    public synchronized List<ToolModule> modules() {
        return loaded.values().stream().map(Entry::module).filter(m -> m != null).map(m -> (ToolModule) m).toList();
    }

    /** Zustand aller zuletzt geladenen Skripte. */
    public List<Status> statuses() {
        List<Entry> entries;
        synchronized (this) {
            entries = List.copyOf(loaded.values());
        }
        ToolRegistry r = registry.getObject();
        List<Status> out = new ArrayList<>();
        for (Entry e : entries) {
            String name = e.summary().name();
            if (e.module() == null || !r.hasModule(name)) {
                out.add(new Status(e.summary(), e.conflict(), false, List.of(), List.of(), e.cached()));
                continue;
            }
            List<String> tools = r.availableTools(name).stream().map(ToolDefinition::name).toList();
            out.add(new Status(e.summary(), r.moduleError(name).orElse(null), r.settings(name).enabled(), tools,
                    tools.stream().filter(t -> r.isToolActive(name, t)).toList(), e.cached()));
        }
        return out;
    }

    public Optional<Status> status(String name) {
        return statuses().stream().filter(s -> s.name().equals(name)).findFirst();
    }

    public Optional<ScriptViews.Details> details(String name) {
        return backend.details(requireName(name));
    }

    /** Zeitlimit je Tool-Aufruf aus dem Modul „Skripte“. */
    Duration callTimeout() {
        try {
            int s = registry.getObject().config(ScriptsModule.ID).getInt(ScriptsModule.TIMEOUT, DEFAULT_TIMEOUT_SECONDS);
            return Duration.ofSeconds(Math.max(1, s));
        } catch (RuntimeException e) {
            return Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS);
        }
    }

    // ------------------------------------------------------------------ Ändern

    /**
     * Prüft ein Skript, ohne es zu speichern: übersetzen, Definition auswerten (der Code auf oberster Ebene bzw. der
     * Konstruktor läuft dabei!) und beschreiben, welche Tools entstünden.
     *
     * @param language {@code null} = die des vorhandenen Skripts, sonst Groovy
     */
    public String check(String name, ScriptViews.Language language, String content) {
        String n = requireName(name);
        requireFreeId(n);
        try (CompiledScript c = compile(n, languageOf(n, language), content)) {
            return "Skript '" + n + "' ist gültig: " + describe(n, c) + warnings(c.warnings());
        }
    }

    /**
     * Prüft, speichert im Backend und lädt neu; die Meldung nennt die entstandenen Tools.
     *
     * @param language {@code null} = die des vorhandenen Skripts, sonst Groovy
     */
    public String save(String name, ScriptViews.Language language, String content, String note,
                       Integer expectedRevision) {
        String n = requireName(name);
        requireFreeId(n);
        ScriptViews.Language lang = languageOf(n, language);
        String description;
        List<String> warnings;
        try (CompiledScript c = compile(n, lang, content)) {
            description = c.description();
            warnings = c.warnings();
        }
        String msg = backend.save(n, lang, description, content, note, expectedRevision);
        reload();
        return msg + " " + statusLine(n) + warnings(warnings);
    }

    /** Übersetzt je nach Sprache; Fehler als {@link IllegalArgumentException} mit Zeile. */
    CompiledScript compile(String name, ScriptViews.Language language, String content) {
        if (language == ScriptViews.Language.JAVA) {
            return javaCompiler.compile(name, content);
        }
        if (language == ScriptViews.Language.GHERKIN) {
            return gherkinCompiler.compile(name, content);
        }
        return compiler.compile(name, content);
    }

    private static String warnings(List<String> warnings) {
        return warnings.isEmpty() ? "" : "\nHinweise:\n- " + String.join("\n- ", warnings);
    }

    private synchronized ScriptViews.Language languageOf(String name, ScriptViews.Language requested) {
        if (requested != null) {
            return requested;
        }
        Entry e = loaded.get(name);
        return e == null ? ScriptViews.Language.GROOVY : e.summary().language();
    }

    public String delete(String name) {
        String msg = backend.delete(requireName(name));
        reload();
        return msg;
    }

    public String publish(String name) {
        String msg = backend.publish(requireName(name));
        reload();
        return msg;
    }

    public String unpublish(String name) {
        String msg = backend.unpublish(requireName(name));
        reload();
        return msg;
    }

    /** Kurzbeschreibung des Zustands nach dem Laden, für Meldungen an LLM und Oberfläche. */
    String statusLine(String name) {
        return status(name).map(s -> {
            if (s.error() != null) {
                return "Fehler beim Laden: " + s.error();
            }
            if (!s.enabled()) {
                return "Modul '" + name + "' ist deaktiviert (Schalter in der App) – Tools: " + String.join(", ", s.tools());
            }
            return "Modul '" + name + "' aktiv mit " + s.activeTools().size() + " Tool(s): "
                    + String.join(", ", s.activeTools()) + ".";
        }).orElse("");
    }

    private static String describe(String name, CompiledScript d) {
        List<String> tools = d.toolNames();
        return "Modul „" + d.displayName() + "“ mit " + tools.size() + " Tool(s) ("
                + String.join(", ", tools.stream().map(t -> ManagedToolCallback.prefixed(name, t)).toList())
                + ")"
                + (d.settings().isEmpty() ? "" : ", Einstellungen: " + String.join(", ",
                d.settings().stream().map(f -> f.key()).toList())) + ".";
    }

    private static String requireName(String name) {
        String n = name == null ? "" : name.trim();
        if (!NAME.matcher(n).matches()) {
            throw new IllegalArgumentException("Ungültiger Skriptname '" + n + "': 2–32 Kleinbuchstaben/Ziffern, "
                    + "beginnend mit einem Buchstaben (er wird Modul-ID und Tool-Präfix, z.B. 'jira' → jira_…).");
        }
        return n;
    }

    /** Die ID darf nicht einem eingebauten Modul oder Plugin gehören. */
    private void requireFreeId(String name) {
        boolean ours;
        synchronized (this) {
            Entry e = loaded.get(name);
            ours = e != null && e.module() != null;
        }
        if (!ours && registry.getObject().hasModule(name)) {
            throw new IllegalArgumentException("Der Name '" + name + "' ist schon die ID eines eingebauten Moduls "
                    + "oder Plugins – anderen Namen wählen.");
        }
    }

    // ------------------------------------------------------------------ Abgleich

    private void reloadQuietly() {
        try {
            reload();
        } catch (RuntimeException e) {
            LOG.debug("Skripte nicht geladen: {}", e.getMessage());
            loadCachedIfEmpty();
        }
    }

    /** Backend nicht erreichbar und noch nichts geladen: letzten Stand aus dem Cache nehmen. */
    private synchronized void loadCachedIfEmpty() {
        if (!loaded.isEmpty()) {
            return;
        }
        List<ScriptCache.Entry> cached;
        try {
            ScriptCache c = cache.getIfAvailable();
            cached = c == null ? List.of() : c.load();
        } catch (RuntimeException e) {
            LOG.warn("Skript-Cache nicht lesbar", e);
            return;
        }
        if (cached.isEmpty()) {
            return;
        }
        cached.forEach(c -> load(c.summary(), c.content(), true));
        LOG.info("Backend nicht erreichbar – Skripte aus dem letzten Stand geladen: {}", loaded.keySet());
        fireChanged();
    }

    /** Gleicht die registrierten Skript-Module mit dem Backend ab (nur geänderte Skripte werden neu übersetzt). */
    public synchronized void reload() {
        List<ScriptViews.Summary> visible = backend.overview();
        Map<String, ScriptViews.Summary> byName = new LinkedHashMap<>();
        visible.forEach(s -> byName.put(s.name(), s));
        boolean changed = false;
        for (String name : List.copyOf(loaded.keySet())) {
            if (!byName.containsKey(name)) {
                unload(name);
                changed = true;
            }
        }
        for (ScriptViews.Summary s : visible) {
            Entry e = loaded.get(s.name());
            if (e != null && e.summary().revision() == s.revision() && e.summary().scope() == s.scope()) {
                if (e.cached()) { // Stand aus dem Cache ist aktuell: Modul behalten, nur die Markierung weg
                    loaded.put(s.name(), new Entry(s, e.content(), e.module(), e.conflict(), false));
                    changed = true;
                }
                continue;
            }
            Optional<ScriptViews.Details> details = backend.details(s.name());
            if (details.isEmpty()) {
                continue; // inzwischen gelöscht – der nächste Abgleich räumt auf
            }
            unload(s.name());
            load(details.get().summary(), details.get().content(), false);
            changed = true;
        }
        if (changed) {
            LOG.info("Skripte geladen: {}", loaded.keySet());
        }
        storeCache(); // auch ohne Änderung: ein veralteter Cache (z.B. gelöschte Skripte) wird so ersetzt
        fireChanged();
    }

    private void storeCache() {
        ScriptCache c = cache.getIfAvailable();
        if (c == null) {
            return;
        }
        try {
            c.store(loaded.values().stream().map(e -> new ScriptCache.Entry(e.summary(), e.content())).toList());
        } catch (RuntimeException e) {
            LOG.warn("Skript-Cache nicht geschrieben", e);
        }
    }

    private void fireChanged() {
        listeners.forEach(l -> {
            try {
                l.run();
            } catch (RuntimeException ex) {
                LOG.warn("Skript-Listener fehlgeschlagen", ex);
            }
        });
    }

    private void load(ScriptViews.Summary s, String content, boolean cached) {
        ToolRegistry r = registry.getObject();
        if (r.hasModule(s.name())) {
            loaded.put(s.name(), new Entry(s, content, null, "Der Name '" + s.name() + "' ist schon die ID eines "
                    + "eingebauten Moduls oder Plugins – das Skript wird nicht geladen. Unter anderem Namen speichern.",
                    cached));
            return;
        }
        ScriptToolModule module;
        try {
            module = ScriptToolModule.of(s, compile(s.name(), s.language(), content), this::callTimeout);
        } catch (IllegalArgumentException e) {
            LOG.warn("Skript {} nicht übersetzbar: {}", s.name(), e.getMessage());
            module = ScriptToolModule.broken(s, e.getMessage());
        }
        try {
            r.register(module);
            loaded.put(s.name(), new Entry(s, content, module, null, cached));
        } catch (IllegalArgumentException e) {
            module.close();
            loaded.put(s.name(), new Entry(s, content, null, e.getMessage(), cached));
        }
    }

    private void unload(String name) {
        Entry e = loaded.remove(name);
        if (e != null && e.module() != null) {
            registry.getObject().unregister(name);
            e.module().close();
        }
    }
}
