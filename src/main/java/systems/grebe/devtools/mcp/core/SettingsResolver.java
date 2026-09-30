package systems.grebe.devtools.mcp.core;

import systems.grebe.devtools.mcp.config.ModuleSettings;

/**
 * Liefert die für einen Scope geltenden Einstellungen eines Moduls: die globalen Einstellungen, überlagert von denen
 * des Benutzers und seines aktiven Profils. Ohne Implementierung (Einzelplatz) gelten die globalen unverändert.
 */
public interface SettingsResolver {

    /** Keine Überlagerung. */
    SettingsResolver GLOBAL_ONLY = (scope, module, global) -> global;

    /**
     * @param global die globalen Einstellungen ({@code settings.json})
     * @return die wirksamen Einstellungen; für {@link ToolScope#LOCAL} immer {@code global}
     */
    ModuleSettings effective(ToolScope scope, ToolModule module, ModuleSettings global);
}
