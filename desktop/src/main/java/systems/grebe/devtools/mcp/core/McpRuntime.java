package systems.grebe.devtools.mcp.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import systems.grebe.devtools.mcp.config.ModuleSettings;

/**
 * Ein MCP-Server mit den Tools für genau einen {@link ToolScope} (Benutzer + Profil). Hält je Modul die erzeugten
 * Tools und gleicht die Registrierung am Server ab; {@code addTool}/{@code removeTool} benachrichtigen die Clients
 * dieses Servers selbst ({@code notifications/tools/list_changed}).
 *
 * <p>Welche Module es gibt und welche Einstellungen gelten, entscheidet die {@link ToolRegistry}; die Runtime setzt
 * nur um. Jeder Tool-Aufruf läuft mit {@link ToolScope#current()} = Scope dieser Runtime.
 *
 * <p><b>Stabile Tool-Liste:</b> Ein Neuaufbau tauscht bei unveränderter Definition nur den Aufruf aus, statt das Tool
 * zu entfernen und neu anzumelden. So bleiben Reihenfolge und Inhalt von {@code tools/list} gleich und es geht keine
 * {@code list_changed}-Meldung raus – der Client muss die Liste nicht neu laden, und sein Prompt-Cache (Tool-Definitionen
 * stehen ganz vorn im Prompt) bleibt gültig.
 *
 * <p><b>Aktiv vs. angeboten:</b> Aktiv ist ein Tool, wenn Modul und Schalter es erlauben; angeboten (beim Server
 * registriert) nur, wenn außerdem {@link ContextSettings#exposes} zustimmt. Nicht angebotene aktive Tools erreicht das
 * LLM über {@code context_call}.
 */
public final class McpRuntime implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(McpRuntime.class);

    private final McpSyncServer server;
    private final ToolScope scope;
    private final ToolInvocationLog invocationLog;
    private final Map<String, ModuleTools> modules = new HashMap<>();
    /** Beim Server registrierte Tools – ihr Aufruf läuft über den austauschbaren {@link Slot#handler}. */
    private final Map<String, Slot> slots = new HashMap<>();
    private ContextSettings context = ContextSettings.OFF;

    public McpRuntime(McpSyncServer server, ToolScope scope, ToolInvocationLog invocationLog) {
        this.server = server;
        this.scope = scope;
        this.invocationLog = invocationLog;
    }

    public McpSyncServer server() {
        return server;
    }

    public ToolScope scope() {
        return scope;
    }

    /** Einstellungen für Angebot und Beschreibungen; wirksam beim nächsten {@link #rebuild}. */
    public synchronized void setContext(ContextSettings value) {
        this.context = value == null ? ContextSettings.OFF : value;
    }

    /** Alle Tools, die das Modul mit seiner Konfiguration anbietet (auch deaktivierte). */
    public synchronized List<ManagedToolCallback> tools(String moduleId) {
        ModuleTools m = modules.get(moduleId);
        return m == null ? List.of() : m.tools;
    }

    public synchronized Optional<String> error(String moduleId) {
        ModuleTools m = modules.get(moduleId);
        return Optional.ofNullable(m == null ? null : m.error);
    }

    public synchronized boolean isActive(String moduleId, String toolName) {
        ModuleTools m = modules.get(moduleId);
        return m != null && m.registered.contains(toolName);
    }

    public synchronized List<String> activeToolNames() {
        return modules.values().stream().flatMap(m -> m.registered.stream()).sorted().toList();
    }

    /** Definitionen aller aktiven Tools (auch der nicht angebotenen), nach Namen sortiert. */
    public synchronized List<ToolDefinition> activeToolDefinitions() {
        List<ToolDefinition> out = new ArrayList<>();
        for (ModuleTools m : modules.values()) {
            for (ManagedToolCallback cb : m.tools) {
                if (m.registered.contains(cb.getToolDefinition().name())) {
                    out.add(cb.getToolDefinition());
                }
            }
        }
        out.sort(java.util.Comparator.comparing(ToolDefinition::name));
        return out;
    }

    /** Umfang der Tool-Definitionen, die der Client zu sehen bekommt. */
    public record Exposed(int tools, int activeTools, long chars) {
    }

    /** Wie viele Tools angeboten werden und wie lang ihre Definitionen (Name, Beschreibung, Schema) zusammen sind. */
    public synchronized Exposed exposed() {
        int tools = 0;
        int active = 0;
        long chars = 0;
        for (ModuleTools m : modules.values()) {
            active += m.registered.size();
            for (ManagedToolCallback cb : m.tools) {
                ToolDefinition d = cb.getToolDefinition();
                Slot slot = slots.get(d.name());
                if (m.exposed.contains(d.name()) && slot != null) {
                    tools++;
                    chars += d.name().length() + (slot.tool.description() == null ? 0 : slot.tool.description().length())
                            + d.inputSchema().length();
                }
            }
        }
        return new Exposed(tools, active, chars);
    }

    /**
     * Ein aktives Tool (Modul an, Tool nicht abgeschaltet) mit vollem Namen, z.B. {@code git_status} – für Aufrufe aus
     * Skripten. Der Aufruf landet im Aufrufprotokoll, aber ohne die {@link ToolCallListener}: deren Hinweise gelten
     * dem LLM und kommen schon beim äußeren Aufruf.
     */
    public synchronized Optional<ToolCallback> activeTool(String name) {
        return enabledTool(name).map(cb -> new ManagedToolCallback(cb.moduleId(), cb.delegate(), invocationLog));
    }

    /**
     * Ein aktives Tool samt {@link ToolCallListener} – so, wie es der Client direkt aufrufen würde (für
     * {@code context_call}, wenn das Tool nicht angeboten wird).
     */
    public synchronized Optional<ManagedToolCallback> enabledTool(String name) {
        for (ModuleTools m : modules.values()) {
            if (!m.registered.contains(name)) {
                continue;
            }
            for (ManagedToolCallback cb : m.tools) {
                if (cb.getToolDefinition().name().equals(name)) {
                    return Optional.of(cb);
                }
            }
        }
        return Optional.empty();
    }

    /** Erzeugt die Tools des Moduls neu und gleicht die Registrierung am Server ab. */
    public synchronized void rebuild(ToolModule module, ModuleSettings settings, List<ToolCallListener> listeners) {
        rebuild(module, settings, listeners, tool -> true);
    }

    /**
     * Wie {@link #rebuild(ToolModule, ModuleSettings, List)}; registriert werden nur Tools, die {@code permitted}
     * erlaubt (Rechte des angemeldeten Benutzers). Erzeugt werden alle – der Katalog fürs Backend braucht sie.
     */
    public synchronized void rebuild(ToolModule module, ModuleSettings settings, List<ToolCallListener> listeners,
                                     java.util.function.Predicate<String> permitted) {
        List<ManagedToolCallback> tools = new ArrayList<>();
        String error = null;
        try {
            ModuleConfig cfg = ModuleConfig.of(module.configSchema(), settings.values());
            ToolScope s = scope;
            for (ToolCallback cb : ToolScope.callIn(s, () -> module.createTools(cfg, s))) {
                tools.add(new ManagedToolCallback(module.id(), cb, invocationLog, listeners));
            }
        } catch (RuntimeException e) {
            LOG.error("Tools für Modul {} ({}) konnten nicht erzeugt werden", module.id(), scope, e);
            error = ManagedToolCallback.describe(e);
        }
        ModuleTools previous = modules.get(module.id());
        ModuleTools current = new ModuleTools(tools, error);
        modules.put(module.id(), current);
        if (settings.enabled()) {
            for (ManagedToolCallback cb : tools) {
                String name = cb.getToolDefinition().name();
                if (settings.disabledTools().contains(name) || !permitted.test(name)) {
                    continue;
                }
                current.registered.add(name);
                if (!context.exposes(module.id(), name)) {
                    continue;
                }
                try {
                    expose(name, cb);
                    current.exposed.add(name);
                } catch (RuntimeException e) {
                    LOG.error("Tool {} konnte nicht registriert werden", name, e);
                }
            }
        }
        if (previous != null) {
            previous.exposed.removeAll(current.exposed);
            unregister(previous);
        }
    }

    /** Meldet das Tool an oder tauscht bei gleicher Definition nur den Aufruf aus. */
    private void expose(String name, ManagedToolCallback cb) {
        McpServerFeatures.SyncToolSpecification spec = inScope(McpProgress.wrap(withAnnotations(
                McpToolUtils.toSyncToolSpecification(cb), McpToolHints.annotations(cb))));
        McpSchema.Tool tool = context.shellHintsOnce() ? withDescription(spec.tool(),
                ShellHints.strip(spec.tool().description())) : spec.tool();
        Slot slot = slots.get(name);
        if (slot != null && slot.tool.equals(tool)) {
            slot.handler = spec.callHandler();
            return;
        }
        if (slot != null) {
            removeQuietly(name);
        }
        Slot fresh = new Slot(tool, spec.callHandler());
        server.addTool(new McpServerFeatures.SyncToolSpecification(tool,
                (exchange, request) -> fresh.handler.apply(exchange, request)));
        slots.put(name, fresh);
    }

    /** Entfernt alle Tools des Moduls vom Server. */
    public synchronized void remove(String moduleId) {
        ModuleTools m = modules.remove(moduleId);
        if (m != null) {
            unregister(m);
        }
    }

    private void unregister(ModuleTools m) {
        for (String name : m.exposed) {
            removeQuietly(name);
        }
        m.exposed.clear();
    }

    private void removeQuietly(String name) {
        slots.remove(name);
        try {
            server.removeTool(name);
        } catch (RuntimeException e) {
            LOG.debug("Tool {} war nicht registriert", name);
        }
    }

    /** Aufrufe laufen mit dem Scope dieser Runtime als {@link ToolScope#current()}. */
    private McpServerFeatures.SyncToolSpecification inScope(McpServerFeatures.SyncToolSpecification spec) {
        BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> handler = spec.callHandler();
        return new McpServerFeatures.SyncToolSpecification(spec.tool(),
                (exchange, request) -> ToolScope.callIn(this.scope, () -> handler.apply(exchange, request)));
    }

    /** Übernimmt die {@link ToolHints} eines Tools in seine MCP-Definition (Spring AI kennt sie nicht). */
    static McpServerFeatures.SyncToolSpecification withAnnotations(McpServerFeatures.SyncToolSpecification spec,
                                                                   McpSchema.ToolAnnotations annotations) {
        if (annotations == null) {
            return spec;
        }
        McpSchema.Tool t = spec.tool();
        McpSchema.Tool tool = new McpSchema.Tool(t.name(), t.title(), t.description(), t.inputSchema(),
                t.outputSchema(), annotations, t.meta(), t.icons());
        return new McpServerFeatures.SyncToolSpecification(tool, spec.callHandler());
    }

    private static McpSchema.Tool withDescription(McpSchema.Tool t, String description) {
        return description == null || description.equals(t.description()) ? t
                : new McpSchema.Tool(t.name(), t.title(), description, t.inputSchema(), t.outputSchema(),
                        t.annotations(), t.meta(), t.icons());
    }

    /** Schließt den Scope (offene Sitzungen der Module); der Server selbst gehört dem Aufrufer. */
    @Override
    public void close() {
        scope.close();
    }

    /** Registriertes Tool; der Aufruf ist austauschbar, damit ein Neuaufbau die Anmeldung nicht ändert. */
    private static final class Slot {
        final McpSchema.Tool tool;
        volatile BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> handler;

        Slot(McpSchema.Tool tool,
             BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> handler) {
            this.tool = tool;
            this.handler = handler;
        }
    }

    private static final class ModuleTools {
        final List<ManagedToolCallback> tools;
        final String error;
        /** Aktive Tools (Modul an, Schalter an, erlaubt). */
        final Set<String> registered = new LinkedHashSet<>();
        /** Davon beim Server angemeldet. */
        final Set<String> exposed = new LinkedHashSet<>();

        ModuleTools(List<ManagedToolCallback> tools, String error) {
            this.tools = List.copyOf(tools);
            this.error = error;
        }
    }
}
