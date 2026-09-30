package systems.grebe.devtools.mcp.config;

import java.util.Map;
import java.util.Set;

/**
 * Persistierter Zustand eines Moduls.
 *
 * @param enabled       Modul aktiv (Tools am MCP-Server registriert)
 * @param disabledTools einzeln deaktivierte Tools (vollständiger, präfixierter Name)
 * @param values        Konfigurationswerte (Geheimnisse hier bereits entschlüsselt)
 */
public record ModuleSettings(boolean enabled, Set<String> disabledTools, Map<String, String> values) {

    public ModuleSettings {
        disabledTools = disabledTools == null ? Set.of() : Set.copyOf(disabledTools);
        values = values == null ? Map.of() : Map.copyOf(values);
    }

    public ModuleSettings withEnabled(boolean value) {
        return new ModuleSettings(value, disabledTools, values);
    }

    public ModuleSettings withDisabledTools(Set<String> value) {
        return new ModuleSettings(enabled, value, values);
    }

    public ModuleSettings withValues(Map<String, String> value) {
        return new ModuleSettings(enabled, disabledTools, value);
    }
}
