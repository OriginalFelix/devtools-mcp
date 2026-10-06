package systems.grebe.devtools.mcp.modules.chat.spi;

import java.util.List;

import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.ServiceProvider;

/**
 * Service-Provider-Schnittstelle für Chat-Systeme (Matrix, Microsoft Teams, …).
 *
 * <p>Implementierungen werden über {@link java.util.ServiceLoader} gefunden – in der App und in Plugin-Jars (siehe
 * {@link ServiceProvider}). Ein neues System benötigt nur eine Klasse
 * mit öffentlichem No-Arg-Konstruktor und einen Eintrag in
 * {@code META-INF/services/systems.grebe.devtools.mcp.modules.chat.spi.ChatProvider}. Das Chat-Modul erzeugt daraus
 * Konfigurationsfelder (Präfix {@code <id>.}), einen Aktivierungsschalter und eine Standard-Unterhaltung und bietet das
 * System in allen {@code chat_*}-Tools über den Parameter {@code provider} an. Eingang (was ist neu, Warten auf
 * Antworten), Markdown und Ausgabe übernimmt das Modul.
 */
public interface ChatProvider extends ServiceProvider {

    /** Stabile technische ID (Kleinbuchstaben), z.B. {@code matrix}. Wird als Parameter {@code provider} verwendet. */
    String id();

    /** Anzeigename in der UI. */
    String displayName();

    /** Systemspezifische Einstellungen (Schlüssel ohne Präfix, z.B. {@code homeserverUrl}). */
    default List<ConfigField> configFields() {
        return List.of();
    }

    /** Reihenfolge in UI und bei der automatischen Auswahl – kleinere Werte zuerst. */
    default int priority() {
        return 100;
    }

    /** Was als Unterhaltung angegeben wird, z.B. „Raum-ID (!…:server), Alias (#…:server) oder Name“. */
    default String conversationHelp() {
        return "ID oder Name der Unterhaltung";
    }

    /**
     * Ob die Anmeldung interaktiv im Browser erfolgt (z.B. OAuth Device Code, siehe {@link ChatSystem#login}) statt mit
     * Zugangsdaten aus den Einstellungen. Das Modul bietet dann eine Aktion „Anmelden“ in der App und {@code chat_login}.
     */
    default boolean interactiveLogin() {
        return false;
    }

    /**
     * Erzeugt das Chat-System für die übergebenen Einstellungen. Darf weder blockieren (keine Verbindung, keine
     * Anmeldung) noch bei unvollständiger Konfiguration werfen – das meldet erst der Aufruf.
     */
    ChatSystem create(ChatSettings settings);
}
