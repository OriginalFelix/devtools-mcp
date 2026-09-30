package systems.grebe.devtools.mcp.core;

import systems.grebe.devtools.mcp.config.ModuleSettings;

/**
 * Woher die wirksamen Einstellungen eines Moduls kommen und wohin Änderungen gehen: im Normalfall das Backend
 * (eingebettet oder Team-Server; Ebenen Global → Benutzer → Profil), solange es noch keinen Stand geliefert hat die
 * lokalen Einstellungen ({@code settings.json}). Ohne Implementierung gelten nur die lokalen.
 */
public interface SettingsResolver {

    /** Keine Überlagerung. */
    SettingsResolver LOCAL_ONLY = (module, local) -> local;

    /**
     * @param local die lokalen Einstellungen ({@code settings.json}) bzw. die Vorbelegung des Moduls
     * @return die wirksamen Einstellungen
     */
    ModuleSettings effective(ToolModule module, ModuleSettings local);

    /** Ob Änderungen über {@link #save} ans Backend gehen statt in {@code settings.json}. */
    default boolean handlesWrites() {
        return false;
    }

    /**
     * Speichert eine Änderung (z.B. aus dem Modul-Formular). {@code before}/{@code after} sind wirksame Einstellungen;
     * gespeichert wird nur, was sich geändert hat. Die Tools baut die Registry danach über
     * {@link ToolRegistry#refreshAll()} neu auf.
     */
    default void save(ToolModule module, ModuleSettings before, ModuleSettings after) {
        throw new UnsupportedOperationException();
    }

    /** Vom Administrator gesperrte Schlüssel des Moduls (Feld, {@code @enabled}, {@code @tools}). */
    default java.util.Set<String> locked(ToolModule module) {
        return java.util.Set.of();
    }

    /** Wohin Änderungen gehen – für die Anzeige, z.B. „Profil „Work“ (eingebettet)“. */
    default String target() {
        return "settings.json";
    }
}
