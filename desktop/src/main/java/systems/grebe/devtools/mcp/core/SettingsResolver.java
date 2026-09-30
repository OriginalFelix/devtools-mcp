package systems.grebe.devtools.mcp.core;

import systems.grebe.devtools.mcp.config.ModuleSettings;

/**
 * Liefert die wirksamen Einstellungen eines Moduls: die lokalen ({@code settings.json}), bei Anbindung an einen
 * Team-Server überlagert von dessen Vorgaben (Global → Benutzer → Profil) und den Projekten des Benutzers. Ohne
 * Implementierung gelten die lokalen unverändert.
 */
public interface SettingsResolver {

    /** Keine Überlagerung. */
    SettingsResolver LOCAL_ONLY = (module, local) -> local;

    /**
     * @param local die lokalen Einstellungen ({@code settings.json})
     * @return die wirksamen Einstellungen
     */
    ModuleSettings effective(ToolModule module, ModuleSettings local);
}
