package systems.grebe.devtools.mcp.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import io.modelcontextprotocol.server.McpSyncServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import systems.grebe.devtools.mcp.config.ModuleSettings;
import systems.grebe.devtools.mcp.config.SettingsStore;

/**
 * Zentrale Verwaltung aller {@link ToolModule}s: hält Konfiguration und Aktivierungsstatus und
 * synchronisiert die am {@link McpSyncServer} registrierten Tools zur Laufzeit. Verbundene Clients werden
 * über {@code notifications/tools/list_changed} informiert.
 */
@Service
public class ToolRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(ToolRegistry.class);

    private final McpSyncServer server;
    private final SettingsStore store;
    private final ToolInvocationLog invocationLog;
    private final Map<String, ModuleState> states = new LinkedHashMap<>();
    private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();
    private final ObjectProvider<ToolCallListener> callListenerProvider;
    private volatile List<ToolCallListener> callListeners;

    public ToolRegistry(McpSyncServer server, SettingsStore store, ToolInvocationLog invocationLog,
                        List<ToolModule> modules, ObjectProvider<ToolCallListener> callListeners) {
        this.server = server;
        this.store = store;
        this.invocationLog = invocationLog;
        // Lazy: Listener dürfen ihrerseits die Registry brauchen (ObjectProvider), ohne Zirkelbezug beim Start.
        this.callListenerProvider = callListeners;
        modules.stream()
                .sorted(Comparator.comparingInt(ToolModule::order)
                        .thenComparing(ToolModule::displayName, String.CASE_INSENSITIVE_ORDER))
                .forEach(m -> {
                    if (states.containsKey(m.id())) {
                        throw new IllegalStateException("Doppelte Modul-ID: " + m.id());
                    }
                    ModuleSettings settings = store.module(m.id())
                            .orElseGet(() -> new ModuleSettings(m.enabledByDefault(), Set.of(),
                                    m.initialValues(other -> store.module(other).map(ModuleSettings::values).orElse(Map.of()))));
                    states.put(m.id(), new ModuleState(m, settings));
                });
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerAll() {
        states.keySet().forEach(this::rebuild);
        LOG.info("MCP-Tools registriert: {} aktiv", activeToolCount());
    }

    // ------------------------------------------------------------------ Lesen

    public List<ToolModule> modules() {
        synchronized (states) {
            return states.values().stream().map(s -> s.module).toList();
        }
    }

    public ModuleSettings settings(String moduleId) {
        return state(moduleId).settings;
    }

    public ModuleConfig config(String moduleId) {
        ModuleState s = state(moduleId);
        return ModuleConfig.of(s.module.configSchema(), s.settings.values());
    }

    /** Alle Tools, die das Modul mit der aktuellen Konfiguration anbietet (auch deaktivierte). */
    public List<ToolDefinition> availableTools(String moduleId) {
        return state(moduleId).tools.stream().map(ToolCallback::getToolDefinition).toList();
    }

    /** Fehler beim Erzeugen der Tools, falls vorhanden. */
    public Optional<String> moduleError(String moduleId) {
        return Optional.ofNullable(state(moduleId).error);
    }

    public boolean isToolActive(String moduleId, String toolName) {
        return state(moduleId).registered.contains(toolName);
    }

    public int activeToolCount() {
        synchronized (states) {
            return states.values().stream().mapToInt(s -> s.registered.size()).sum();
        }
    }

    public List<String> activeToolNames() {
        synchronized (states) {
            return states.values().stream().flatMap(s -> s.registered.stream()).sorted().toList();
        }
    }

    // ------------------------------------------------------------------ Ändern

    public void setModuleEnabled(String moduleId, boolean enabled) {
        update(moduleId, s -> s.withEnabled(enabled));
    }

    public void setToolEnabled(String moduleId, String toolName, boolean enabled) {
        update(moduleId, s -> {
            Set<String> disabled = new LinkedHashSet<>(s.disabledTools());
            if (enabled) {
                disabled.remove(toolName);
            } else {
                disabled.add(toolName);
            }
            return s.withDisabledTools(disabled);
        });
    }

    public void updateConfig(String moduleId, Map<String, String> values) {
        update(moduleId, s -> s.withValues(values));
    }

    public ConnectionTestResult testConnection(String moduleId, Map<String, String> values) {
        ToolModule m = state(moduleId).module;
        try {
            return m.testConnection(ModuleConfig.of(m.configSchema(), values));
        } catch (RuntimeException e) {
            return ConnectionTestResult.failed(ManagedToolCallback.describe(e));
        }
    }

    // ------------------------------------------------------------------ Modul-Aktionen (UI)

    public List<ModuleAction> actions(String moduleId) {
        return state(moduleId).module.actions();
    }

    /** Ziele der Aktion für die gespeicherte Konfiguration; bei Fehlern leer. */
    public List<String> actionTargets(String moduleId, String actionId) {
        try {
            return action(moduleId, actionId).targets(config(moduleId));
        } catch (RuntimeException e) {
            LOG.warn("Ziele für Aktion {}/{} nicht ermittelbar", moduleId, actionId, e);
            return List.of();
        }
    }

    /** Zustand eines Ziels für die Anzeige; bei Fehlern die Meldung. */
    public String describeActionTarget(String moduleId, String actionId, String target) {
        try {
            return action(moduleId, actionId).describe(config(moduleId), target);
        } catch (RuntimeException e) {
            return ManagedToolCallback.describe(e);
        }
    }

    /**
     * Führt eine Aktion mit der gespeicherten Konfiguration aus (blockierend – aus einem Hintergrund-Thread aufrufen).
     * Ein Thread-Interrupt bricht ab. Anschließend werden die Change-Listener benachrichtigt.
     */
    public ModuleAction.ActionResult runAction(String moduleId, String actionId, String target, Set<String> flags,
                                               ModuleAction.Progress progress) {
        ModuleAction a = action(moduleId, actionId);
        try {
            return a.run(config(moduleId), target, flags == null ? Set.of() : flags,
                    progress == null ? ModuleAction.Progress.NONE : progress);
        } catch (RuntimeException e) {
            if (Thread.currentThread().isInterrupted() || e.getCause() instanceof InterruptedException) {
                return ModuleAction.ActionResult.failed("Abgebrochen.");
            }
            LOG.error("Aktion {}/{} fehlgeschlagen", moduleId, actionId, e);
            return ModuleAction.ActionResult.failed(ManagedToolCallback.describe(e));
        } finally {
            changeListeners.forEach(Runnable::run);
        }
    }

    private ModuleAction action(String moduleId, String actionId) {
        return state(moduleId).module.actions().stream().filter(a -> a.id().equals(actionId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unbekannte Aktion " + moduleId + "/" + actionId));
    }

    public void addChangeListener(Runnable listener) {
        changeListeners.add(listener);
    }

    // ------------------------------------------------------------------ intern

    private void update(String moduleId, java.util.function.UnaryOperator<ModuleSettings> change) {
        ModuleState s = state(moduleId);
        synchronized (states) {
            s.settings = change.apply(s.settings);
            store.saveModule(moduleId, s.settings, secretKeys(s.module));
        }
        rebuild(moduleId);
    }

    private static Set<String> secretKeys(ToolModule m) {
        return m.configSchema().stream()
                .filter(f -> f.type() == FieldType.SECRET)
                .map(ConfigField::key)
                .collect(Collectors.toSet());
    }

    /** Erzeugt die Tools des Moduls neu und gleicht die Registrierung am MCP-Server ab. */
    private void rebuild(String moduleId) {
        ModuleState s = state(moduleId);
        synchronized (states) {
            List<ManagedToolCallback> tools = new ArrayList<>();
            String error = null;
            try {
                ModuleConfig cfg = ModuleConfig.of(s.module.configSchema(), s.settings.values());
                for (ToolCallback cb : s.module.createTools(cfg)) {
                    tools.add(new ManagedToolCallback(s.module.id(), cb, invocationLog, callListeners()));
                }
            } catch (RuntimeException e) {
                LOG.error("Tools für Modul {} konnten nicht erzeugt werden", moduleId, e);
                error = ManagedToolCallback.describe(e);
            }
            s.tools = tools;
            s.error = error;

            for (String name : s.registered) {
                try {
                    server.removeTool(name);
                } catch (RuntimeException e) {
                    LOG.debug("Tool {} war nicht registriert", name);
                }
            }
            s.registered = new LinkedHashSet<>();
            if (s.settings.enabled()) {
                for (ManagedToolCallback cb : tools) {
                    String name = cb.getToolDefinition().name();
                    if (s.settings.disabledTools().contains(name)) {
                        continue;
                    }
                    try {
                        server.addTool(McpToolUtils.toSyncToolSpecification(cb));
                        s.registered.add(name);
                    } catch (RuntimeException e) {
                        LOG.error("Tool {} konnte nicht registriert werden", name, e);
                    }
                }
            }
        }
        // Kein explizites notifyToolsListChanged(): addTool/removeTool benachrichtigen die Clients bereits selbst
        // (spring.ai.mcp.server.tool-change-notification=true).
        changeListeners.forEach(Runnable::run);
    }

    private List<ToolCallListener> callListeners() {
        List<ToolCallListener> l = callListeners;
        if (l == null) {
            l = callListenerProvider.orderedStream().toList();
            callListeners = l;
        }
        return l;
    }

    private ModuleState state(String moduleId) {
        synchronized (states) {
            ModuleState s = states.get(moduleId);
            if (s == null) {
                throw new IllegalArgumentException("Unbekanntes Modul: " + moduleId);
            }
            return s;
        }
    }

    private static final class ModuleState {
        final ToolModule module;
        ModuleSettings settings;
        List<ManagedToolCallback> tools = List.of();
        Set<String> registered = new LinkedHashSet<>();
        String error;

        ModuleState(ToolModule module, ModuleSettings settings) {
            this.module = module;
            this.settings = settings;
        }
    }
}
