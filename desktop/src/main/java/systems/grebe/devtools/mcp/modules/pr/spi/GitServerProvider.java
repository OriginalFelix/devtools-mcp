package systems.grebe.devtools.mcp.modules.pr.spi;

import java.util.List;

import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.modules.ticket.spi.ProviderSettings;

/**
 * Service-Provider-Schnittstelle für Git-Server mit Pull/Merge Requests (GitHub, GitLab, Bitbucket, …).
 *
 * <p>Implementierungen werden über {@link java.util.ServiceLoader} gefunden. Ein neuer Server braucht nur eine Klasse
 * mit öffentlichem No-Arg-Konstruktor und einen Eintrag in
 * {@code META-INF/services/systems.grebe.devtools.mcp.modules.pr.spi.GitServerProvider}. Das Modul Pull Requests
 * erzeugt daraus automatisch Konfigurationsfelder (Präfix {@code <id>.}) und einen Aktivierungsschalter und bietet den
 * Server in allen {@code pr_*}-Tools über den Parameter {@code provider} an. Einstellungen und HTTP-Client
 * ({@link systems.grebe.devtools.mcp.modules.ticket.spi.HttpJson}) teilen sich Git-Server- und Ticket-Provider.
 */
public interface GitServerProvider {

    /** Stabile technische ID (Kleinbuchstaben), z.B. {@code github}. Wird als Parameter {@code provider} verwendet. */
    String id();

    /** Anzeigename in der UI. */
    String displayName();

    /** Serverspezifische Einstellungen (Schlüssel ohne Präfix, z.B. {@code baseUrl}, {@code token}). */
    default List<ConfigField> configFields() {
        return List.of();
    }

    /** Reihenfolge bei der automatischen Auswahl – kleinere Werte zuerst. */
    default int priority() {
        return 100;
    }

    /** Was als Repository angegeben wird, z.B. „owner/repo“. Erscheint in UI und {@code pr_providers}. */
    default String projectHelp() {
        return "Repository";
    }

    /** Format einer Pull-Request-Angabe, z.B. „owner/repo#12, #12 oder URL“. */
    default String keyHelp() {
        return "Nummer oder URL";
    }

    /**
     * Erzeugt die Anbindung für die übergebenen Einstellungen. Darf weder blockieren (keine Verbindungsprüfung) noch
     * bei unvollständiger Konfiguration werfen – das meldet erst der Aufruf.
     */
    GitServer create(ProviderSettings settings);
}
