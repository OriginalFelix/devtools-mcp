package systems.grebe.devtools.mcp.modules.dolt;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;

import systems.grebe.devtools.mcp.core.Text;

/**
 * Eine Datenbank, deren Branch dem Git-Branch eines Arbeitsverzeichnisses folgt. Das Passwort verlässt das Modul nie –
 * weder in Tool-Ausgaben noch in {@link #toString()}.
 *
 * @param repository Git-Arbeitsverzeichnis (Haupt-Repository oder Worktree), dessen Branch maßgeblich ist
 * @param location   Dolt/Doltgres: {@code host[:port]/datenbank} oder eine JDBC-URL; Doltlite: Pfad der Datei
 * @param baseBranch Startpunkt neuer Datenbank-Branches; leer = der Branch, auf dem die Datenbank gerade steht
 */
public record DoltDatabase(String name, Kind kind, String repository, String location, String username,
                           String password, String baseBranch) {

    static final String NAME = "name";
    static final String KIND = "kind";
    static final String REPOSITORY = "repository";
    static final String LOCATION = "location";
    static final String USERNAME = "username";
    static final String PASSWORD = "password";
    static final String BASE_BRANCH = "baseBranch";

    /** Art der Datenbank: Server mit MySQL- oder PostgreSQL-Protokoll oder eingebettete Datei. */
    public enum Kind {
        DOLT("dolt", "Dolt", 3306),
        DOLTGRES("doltgres", "Doltgres", 5432),
        DOLTLITE("doltlite", "Doltlite", 0);

        final String key;
        final String label;
        final int defaultPort;

        Kind(String key, String label, int defaultPort) {
            this.key = key;
            this.label = label;
            this.defaultPort = defaultPort;
        }

        static Kind of(String value) {
            String v = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
            return Arrays.stream(values()).filter(k -> k.key.equals(v)).findFirst().orElse(DOLT);
        }

        static String[] keys() {
            return Arrays.stream(values()).map(k -> k.key).toArray(String[]::new);
        }
    }

    /** Adresse eines Dolt-/Doltgres-Servers; {@code params} ohne führendes {@code ?}. */
    record Server(String host, int port, String database, String params) {
    }

    static DoltDatabase of(Map<String, String> r) {
        return new DoltDatabase(value(r, NAME), Kind.of(r.get(KIND)), value(r, REPOSITORY), value(r, LOCATION),
                value(r, USERNAME), blankToNull(r.get(PASSWORD)), value(r, BASE_BRANCH));
    }

    /** Arbeitsverzeichnis als Pfad; wirft mit verständlicher Meldung. */
    Path repositoryPath() {
        if (repository.isEmpty()) {
            throw new IllegalStateException("Datenbank '" + name + "': kein Git-Arbeitsverzeichnis eingetragen.");
        }
        try {
            return Path.of(repository).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new IllegalStateException("Datenbank '" + name + "': ungültiger Pfad " + repository);
        }
    }

    /** Doltlite-Datei als Pfad. */
    Path file() {
        try {
            return Path.of(location).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new IllegalStateException("Datenbank '" + name + "': ungültiger Pfad " + location);
        }
    }

    /**
     * Server-Adresse aus {@code host[:port]/datenbank[?parameter]}, auch mit Schema davor
     * ({@code jdbc:mysql://…}, {@code postgresql://…}). Ein Branch-Anteil ({@code datenbank/branch}) ist nicht erlaubt –
     * welcher Branch gilt, bestimmt gerade dieses Modul.
     */
    Server server() {
        String s = location.strip();
        int scheme = s.indexOf("://");
        if (scheme >= 0) {
            s = s.substring(scheme + 3);
        }
        String params = "";
        int q = s.indexOf('?');
        if (q >= 0) {
            params = s.substring(q + 1);
            s = s.substring(0, q);
        }
        int at = s.lastIndexOf('@');
        if (at >= 0) {
            s = s.substring(at + 1); // benutzer:passwort@ gehört in die eigenen Felder
        }
        int slash = s.indexOf('/');
        String hostPort = slash < 0 ? s : s.substring(0, slash);
        String db = slash < 0 ? "" : URLDecoder.decode(s.substring(slash + 1), StandardCharsets.UTF_8);
        if (db.endsWith("/")) {
            db = db.substring(0, db.length() - 1);
        }
        if (hostPort.isBlank() || db.isBlank()) {
            throw new IllegalStateException("Datenbank '" + name + "': Ort '" + Text.maskCredentials(location)
                    + "' – erwartet host[:port]/datenbank, z.B. localhost:" + kind.defaultPort + "/app.");
        }
        if (db.contains("/")) {
            throw new IllegalStateException("Datenbank '" + name + "': im Ort steht ein Branch (" + db + ") – nur den "
                    + "Datenbanknamen angeben, den Branch stellt das Modul ein.");
        }
        String host = hostPort;
        int port = kind.defaultPort;
        int colon = hostPort.lastIndexOf(':');
        if (colon > 0 && !hostPort.endsWith("]")) {
            host = hostPort.substring(0, colon);
            try {
                port = Integer.parseInt(hostPort.substring(colon + 1));
            } catch (NumberFormatException e) {
                throw new IllegalStateException("Datenbank '" + name + "': ungültiger Port in '" + hostPort + "'.");
            }
        }
        return new Server(host, port, db, params);
    }

    /** Ort ohne Zugangsdaten für Ausgaben. */
    String safeLocation() {
        return Text.maskCredentials(location);
    }

    @Override
    public String toString() {
        return name + " (" + kind.label + ", " + safeLocation() + (username.isEmpty() ? "" : ", " + username) + ")";
    }

    private static String value(Map<String, String> r, String key) {
        String v = r.get(key);
        return v == null ? "" : v.strip();
    }

    private static String blankToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
