package systems.grebe.devtools.mcp.plugin.store;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Ein Maven-Repository, aus dem der Plugin-Store Plugins, deren Bibliotheken und Kataloge lädt.
 *
 * @param id        stabile ID (wie {@code <id>} in einer {@code settings.xml})
 * @param name      Anzeigename
 * @param url       {@code https://…}, {@code http://…} oder {@code file:…} (Maven-2-Layout)
 * @param username  optional, für geschützte Repositories (Nexus, Artifactory, Bitbucket …)
 * @param password  optional; wird verschlüsselt gespeichert
 * @param snapshots SNAPSHOT-Versionen anbieten
 * @param catalog   optional {@code groupId:artifactId} eines Katalog-Artefakts ({@code .yml}, siehe
 *                  {@link PluginCatalog}); ohne Katalog lassen sich Plugins über ihre Koordinaten installieren
 * @param enabled   wird bei Auflösungen verwendet
 */
public record PluginRepository(
        String id,
        String name,
        String url,
        String username,
        String password,
        boolean snapshots,
        String catalog,
        boolean enabled) {

    public static final String CENTRAL_ID = "central";
    public static final String CENTRAL_URL = "https://repo.maven.apache.org/maven2/";

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern GA = Pattern.compile("[^:\\s]+:[^:\\s]+");

    public PluginRepository {
        id = trim(id);
        name = trim(name);
        url = trim(url);
        username = trim(username);
        password = password == null ? "" : password;
        catalog = trim(catalog);
        if (name.isEmpty()) {
            name = id;
        }
    }

    public static PluginRepository central() {
        return new PluginRepository(CENTRAL_ID, "Maven Central", CENTRAL_URL, "", "", false, "", true);
    }

    public boolean hasCredentials() {
        return !username.isEmpty();
    }

    public boolean hasCatalog() {
        return !catalog.isEmpty();
    }

    public PluginRepository withEnabled(boolean value) {
        return new PluginRepository(id, name, url, username, password, snapshots, catalog, value);
    }

    /** Deutschsprachige Fehlermeldungen; leer, wenn die Angaben gültig sind. */
    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        if (!ID.matcher(id).matches()) {
            errors.add("ID ungültig: Buchstaben, Ziffern, '.', '_' und '-' (höchstens 64 Zeichen).");
        }
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            if (!(scheme.equals("https") || scheme.equals("http") || scheme.equals("file"))) {
                errors.add("URL muss mit https://, http:// oder file: beginnen.");
            } else if (!scheme.equals("file") && (uri.getHost() == null || uri.getHost().isBlank())) {
                errors.add("URL enthält keinen Host.");
            }
        } catch (IllegalArgumentException e) {
            errors.add("URL ist ungültig: " + e.getMessage());
        }
        if (!catalog.isEmpty() && !GA.matcher(catalog).matches()) {
            errors.add("Katalog muss die Form groupId:artifactId haben.");
        }
        return errors;
    }

    /** Für Anzeige und Protokoll: niemals das Passwort. */
    @Override
    public String toString() {
        return "PluginRepository[" + id + ", " + url + (hasCredentials() ? ", Benutzer " + username : "") + "]";
    }

    private static String trim(String s) {
        return s == null ? "" : s.strip();
    }
}
