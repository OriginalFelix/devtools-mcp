package systems.grebe.devtools.mcp.modules.graph;

import java.nio.file.Path;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import systems.grebe.devtools.mcp.backend.graph.GraphStorage;
import systems.grebe.devtools.mcp.config.SettingsStore;
import systems.grebe.devtools.mcp.remote.BackendConnection;
import systems.grebe.devtools.mcp.remote.BackendGraphs;
import systems.grebe.devtools.mcp.remote.GraphQlGraphProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Graph-Storage über die GraphQL-API des Backends – so arbeitet die App mit einem Team-Server. Hier gegen das
 * eingebettete Backend: dieselben Antworten wie die Datei-Ablage, eigener Bereich je Benutzer, und im Local-Mode
 * greift das Modul direkt auf die Ablage zu. Der Kontext wird danach geschlossen – sonst hielte die eingebettete
 * Datenbank ihre Dateien im temporären Ordner offen.
 */
@DirtiesContext
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"devtools.local-user.email=local@example.com", "devtools.login.username=tester",
                "devtools.login.password=tester-passwort"})
class GraphQlGraphProviderTest {

    @TempDir
    static Path home;

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        SettingsStore settingsStore() {
            return new SettingsStore(home);
        }
    }

    @Autowired
    BackendConnection backend;

    @Autowired
    BackendGraphs graphs;

    @Autowired
    GraphStorage storage;

    @Test
    void graphQlAnswersLikeTheFileStorageAndKeepsUsersApart(@TempDir Path project) throws Exception {
        assertThat(backend.signedIn()).isTrue();
        assertThat(storage.describe()).contains("ArcadeDB eingebettet", home.toString());
        // beim Start des Backends gestartet, nicht erst beim ersten Zugriff
        long end = System.nanoTime() + 20_000_000_000L;
        while (!storage.status().startsWith("läuft:") && System.nanoTime() < end) {
            Thread.sleep(50);
        }
        assertThat(storage.status()).startsWith("läuft: ArcadeDB eingebettet");
        DatabaseGraphStorageTest.init(project);
        try (Git git = Git.init().setDirectory(project.toFile()).setInitialBranch("main").call()) {
            git.add().addFilepattern(".").call();
            git.commit().setMessage("init").setSign(false).call();
        }
        GraphQlGraphProvider api = new GraphQlGraphProvider(backend);
        assertThat(api.check()).contains("ArcadeDB eingebettet", "über http://127.0.0.1:");

        String built = DatabaseGraphStorageTest.compareWithFileStorage(project, () -> api);
        assertThat(built).contains("GraphBranch user:");

        String root = project.toAbsolutePath().normalize().toString();
        assertThat(api.branches(root)).extracting(GraphProvider.Stored::branch).containsExactly("main");
        // Local-Mode: das Modul nutzt die Ablage direkt, ohne GraphQL – Bereich des Benutzers der API ist getrennt
        assertThat(backend.embedded()).isTrue();
        assertThat(graphs.describe()).isEqualTo(storage.describe());
        assertThat(graphs.branches(root)).isEmpty();
        assertThat(api.delete(root, "main")).isTrue();
        assertThat(api.branches(root)).isEmpty();
    }
}
