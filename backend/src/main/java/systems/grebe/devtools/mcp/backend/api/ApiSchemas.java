package systems.grebe.devtools.mcp.backend.api;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import systems.grebe.devtools.mcp.api.ApiVersions;

/**
 * GraphQL-Schemas der angebotenen {@link ApiVersions Protokollversionen}: je Version ein eigener Ordner
 * {@code backend-graphql/v<n>/}. Ein veröffentlichter Ordner wird nicht mehr geändert (geprüft über die Prüfsummen in
 * {@code ApiSchemasTest}); eine Änderung am Protokoll legt den Ordner der nächsten Version als Kopie an, ändert dort,
 * trägt ihn hier ein und hebt {@link ApiVersions#CURRENT}. Die Controller bedienen alle Versionen – was eine neue
 * Version entfernt oder umbenennt, bleibt für die älteren als eigene Methode stehen, bis deren Version hier entfällt.
 *
 * <p>Versioniert ist nur die Schnittstelle: alle Versionen arbeiten auf derselben Datenbank (Core, Skills,
 * Graph-Storage, Dateiablage). Ältere Versionen werden aus dem aktuellen Datenmodell bedient.
 *
 * <p>Die Dateien stehen einzeln da statt über eine Pattern-Suche: die findet im WAR unter WildFly (VFS) nichts.
 */
public final class ApiSchemas {

    /** Schema-Dateien je Version, relativ zu {@code backend-graphql/v<n>/}. */
    private static final NavigableMap<Integer, List<String>> FILES = Collections.unmodifiableNavigableMap(
            new TreeMap<>(Map.of(
                    0, List.of("schema.graphqls", "graph.graphqls"))));

    private ApiSchemas() {
    }

    /** Angebotene Versionen, aufsteigend. */
    public static List<Integer> versions() {
        return List.copyOf(FILES.keySet());
    }

    /** Neueste angebotene Version. */
    public static int current() {
        return FILES.lastKey();
    }

    public static boolean offered(int version) {
        return FILES.containsKey(version);
    }

    /** Schema-Dateien einer Version als Klassenpfad-Ressourcen. */
    public static List<Resource> resources(int version) {
        List<String> files = FILES.get(version);
        if (files == null) {
            throw new IllegalArgumentException("API-Version " + version + " wird nicht angeboten.");
        }
        return files.stream().<Resource>map(f -> new ClassPathResource(folder(version) + f)).toList();
    }

    /** Ordner der Version im Klassenpfad, z.B. {@code backend-graphql/v0/}. */
    public static String folder(int version) {
        return "backend-graphql/v" + version + "/";
    }

    /** Was {@code GET /api/versions} meldet. */
    public static ApiVersions.Info info() {
        return new ApiVersions.Info(current(), versions());
    }
}
