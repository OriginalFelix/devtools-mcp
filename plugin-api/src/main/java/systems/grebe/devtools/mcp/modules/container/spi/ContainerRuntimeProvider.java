package systems.grebe.devtools.mcp.modules.container.spi;

import java.util.List;

import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.ServiceProvider;

/**
 * Service-Provider-Schnittstelle für Container-Laufzeiten (Docker, Podman, …).
 *
 * <p>Implementierungen werden über {@link java.util.ServiceLoader} gefunden – in der App und in Plugin-Jars (siehe
 * {@link ServiceProvider}). Eine neue Laufzeit benötigt nur
 * eine Klasse mit öffentlichem No-Arg-Konstruktor und einen Eintrag in
 * {@code META-INF/services/systems.grebe.devtools.mcp.modules.container.spi.ContainerRuntimeProvider}.
 * Das Container-Modul erzeugt daraus automatisch Konfigurationsfelder (mit Präfix {@code <id>.}),
 * einen Aktivierungsschalter und bietet die Laufzeit in allen {@code container_*}-Tools an.
 */
public interface ContainerRuntimeProvider extends ServiceProvider {

    /** Stabile technische ID (Kleinbuchstaben), z.B. {@code docker}. Wird als Parameter {@code runtime} verwendet. */
    String id();

    /** Anzeigename in der UI. */
    String displayName();

    /**
     * Laufzeitspezifische Einstellungen (Schlüssel ohne Präfix, z.B. {@code binary}). Die UI zeigt sie als
     * {@code <Anzeigename>: <Label>} an.
     */
    default List<ConfigField> configFields() {
        return List.of();
    }

    /** Reihenfolge bei der automatischen Auswahl ({@code auto}) – kleinere Werte zuerst. */
    default int priority() {
        return 100;
    }

    /** Erzeugt eine Laufzeit für die übergebenen Einstellungen. Darf nicht blockieren (keine Verbindungsprüfung). */
    ContainerRuntime create(RuntimeSettings settings);
}
