package systems.grebe.devtools.mcp.backend.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import com.arcadedb.ContextConfiguration;
import com.arcadedb.GlobalConfiguration;
import com.arcadedb.server.ArcadeDBServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Dieselben Prüfungen wie {@link GraphStorageTest}, aber mit externer ArcadeDB ({@code devtools.graph.mode=remote}):
 * ein ArcadeDB-Server in derselben JVM, angesprochen über HTTP wie ein entfernter. Jeder Test bekommt eine eigene
 * Datenbank, die die Ablage selbst anlegt.
 */
class RemoteGraphStorageTest extends GraphStorageTest {

    private static final String PASSWORD = "graph-test-passwort";
    private static final AtomicInteger DATABASES = new AtomicInteger();

    @TempDir
    static Path serverDir;

    static ArcadeDBServer server;
    static int port;

    private String database;

    @BeforeAll
    static void startServer() throws Exception {
        // wie GraphStorage: Protokoll über SLF4J statt in ./log – der Server lädt ArcadeDB hier zuerst
        System.setProperty("arcadedb.log.impl", "slf4j");
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        ContextConfiguration cfg = new ContextConfiguration();
        cfg.setValue(GlobalConfiguration.SERVER_ROOT_PATH, serverDir.toString());
        cfg.setValue(GlobalConfiguration.SERVER_DATABASE_DIRECTORY, serverDir.resolve("databases").toString());
        cfg.setValue(GlobalConfiguration.SERVER_ROOT_PASSWORD, PASSWORD);
        cfg.setValue(GlobalConfiguration.SERVER_HTTP_INCOMING_HOST, "127.0.0.1");
        cfg.setValue(GlobalConfiguration.SERVER_HTTP_INCOMING_PORT, String.valueOf(port));
        server = new ArcadeDBServer(cfg);
        server.start();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    @Override
    protected GraphStorage create() {
        if (database == null) {
            database = "graphs" + DATABASES.incrementAndGet();
        }
        return new GraphStorage(GraphStorage.Settings.remote("127.0.0.1", port, database, "root", PASSWORD));
    }

    @Test
    void createsTheDatabaseAndExplainsConnectionErrors() {
        assertThat(storage.check()).startsWith("ArcadeDB 127.0.0.1:" + port + "/" + database);
        assertThat(server.getDatabaseNames()).contains(database);

        try (GraphStorage wrongPassword = new GraphStorage(GraphStorage.Settings.remote("127.0.0.1", port, database,
                "root", "falsch"))) {
            assertThatThrownBy(wrongPassword::check).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ist nicht verfügbar").hasMessageContaining("devtools.graph.*");
        }
        try (GraphStorage noServer = new GraphStorage(GraphStorage.Settings.remote("127.0.0.1", 1, database, "root",
                PASSWORD))) {
            assertThatThrownBy(() -> noServer.reader(MAIN)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ArcadeDB 127.0.0.1:1/" + database + " ist nicht verfügbar");
        }
    }
}
