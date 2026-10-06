package systems.grebe.devtools.mcp.plugin;

/** Ungültiges Plugin (Beschreibung, Klasse, API-Version, Abhängigkeiten). Die Meldung ist für die UI gedacht. */
public class InvalidPluginException extends RuntimeException {

    public InvalidPluginException(String message) {
        super(message);
    }

    public InvalidPluginException(String message, Throwable cause) {
        super(message, cause);
    }
}
