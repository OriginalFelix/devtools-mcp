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
import systems.grebe.devtools.mcp.config.ModuleSettings;

/**
 * Ein MCP-Server mit den Tools für genau einen {@link ToolScope} (Benutzer + Profil). Hält je Modul die erzeugten
 * Tools und gleicht die Registrierung am Server ab; {@code addTool}/{@code removeTool} benachrichtigen die Clients
 * dieses Servers selbst ({@code notifications/tools/list_changed}).
 *
 * <p>Welche Module es gibt und welche Einstellungen gelten, entscheidet die {@link ToolRegistry}; die Runtime setzt
 * nur um. Jeder Tool-Aufruf läuft mit {@link ToolScope#current()} = Scope dieser Runtime.
 */
public final class McpRuntime implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(McpRuntime.class);

    private final McpSyncServer server;
    private final ToolScope scope;
    private final ToolInvocationLog invocationLog;
    private final Map<String, ModuleTools> modules = new HashMap<>();

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

    /**
     * Ein aktives Tool (Modul an, Tool nicht abgeschaltet) mit vollem Namen, z.B. {@code git_status} – für Aufrufe aus
     * Skripten. Der Aufruf landet im Aufrufprotokoll, aber ohne die {@link ToolCallListener}: deren Hinweise gelten
     * dem LLM und kommen schon beim äußeren Aufruf.
     */
    public synchronized Optional<ToolCallback> activeTool(String name) {
        for (Map.Entry<String, ModuleTools> e : modules.entrySet()) {
            if (!e.getValue().registered.contains(name)) {
                continue;
            }
            for (ManagedToolCallback cb : e.getValue().tools) {
                if (cb.getToolDefinition().name().equals(name)) {
                    return Optional.of(new ManagedToolCallback(e.getKey(), cb.delegate(), invocationLog));
                }
            }
        }
        return Optional.empty();
    }

    /** Erzeugt die Tools des Moduls neu und gleicht die Registrierung am Server ab. */
    public synchronized void rebuild(ToolModule module, ModuleSettings settings, List<ToolCallListener> listeners) {
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
        if (previous != null) {
            unregister(previous);
        }
        ModuleTools current = new ModuleTools(tools, error);
        modules.put(module.id(), current);
        if (!settings.enabled()) {
            return;
        }
        for (ManagedToolCallback cb : tools) {
            String name = cb.getToolDefinition().name();
            if (settings.disabledTools().contains(name)) {
                continue;
            }
            try {
                server.addTool(inScope(McpProgress.wrap(withAnnotations(McpToolUtils.toSyncToolSpecification(cb),
                        McpToolHints.annotations(cb)))));
                current.registered.add(name);
            } catch (RuntimeException e) {
                LOG.error("Tool {} konnte nicht registriert werden", name, e);
            }
        }
    }

    /** Entfernt alle Tools des Moduls vom Server. */
    public synchronized void remove(String moduleId) {
        ModuleTools m = modules.remove(moduleId);
        if (m != null) {
            unregister(m);
        }
    }

    private void unregister(ModuleTools m) {
        for (String name : m.registered) {
            try {
                server.removeTool(name);
            } catch (RuntimeException e) {
                LOG.debug("Tool {} war nicht registriert", name);
            }
        }
        m.registered.clear();
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

    /** Schließt den Scope (offene Sitzungen der Module); der Server selbst gehört dem Aufrufer. */
    @Override
    public void close() {
        scope.close();
    }

    private static final class ModuleTools {
        final List<ManagedToolCallback> tools;
        final String error;
        final Set<String> registered = new LinkedHashSet<>();

        ModuleTools(List<ManagedToolCallback> tools, String error) {
            this.tools = List.copyOf(tools);
            this.error = error;
        }
    }
}
