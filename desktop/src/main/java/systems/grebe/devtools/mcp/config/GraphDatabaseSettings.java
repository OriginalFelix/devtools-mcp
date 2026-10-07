package systems.grebe.devtools.mcp.config;

/**
 * Graph-Datenbank des eingebetteten Backends (Reiter „Backend“): eingebettete ArcadeDB oder ein externer
 * ArcadeDB-Server. Nicht eingestellt gelten die Properties {@code devtools.graph.*} (Standard: eingebettet).
 *
 * @param mode     {@link #EMBEDDED}, {@link #REMOTE} oder leer = nicht eingestellt
 * @param host     ArcadeDB-Server
 * @param port     HTTP-Port des Servers
 * @param database Datenbank auf dem Server (wird bei Bedarf angelegt)
 * @param password verschlüsselt gespeichert
 */
public record GraphDatabaseSettings(String mode, String host, int port, String database, String user,
                                    String password) {

    public static final String EMBEDDED = "embedded";
    public static final String REMOTE = "remote";
    public static final int DEFAULT_PORT = 2480;

    public static GraphDatabaseSettings none() {
        return new GraphDatabaseSettings("", "", DEFAULT_PORT, "", "", "");
    }

    public GraphDatabaseSettings {
        mode = mode == null ? "" : mode.strip();
        host = host == null ? "" : host.strip();
        port = port <= 0 ? DEFAULT_PORT : port;
        database = database == null ? "" : database.strip();
        user = user == null ? "" : user.strip();
        password = password == null ? "" : password;
    }

    /** Im Reiter „Backend“ eingestellt. */
    public boolean configured() {
        return EMBEDDED.equals(mode) || REMOTE.equals(mode);
    }

    public boolean remote() {
        return REMOTE.equals(mode);
    }
}
