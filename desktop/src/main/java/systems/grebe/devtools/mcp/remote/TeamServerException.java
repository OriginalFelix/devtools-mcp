package systems.grebe.devtools.mcp.remote;

/**
 * Fehler bei der Verbindung zum Team-Server.
 *
 * @see #permanent()
 */
public class TeamServerException extends RuntimeException {

    private final boolean permanent;

    public TeamServerException(String message, Throwable cause, boolean permanent) {
        super(message, cause);
        this.permanent = permanent;
    }

    /**
     * {@code true}: Wiederholen hilft nicht (Token ungültig, falsche Adresse, Anfrage abgelehnt); {@code false}: Server
     * gerade nicht erreichbar – die App arbeitet mit dem letzten Stand weiter.
     */
    public boolean permanent() {
        return permanent;
    }
}
