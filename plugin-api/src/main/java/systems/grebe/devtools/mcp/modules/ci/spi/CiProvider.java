package systems.grebe.devtools.mcp.modules.ci.spi;

import java.util.List;

import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.ServiceProvider;
import systems.grebe.devtools.mcp.modules.ticket.spi.ProviderSettings;

/**
 * Service-Provider-Schnittstelle für CI/CD-Systeme (Jenkins, GitLab CI/CD, GitHub Actions, …): Builds bzw. Pipelines
 * auflisten, Status und Log lesen, starten, abbrechen und wiederholen.
 *
 * <p>Implementierungen werden über {@link java.util.ServiceLoader} gefunden – in der App und in Plugin-Jars (siehe
 * {@link ServiceProvider}). Ein neues System braucht nur eine Klasse mit öffentlichem No-Arg-Konstruktor und einen
 * Eintrag in {@code META-INF/services/systems.grebe.devtools.mcp.modules.ci.spi.CiProvider}. Das Modul CI/CD erzeugt
 * daraus automatisch Konfigurationsfelder (Präfix {@code <id>.}) und einen Aktivierungsschalter und bietet das System
 * in allen {@code ci_*}-Tools über den Parameter {@code provider} an. Einstellungen und HTTP-Client
 * ({@link systems.grebe.devtools.mcp.modules.ticket.spi.HttpJson}) teilt es mit Ticket- und Git-Server-Providern.
 */
public interface CiProvider extends ServiceProvider {

    /** Stabile technische ID (Kleinbuchstaben), z.B. {@code jenkins}. Wird als Parameter {@code provider} verwendet. */
    String id();

    /** Anzeigename in der UI. */
    String displayName();

    /** Systemspezifische Einstellungen (Schlüssel ohne Präfix, z.B. {@code baseUrl}, {@code token}). */
    default List<ConfigField> configFields() {
        return List.of();
    }

    /** Reihenfolge bei der automatischen Auswahl – kleinere Werte zuerst. */
    default int priority() {
        return 100;
    }

    /** Was als Projekt angegeben wird, z.B. „owner/repo“ oder „Ordner/Job“. Erscheint in UI und {@code ci_providers}. */
    default String projectHelp() {
        return "Projekt";
    }

    /** Format einer Build-Angabe, z.B. „owner/repo#123456, Lauf-ID oder URL“. */
    default String buildHelp() {
        return "Nummer oder URL";
    }

    /**
     * Erzeugt die Anbindung für die übergebenen Einstellungen. Darf weder blockieren (keine Verbindungsprüfung) noch
     * bei unvollständiger Konfiguration werfen – das meldet erst der Aufruf.
     */
    CiSystem create(ProviderSettings settings);
}
