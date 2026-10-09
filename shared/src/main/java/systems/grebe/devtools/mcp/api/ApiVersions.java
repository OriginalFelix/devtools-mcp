package systems.grebe.devtools.mcp.api;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Versionen des Protokolls zwischen Desktop-App und Backend (GraphQL-API und Dateiablage). Jede Änderung am Protokoll
 * ergibt eine neue Version ({@link #CURRENT} + 1); das Backend bietet die älteren weiter an, damit Desktop-Apps älterer
 * Stände mit einem neueren Team-Server arbeiten.
 *
 * <ul>
 *   <li>Version {@code n} liegt unter {@code /api/v<n>/graphql} (HTTP und WebSocket) und {@code /api/v<n>/blobs}.</li>
 *   <li>Die Pfade ohne Version ({@code /graphql}, {@code /blobs}) sind Version 0 – so sprechen Desktop-Apps von vor der
 *       Versionierung.</li>
 *   <li>{@code GET /api/versions} (ohne Anmeldung) nennt die angebotenen Versionen ({@link Info}).</li>
 * </ul>
 */
public final class ApiVersions {

    /** Neueste Version – die spricht die Desktop-App. */
    public static final int CURRENT = 0;
    /** Angebotene Versionen ({@link Info}), ohne Anmeldung. */
    public static final String VERSIONS_PATH = "/api/versions";
    /** Version der Pfade ohne Versionsnummer ({@code /graphql}, {@code /blobs}). */
    public static final int LEGACY = 0;

    private static final Pattern VERSIONED = Pattern.compile("^/api/v(\\d{1,4})(?:/.*)?$");

    private ApiVersions() {
    }

    /** Pfadpräfix der Version, z.B. {@code /api/v1} – davor die Adresse des Backends, dahinter {@code /graphql}. */
    public static String base(int version) {
        return "/api/v" + version;
    }

    /** Version aus dem Pfad einer Anfrage: {@code /api/v3/graphql} → 3, Pfade ohne Version → {@link #LEGACY}. */
    public static int fromPath(String path) {
        Matcher m = VERSIONED.matcher(path == null ? "" : path);
        return m.matches() ? Integer.parseInt(m.group(1)) : LEGACY;
    }

    /**
     * Antwort von {@code GET /api/versions}.
     *
     * @param current  neueste Version des Backends
     * @param versions alle angebotenen Versionen, aufsteigend
     */
    public record Info(int current, List<Integer> versions) {

        public Info {
            versions = versions == null ? List.of() : List.copyOf(versions);
        }
    }

    /**
     * Pfadpräfix, unter dem eine App mit Version {@code wanted} das Backend anspricht.
     *
     * @param offered angebotene Versionen; {@code null} = Backend ohne Versionierung (nur {@link #LEGACY} ohne Präfix)
     * @return z.B. {@code /api/v1}; leer bei einem Backend ohne Versionierung
     * @throws IllegalStateException wenn das Backend die Version nicht (mehr) anbietet – mit Hinweis, was zu
     *                               aktualisieren ist
     */
    public static String negotiate(int wanted, Info offered) {
        if (offered == null) {
            if (wanted == LEGACY) {
                return "";
            }
            throw new IllegalStateException("Das Backend ist zu alt: es kennt nur die API-Version " + LEGACY
                    + ", diese App braucht Version " + wanted + ". Bitte den Team-Server aktualisieren.");
        }
        if (offered.versions().contains(wanted)) {
            return base(wanted);
        }
        if (offered.versions().isEmpty() || wanted > offered.current()) {
            throw new IllegalStateException("Das Backend ist zu alt: es bietet die API-Version(en) "
                    + offered.versions() + ", diese App braucht Version " + wanted
                    + ". Bitte den Team-Server aktualisieren.");
        }
        throw new IllegalStateException("Diese App ist zu alt: sie spricht API-Version " + wanted
                + ", das Backend bietet nur noch " + offered.versions() + ". Bitte die Desktop-App aktualisieren.");
    }
}
