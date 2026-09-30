package systems.grebe.devtools.mcp.config;

/**
 * Allgemeine Server-/App-Einstellungen.
 *
 * @param port            HTTP-Port des MCP-Servers (wirksam nach Neustart)
 * @param authToken       optionales Bearer-Token; leer = kein Schutz (Server lauscht nur auf 127.0.0.1)
 * @param closeToTray     Fenster schließen minimiert in den System-Tray statt die App zu beenden
 * @param startMinimized  beim Start direkt im Tray starten
 */
public record ServerSettings(int port, String authToken, boolean closeToTray, boolean startMinimized) {

    public static final int DEFAULT_PORT = 8765;

    public static ServerSettings defaults() {
        return new ServerSettings(DEFAULT_PORT, "", true, false);
    }

    public ServerSettings {
        if (port <= 0 || port > 65535) {
            port = DEFAULT_PORT;
        }
        authToken = authToken == null ? "" : authToken.trim();
    }

    public boolean authEnabled() {
        return !authToken.isEmpty();
    }
}
