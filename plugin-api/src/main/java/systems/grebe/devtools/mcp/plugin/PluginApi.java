package systems.grebe.devtools.mcp.plugin;

/** Versionsstand der Plugin-API. */
public final class PluginApi {

    /**
     * Wird erhöht, wenn die API um Methoden wächst, die ältere Apps nicht kennen. Plugins mit höherer
     * {@code api-version} als diese werden abgewiesen, ältere laufen weiter.
     *
     * <ul>
     *   <li>1 – Plugins, Module, Provider-SPIs (Tickets, Chat, Git-Server, Container)</li>
     *   <li>2 – {@code DatabaseConnectionProvider} (Verbindungen des JDBC-Moduls) und {@code ProjectProvider}
     *   (freigegebene Projektverzeichnisse) für Plugins</li>
     *   <li>3 – {@code MailAccountProvider} (E-Mail-Konten des Mail-Moduls) für Plugins; {@code TicketSystem}:
     *   Verknüpfungen ({@code linkTypes}, {@code link}, {@code unlink}, {@code LinkType}, {@code pickLinkType},
     *   {@code pickLink}) und {@code create(project, ticket, fields)}</li>
     *   <li>4 – Hilfen für Plugins: {@code HttpJson.sharedClient()}, {@code HttpJson.basicAuth(user, secret)} und
     *   {@code HttpJson.abbreviate(text)}; {@code DirectoryEntry} (Zeile einer Verzeichnisliste)</li>
     * </ul>
     */
    public static final int VERSION = 4;

    private PluginApi() {
    }
}
