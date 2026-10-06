package systems.grebe.devtools.mcp.modules.ticket.spi;

import java.util.List;

import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.ServiceProvider;

/**
 * Service-Provider-Schnittstelle für Ticket-Systeme (Jira, GitHub, GitLab, YouTrack, OpenProject, …).
 *
 * <p>Implementierungen werden über {@link java.util.ServiceLoader} gefunden – in der App und in Plugin-Jars (siehe
 * {@link ServiceProvider}). Ein neues System benötigt nur eine Klasse
 * mit öffentlichem No-Arg-Konstruktor und einen Eintrag in
 * {@code META-INF/services/systems.grebe.devtools.mcp.modules.ticket.spi.TicketProvider}. Das Ticket-Modul erzeugt
 * daraus automatisch Konfigurationsfelder (Präfix {@code <id>.}), einen Aktivierungsschalter und ein Standardprojekt
 * und bietet das System in allen {@code ticket_*}-Tools über den Parameter {@code provider} an.
 */
public interface TicketProvider extends ServiceProvider {

    /** Stabile technische ID (Kleinbuchstaben), z.B. {@code jira}. Wird als Parameter {@code provider} verwendet. */
    String id();

    /** Anzeigename in der UI. */
    String displayName();

    /** Systemspezifische Einstellungen (Schlüssel ohne Präfix, z.B. {@code baseUrl}, {@code token}). */
    default List<ConfigField> configFields() {
        return List.of();
    }

    /** Reihenfolge bei der automatischen Auswahl ({@code auto}) – kleinere Werte zuerst. */
    default int priority() {
        return 100;
    }

    /** Was als Projekt angegeben wird, z.B. „Projektschlüssel, z.B. ABC“. Erscheint in UI und {@code ticket_providers}. */
    default String projectHelp() {
        return "Projekt";
    }

    /** Format eines Ticket-Schlüssels, z.B. „ABC-123 oder Browse-URL“. */
    default String keyHelp() {
        return "Ticket-Schlüssel";
    }

    /** Syntax des Parameters {@code query} von {@code ticket_search} (JQL, Suchsyntax …). */
    default String queryHelp() {
        return "keine eigene Abfragesprache";
    }

    /**
     * Erzeugt ein Ticket-System für die übergebenen Einstellungen. Darf weder blockieren (keine Verbindungsprüfung)
     * noch bei unvollständiger Konfiguration werfen – das meldet erst der Aufruf.
     */
    TicketSystem create(ProviderSettings settings);
}
