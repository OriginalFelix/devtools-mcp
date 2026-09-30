package systems.grebe.devtools.mcp.core;

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
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import systems.grebe.devtools.mcp.config.ModuleSettings;
import systems.grebe.devtools.mcp.config.SettingsStore;

/**
 * Zentrale Verwaltung aller {@link ToolModule}s: hält Konfiguration und Aktivierungsstatus und lässt die
 * {@link McpRuntime}s ihre Tools danach neu aufbauen. Verbundene Clients werden über
 * {@code notifications/tools/list_changed} informiert.
 *
 * <p>Derzeit gibt es genau eine Runtime ({@link ToolScope#LOCAL}) am automatisch konfigurierten {@link McpSyncServer};
 * Abfragen wie {@link #isToolActive} beziehen sich auf sie.
 */
@Service
public class ToolRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(ToolRegistry.class);

    private final McpRuntime local;
    private final SettingsStore store;
    private final ToolInvocationLog invocationLog;
    private final Map<String, ModuleState> states = new LinkedHashMap<>();
    private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();
    private final ObjectProvider<ToolCallListener> callListenerProvider;
    private volatile List<ToolCallListener> callListeners;

    public ToolRegistry(McpSyncServer server, SettingsStore store, ToolInvocationLog invocationLog,
                        List<ToolModule> modules, ObjectProvider<ToolCallListener> callListeners) {
        this.local = new McpRuntime(server, ToolScope.LOCAL, invocationLog);
        this.store = store;
        this.invocationLog = invocationLog;
        // Lazy: Listener dürfen ihrerseits die Registry brauchen (ObjectProvider), ohne Zirkelbezug beim Start.
        this.callListenerProvider = callListeners;
        modules.stream().sorted(MODULE_ORDER).forEach(m -> {
            if (states.containsKey(m.id())) {
                throw new IllegalStateException("Doppelte Modul-ID: " + m.id());
            }
            states.put(m.id(), new ModuleState(m, initialSettings(m)));
        });
    }

    /** Reihenfolge der Modulliste: {@link ToolModule#order()}, dann Anzeigename. */
    public static final Comparator<ToolModule> MODULE_ORDER = Comparator.comparingInt(ToolModule::order)
            .thenComparing(ToolModule::displayName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(ToolModule::id);

    private static final java.util.regex.Pattern MODULE_ID = java.util.regex.Pattern.compile("[a-z][a-z0-9]{1,31}");

    private ModuleSettings initialSettings(ToolModule m) {
        return store.module(m.id())
                .orElseGet(() -> new ModuleSettings(m.enabledByDefault(), Set.of(),
                        m.initialValues(other -> store.module(other).map(ModuleSettings::values).orElse(Map.of()))));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerAll() {
        List<String> ids;
        synchronized (states) {
            ids = List.copyOf(states.keySet());
        }
        ids.forEach(this::rebuild);
        LOG.info("MCP-Tools registriert: {} aktiv", activeToolCount());
    }

    /** Schließt beim Beenden den Zustand der Module (offene SSH-Sitzungen, Debugger …). */
    @PreDestroy
    public void close() {
        local.close();
    }

    // ------------------------------------------------------------------ Lesen

    public List<ToolModule> modules() {
        synchronized (states) {
            return states.values().stream().map(s -> s.module).sorted(MODULE_ORDER).toList();
        }
    }

    public boolean hasModule(String moduleId) {
        synchronized (states) {
            return states.containsKey(moduleId);
        }
    }

    // ------------------------------------------------------------------ Module zur Laufzeit (Plugins)

    /**
     * Nimmt ein Modul zur Laufzeit auf (z.B. aus einem Plugin) und registriert seine Tools sofort. Gespeicherte
     * Einstellungen unter derselben ID werden übernommen – Schalter und Konfiguration überleben so Updates und
     * Neuinstallationen.
     *
     * @throws IllegalArgumentException bei ungültiger oder bereits vergebener ID
     */
    public void register(ToolModule module) {
        String id = module.id();
        if (id == null || !MODULE_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Modul-ID '" + id + "' ungültig: 2–32 Kleinbuchstaben/Ziffern, "
                    + "beginnend mit einem Buchstaben (sie wird Tool-Präfix, z.B. " + "jira_issue).");
        }
        synchronized (states) {
            if (states.containsKey(id)) {
                throw new IllegalArgumentException("Modul-ID '" + id + "' ist bereits vergeben.");
            }
            states.put(id, new ModuleState(module, initialSettings(module)));
        }
        rebuild(id);
        LOG.info("Modul {} aufgenommen", id);
    }

    /** Entfernt ein zur Laufzeit aufgenommenes Modul samt seiner Tools. Gespeicherte Einstellungen bleiben erhalten. */
    public void unregister(String moduleId) {
        synchronized (states) {
            if (states.remove(moduleId) == null) {
                return;
            }
            local.remove(moduleId);
        }
        LOG.info("Modul {} entfernt", moduleId);
        changeListeners.forEach(Runnable::run);
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
        state(moduleId);
        return local.tools(moduleId).stream().map(ToolCallback::getToolDefinition).toList();
    }

    /** Fehler beim Erzeugen der Tools, falls vorhanden. */
    public Optional<String> moduleError(String moduleId) {
        state(moduleId);
        return local.error(moduleId);
    }

    public boolean isToolActive(String moduleId, String toolName) {
        state(moduleId);
        return local.isActive(moduleId, toolName);
    }

    public int activeToolCount() {
        return activeToolNames().size();
    }

    public List<String> activeToolNames() {
        return local.activeToolNames();
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
                .filter(ConfigField::secret)
                .map(ConfigField::key)
                .collect(Collectors.toSet());
    }

    /** Erzeugt die Tools des Moduls neu und gleicht die Registrierung am MCP-Server ab. */
    private void rebuild(String moduleId) {
        ModuleState s = state(moduleId);
        synchronized (states) {
            if (states.get(moduleId) != s) {
                return; // inzwischen entfernt (Plugin deaktiviert) – keine Tools eines alten Moduls registrieren
            }
            local.rebuild(s.module, s.settings, callListeners());
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

        ModuleState(ToolModule module, ModuleSettings settings) {
            this.module = module;
            this.settings = settings;
        }
    }
}
