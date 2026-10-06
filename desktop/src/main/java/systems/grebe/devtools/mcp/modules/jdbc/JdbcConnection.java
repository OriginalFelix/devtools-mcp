package systems.grebe.devtools.mcp.modules.jdbc;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import systems.grebe.devtools.mcp.core.Text;
import systems.grebe.devtools.mcp.modules.jdbc.SqlStatements.Kind;

/**
 * Eine konfigurierte JDBC-Verbindung. Das Passwort verlässt das Modul nie – weder in Tool-Ausgaben noch in
 * {@link #toString()}; die URL erscheint nur mit maskierten Zugangsdaten ({@link #safeUrl()}).
 *
 * @param access      höchstens erlaubter Zugriff, zusätzlich zu den Schaltern des Moduls
 * @param driver      leer, Maven-Koordinaten oder Pfade zu JAR-Dateien/Verzeichnissen (siehe {@link JdbcDrivers})
 * @param driverClass Treiberklasse, falls sich der Treiber nicht per {@code META-INF/services} anmeldet; sonst leer
 */
public record JdbcConnection(String name, String url, String username, String password, Access access, String driver,
                             String driverClass, String description) {

    static final String NAME = "name";
    static final String URL = "url";
    static final String USERNAME = "username";
    static final String PASSWORD = "password";
    static final String ACCESS = "access";
    static final String DRIVER = "driver";
    static final String DRIVER_CLASS = "driverClass";
    static final String DESCRIPTION = "description";

    /** Höchstens erlaubter Zugriff einer Verbindung – unabhängig davon, was die Schalter des Moduls freigeben. */
    public enum Access {
        ALL("all", "alles, was die Schalter des Moduls erlauben", EnumSet.allOf(Kind.class)),
        WRITE("write", "lesen und Datensätze ändern", EnumSet.of(Kind.QUERY, Kind.INSERT, Kind.UPDATE, Kind.DELETE)),
        READ("read", "nur lesen", EnumSet.of(Kind.QUERY));

        final String key;
        final String label;
        private final Set<Kind> kinds;

        Access(String key, String label, Set<Kind> kinds) {
            this.key = key;
            this.label = label;
            this.kinds = kinds;
        }

        boolean permits(Kind kind) {
            return kinds.contains(kind);
        }

        static Access of(String value) {
            String v = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
            for (Access a : values()) {
                if (a.key.equals(v)) {
                    return a;
                }
            }
            return ALL;
        }

        static String[] keys() {
            return java.util.Arrays.stream(values()).map(a -> a.key).toArray(String[]::new);
        }
    }

    static JdbcConnection of(Map<String, String> r) {
        return new JdbcConnection(value(r, NAME), value(r, URL), value(r, USERNAME), blankToNull(r.get(PASSWORD)),
                Access.of(r.get(ACCESS)), value(r, DRIVER), value(r, DRIVER_CLASS), value(r, DESCRIPTION));
    }

    /** URL ohne Zugangsdaten (Passwort-Parameter, {@code benutzer:passwort@}) für Ausgaben. */
    public String safeUrl() {
        return Text.maskCredentials(url);
    }

    /** Subprotokoll der URL ({@code jdbc:postgresql://…} → {@code postgresql}); leer, wenn keine JDBC-URL. */
    String subprotocol() {
        String u = url.toLowerCase(Locale.ROOT);
        if (!u.startsWith("jdbc:")) {
            return "";
        }
        int end = u.indexOf(':', 5);
        return end < 0 ? "" : u.substring(5, end);
    }

    @Override
    public String toString() {
        return name + " (" + safeUrl() + (username.isEmpty() ? "" : ", " + username) + ", " + access.key + ")";
    }

    private static String value(Map<String, String> r, String key) {
        String v = r.get(key);
        return v == null ? "" : v.strip();
    }

    private static String blankToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
