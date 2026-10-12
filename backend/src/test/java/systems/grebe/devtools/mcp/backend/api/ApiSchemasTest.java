package systems.grebe.devtools.mcp.backend.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import systems.grebe.devtools.mcp.api.ApiVersions;

/**
 * Veröffentlichte API-Versionen sind eingefroren: ihre Schemas dürfen sich nicht mehr ändern, sonst bricht ein Client
 * dieser Version. Die Prüfsummen stehen in {@value #FROZEN}.
 *
 * <p>Ändert eine Arbeit das Protokoll (Schema oder Dateiablage), bekommt es eine neue Version:
 * <ol>
 *   <li>{@code backend-graphql/v<n>/} nach {@code v<n+1>/} kopieren und dort ändern,</li>
 *   <li>die Version in {@link ApiSchemas} eintragen und {@link ApiVersions#CURRENT} auf {@code n+1} heben,</li>
 *   <li>was die neue Version entfernt oder umbenennt, in den Controllern für die älteren stehen lassen,</li>
 *   <li>die Prüfsumme der neuen Version (steht in der Meldung dieses Tests) in {@value #FROZEN} eintragen.</li>
 * </ol>
 */
class ApiSchemasTest {

    static final String FROZEN = "api-versions.sha256";

    @Test
    void desktopSpeaksTheNewestVersion() {
        assertThat(ApiSchemas.current()).as("ApiVersions.CURRENT = neueste Version in ApiSchemas")
                .isEqualTo(ApiVersions.CURRENT);
        assertThat(ApiSchemas.versions()).contains(ApiVersions.LEGACY).isSorted();
        assertThat(ApiSchemas.info().versions()).isEqualTo(ApiSchemas.versions());
    }

    @Test
    void everyVersionHasAParsableSchema() {
        for (int version : ApiSchemas.versions()) {
            TypeDefinitionRegistry types = new TypeDefinitionRegistry();
            for (Resource r : ApiSchemas.resources(version)) {
                assertThat(r.exists()).as(r.getDescription()).isTrue();
                types.merge(new SchemaParser().parse(read(r)));
            }
            assertThat(types.getType("Query")).as("Version " + version).isPresent();
        }
    }

    @Test
    void publishedVersionsAreFrozen() throws IOException {
        Properties frozen = new Properties();
        try (InputStream in = new ClassPathResource(FROZEN).getInputStream()) {
            frozen.load(in);
        }
        Map<Integer, String> actual = new LinkedHashMap<>();
        ApiSchemas.versions().forEach(v -> actual.put(v, checksum(v)));
        for (Map.Entry<Integer, String> e : actual.entrySet()) {
            String expected = frozen.getProperty(String.valueOf(e.getKey()));
            assertThat(expected).as("Version %d fehlt in %s – eintragen: %d=%s", e.getKey(), FROZEN, e.getKey(),
                    e.getValue()).isNotNull();
            assertThat(e.getValue()).as("""
                    Das Schema der veröffentlichten API-Version %d hat sich geändert. Veröffentlichte Versionen sind \
                    eingefroren – Clients dieser Version brächen. Die Änderung gehört in eine neue Version \
                    (backend-graphql/v%d/, ApiSchemas, ApiVersions.CURRENT, siehe ApiSchemasTest).""",
                    e.getKey(), ApiSchemas.current() + 1).isEqualTo(expected);
        }
        assertThat(frozen.stringPropertyNames()).as("Versionen in %s, die nicht mehr angeboten werden", FROZEN)
                .allMatch(k -> actual.containsKey(Integer.parseInt(k)));
    }

    /** SHA-256 über Namen und Inhalt der Schema-Dateien, Zeilenenden vereinheitlicht (Checkout unter Windows). */
    static String checksum(int version) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (Resource r : ApiSchemas.resources(version)) {
                sha.update((r.getFilename() + "\n").getBytes(StandardCharsets.UTF_8));
                sha.update(read(r).replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String read(Resource r) {
        try {
            return r.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(r.getDescription() + ": " + e.getMessage(), e);
        }
    }
}
