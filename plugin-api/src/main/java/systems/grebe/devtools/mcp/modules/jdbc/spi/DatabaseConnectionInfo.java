package systems.grebe.devtools.mcp.modules.jdbc.spi;

/**
 * Eine Verbindung des JDBC-Moduls, wie sie {@link DatabaseConnectionProvider#connections()} liefert – ohne Passwort.
 *
 * @param name        eindeutiger Name, z.B. {@code crm-test}
 * @param url         JDBC-URL mit maskierten Zugangsdaten
 * @param username    Benutzer; leer, wenn keiner angegeben ist
 * @param description Beschreibung aus den Einstellungen; leer, wenn keine
 * @param product     zuletzt gesehenes Datenbankprodukt mit Version, {@code null} vor der ersten Verbindung
 * @param readable    ob {@link DatabaseConnectionProvider#read} auf dieser Verbindung erlaubt ist
 */
public record DatabaseConnectionInfo(String name, String url, String username, String description, String product,
                                     boolean readable) {
}
