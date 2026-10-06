package systems.grebe.devtools.mcp.core;

import java.util.List;

/**
 * Gemeinsame Basis der austauschbaren Provider (Ticket-Systeme, Chat-Systeme, Git-Server, Container-Laufzeiten).
 * Das jeweilige Modul macht aus jedem Provider eine Gruppe in seinem Formular (Schalter {@code <id>.enabled}, Felder
 * {@code <id>.<feld>}) und bietet ihn in seinen Tools an.
 *
 * <p>Gefunden werden Provider über {@link java.util.ServiceLoader}: eine Klasse mit öffentlichem No-Arg-Konstruktor
 * und eine Zeile in {@code META-INF/services/<voll qualifizierte SPI-Schnittstelle>} – in der App wie in Plugin-Jars.
 * Provider aus Plugins erscheinen, sobald das Plugin aktiv ist, und verschwinden mit ihm; eine ID, die schon ein
 * eingebauter Provider belegt, wird ignoriert.
 */
public interface ServiceProvider {

    /** Stabile technische ID ({@code [a-z][a-z0-9-]*}), Präfix der Einstellungen und Wert des Tool-Parameters. */
    String id();

    /** Anzeigename in der UI. */
    String displayName();

    /** Provider-spezifische Einstellungen (Schlüssel ohne Präfix, z.B. {@code baseUrl}, {@code token}). */
    default List<ConfigField> configFields() {
        return List.of();
    }

    /** Reihenfolge in UI und bei der automatischen Auswahl – kleinere Werte zuerst. */
    default int priority() {
        return 100;
    }
}
