package systems.grebe.devtools.mcp.modules.graph;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.neo4j.driver.AuthToken;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.SessionConfig;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.data.neo4j.core.DatabaseSelectionProvider;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.data.neo4j.core.Neo4jTemplate;
import org.springframework.data.neo4j.core.mapping.Neo4jMappingContext;
import org.springframework.data.neo4j.core.transaction.Neo4jTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import systems.grebe.devtools.mcp.core.ModuleConfig;

/**
 * Verbindung zur Neo4j-Datenbank des Graph-Moduls: Treiber plus Spring-Data-Neo4j-Bausteine (Mapping-Kontext,
 * {@link Neo4jTemplate} für die Entities, {@link Neo4jClient} für Bulk-Cypher, Transaktionsmanager).
 *
 * <p>Wird aus den Modul-Einstellungen gebaut – nicht als Spring-Bean –, damit Änderungen an der Verbindung sofort
 * gelten. Je Verbindungsdaten gibt es eine Instanz; eine geänderte Verbindung schließt die vorige.
 */
final class Neo4jConnection implements AutoCloseable {

    record Settings(String uri, String user, String password, String database) {

        static Settings from(ModuleConfig config) {
            return new Settings(config.getString(GraphModule.NEO4J_URI, "bolt://localhost:7687"),
                    config.getString(GraphModule.NEO4J_USER, "neo4j"), config.getString(GraphModule.NEO4J_PASSWORD, ""),
                    config.getString(GraphModule.NEO4J_DATABASE, null));
        }

        @Override
        public String toString() {
            // Passwort bewusst nicht ausgeben
            return uri + (database == null ? "" : " (Datenbank " + database + ")");
        }
    }

    private static final Map<Settings, Neo4jConnection> OPEN = new ConcurrentHashMap<>();

    static {
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(Neo4jConnection::closeAll));
    }

    final Settings settings;
    final Driver driver;
    final Neo4jClient client;
    final Neo4jTemplate template;
    final TransactionTemplate tx;
    private volatile boolean schemaReady;

    private Neo4jConnection(Settings s) {
        this.settings = s;
        AuthToken auth = s.password().isEmpty() ? AuthTokens.none() : AuthTokens.basic(s.user(), s.password());
        this.driver = GraphDatabase.driver(s.uri(), auth, Config.builder()
                .withConnectionTimeout(5, TimeUnit.SECONDS)
                .withMaxConnectionPoolSize(8)
                .build());
        DatabaseSelectionProvider db = s.database() == null ? DatabaseSelectionProvider.getDefaultSelectionProvider()
                : DatabaseSelectionProvider.createStaticDatabaseSelectionProvider(s.database());
        this.client = Neo4jClient.with(driver).withDatabaseSelectionProvider(db).build();
        Neo4jMappingContext mapping = Neo4jMappingContext.builder().build();
        mapping.setInitialEntitySet(Set.of(GraphProjectEntity.class, GraphBranchEntity.class));
        mapping.initialize();
        Neo4jTransactionManager txManager = Neo4jTransactionManager.with(driver).withDatabaseSelectionProvider(db).build();
        this.template = new Neo4jTemplate(client, mapping, txManager);
        // Außerhalb eines Spring-Kontexts: Callbacks/Projektionen brauchen trotzdem Classloader und BeanFactory
        this.template.setBeanClassLoader(Neo4jConnection.class.getClassLoader());
        this.template.setBeanFactory(new DefaultListableBeanFactory());
        this.tx = new TransactionTemplate(txManager);
    }

    /** Offene Verbindung zu diesen Einstellungen; andere offene Verbindungen werden geschlossen. */
    static Neo4jConnection get(Settings s) {
        Neo4jConnection c = OPEN.get(s);
        if (c != null) {
            return c;
        }
        synchronized (OPEN) {
            c = OPEN.get(s);
            if (c == null) {
                OPEN.values().forEach(Neo4jConnection::close);
                OPEN.clear();
                c = new Neo4jConnection(s);
                OPEN.put(s, c);
            }
            return c;
        }
    }

    static void closeAll() {
        synchronized (OPEN) {
            OPEN.values().forEach(Neo4jConnection::close);
            OPEN.clear();
        }
    }

    SessionConfig sessionConfig() {
        return settings.database() == null ? SessionConfig.defaultConfig() : SessionConfig.forDatabase(settings.database());
    }

    /** Legt Constraints und Indizes an (einmal je Verbindung) und wartet, bis sie bereit sind. */
    void ensureSchema() {
        if (schemaReady) {
            return;
        }
        synchronized (this) {
            if (schemaReady) {
                return;
            }
            for (String stmt : new String[] {
                    "CREATE CONSTRAINT graph_project_root IF NOT EXISTS FOR (p:GraphProject) REQUIRE p.root IS UNIQUE",
                    "CREATE CONSTRAINT graph_branch_key IF NOT EXISTS FOR (b:GraphBranch) REQUIRE b.key IS UNIQUE",
                    "CREATE INDEX graph_branch_root IF NOT EXISTS FOR (b:GraphBranch) ON (b.root)",
                    // Einzelschlüssel statt (g, id): nach großen Importen wählte der Planer sonst mit veralteter
                    // Statistik den g-Index und prüfte je Kante alle Knoten des Graphen (Minuten statt Sekunden)
                    "CREATE CONSTRAINT code_node_uid IF NOT EXISTS FOR (n:CodeNode) REQUIRE n.uid IS UNIQUE",
                    "DROP INDEX code_node_g_id IF EXISTS",
                    "CREATE INDEX code_node_g IF NOT EXISTS FOR (n:CodeNode) ON (n.g)",
                    "CREATE INDEX code_type_g IF NOT EXISTS FOR (n:Type) ON (n.g)",
                    "CREATE INDEX code_file_g IF NOT EXISTS FOR (n:File) ON (n.g)",
                    "CREATE INDEX code_external_g IF NOT EXISTS FOR (n:External) ON (n.g)",
                    "CREATE INDEX source_file_g IF NOT EXISTS FOR (f:SourceFile) ON (f.g)"}) {
                client.query(stmt).run();
            }
            client.query("CALL db.awaitIndexes(300)").run();
            schemaReady = true;
        }
    }

    @Override
    public void close() {
        try {
            driver.close();
        } catch (RuntimeException ignored) {
            // beim Schließen egal
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Neo4jConnection c && Objects.equals(settings, c.settings);
    }

    @Override
    public int hashCode() {
        return settings.hashCode();
    }
}
