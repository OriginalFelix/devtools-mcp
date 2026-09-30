package systems.grebe.devtools.mcp.api;

import java.util.Map;

/**
 * Vorgaben des Servers für alle Module im aktiven Profil des Benutzers ({@code GET /api/settings}).
 *
 * @param modules Modul-ID → Vorgaben; Module ohne Vorgaben fehlen
 */
public record SettingsSnapshot(long profileId, String profileName, Map<String, ModuleOverlay> modules) {

    public SettingsSnapshot {
        modules = modules == null ? Map.of() : Map.copyOf(modules);
    }

    public ModuleOverlay module(String moduleId) {
        return modules.getOrDefault(moduleId, ModuleOverlay.NONE);
    }
}
