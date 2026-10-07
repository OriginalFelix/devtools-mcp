package systems.grebe.devtools.mcp.backend.graph;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.arcadedb.Constants;
import com.arcadedb.database.BasicDatabase;
import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseFactory;
import com.arcadedb.exception.NeedRetryException;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.remote.RemoteDatabase;
import com.arcadedb.remote.RemoteServer;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.backend.BackendHome;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Community;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Confidence;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Edge;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.FileEntry;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.GraphFile;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Kind;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Node;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Relation;
import systems.grebe.devtools.mcp.modules.graph.GraphProvider;
import systems.grebe.devtools.mcp.modules.graph.GraphReader;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Graph-Storage: die zentrale Ablage der Code-Graphen in <a href="https://arcadedb.com">ArcadeDB</a> – eingebettet
 * (Standard, Datenbank im Datenverzeichnis des Backends) oder extern (ArcadeDB-Server über HTTP). Gewählt wird mit
 * {@code devtools.graph.mode=embedded|remote}, siehe {@link Settings}. Die Desktop-App nutzt die Ablage im Local-Mode
 * direkt als {@link GraphProvider}, mit Team-Server über die GraphQL-API ({@link GraphGraphQlController}); dort ist
 * jeder Benutzer ein eigener Bereich ({@link #forOwner}).
 *
 * <p>Datenmodell (Vertex-Typen mit Vererbung – {@code MATCH (n:Type)} findet alle Typen, {@code (n:CodeNode)} alles):
 * <pre>
 * (:GraphBranch {branchKey, projectKey, owner, root, project, branch, commitId, graphId, pendingGraphId, builtAt,
 *                generator, version, files, nodes, edges, stats (JSON), communities (JSON)})
 * (:SourceFile {g, path, sha256, lines, parseErrors})
 * (:CodeNode {g, uid = g|id, id, kind, name, file, line, endLine, modifiers, signature, doc, community,
 *             t = Typ-ID (Typ selbst bzw. Besitzer eines Members), ln/li = name/id klein})
 *   Type ← Class | Interface | Enum | Record | Annotation;  Member ← Constructor | Method | Field;
 *   Package, File, External – alle erben von CodeNode
 * (:CodeNode)-[:CALLS|EXTENDS|IMPLEMENTS|OVERRIDES|INSTANTIATES|HAS_TYPE|ANNOTATED_WITH|IMPORTS|CONTAINS
 *              {conf, score, count, line}]->(:CodeNode)
 * </pre>
 * {@code g} ist die {@code graphId} des Branches: jeder Aufbau schreibt eine neue Generation und schaltet
 * {@code GraphBranch.graphId} erst am Ende mit einem einzigen Kommando um – Leser sehen nie einen halben Graphen.
 * Generationen, auf die kein Branch mehr zeigt (ersetzt, abgebrochen, gelöscht), entfernt ein Aufräumer im Hintergrund;
 * das Löschen vieler Knoten und Kanten hält so keinen Aufbau auf.
 *
 * <p>Jede Schreiboperation ist ein einzelnes Kommando (Batches per {@code UNWIND}) – ohne clientseitige Transaktionen,
 * weil {@link RemoteDatabase} je Instanz nur eine Sitzung führt. Abfragen laufen als OpenCypher in der Datenbank
 * ({@link ArcadeGraphReader}); der Graph wird nie komplett geladen.
 */
@Component
public class GraphStorage implements GraphProvider, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(GraphStorage.class);

    static {
        // ArcadeDB protokolliert sonst per java.util.logging in ./log im Arbeitsverzeichnis
        if (System.getProperty("arcadedb.log.impl") == null) {
            System.setProperty("arcadedb.log.impl", "slf4j");
        }
    }

    static final String CYPHER = "opencypher";
    static final int BATCH = 10_000;
    private static final int RETRIES = 10;
    /** Knoten je Löschkommando beim Aufräumen alter Generationen. */
    private static final int DELETE_BATCH = 2_000;

    static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /** Bereich der direkten Zugriffe (Local-Mode); Benutzer der GraphQL-API haben {@code user:<id>}. */
    static final String LOCAL = "";

    public enum Mode { EMBEDDED, REMOTE }

    /**
     * Einstellungen ({@code devtools.graph.*}).
     *
     * @param path     Verzeichnis der eingebetteten Datenbank
     * @param host     ArcadeDB-Server (extern)
     * @param port     HTTP-Port des Servers (Standard 2480)
     * @param database Datenbank auf dem Server; fehlt sie, wird sie angelegt
     */
    public record Settings(Mode mode, Path path, String host, int port, String database, String user,
                           String password) {

        public static Settings embedded(Path path) {
            return new Settings(Mode.EMBEDDED, path, null, 0, null, null, null);
        }

        public static Settings remote(String host, int port, String database, String user, String password) {
            return new Settings(Mode.REMOTE, null, host, port, database, user, password);
        }

        @Override
        public String toString() {
            // Passwort bewusst nicht ausgeben
            return mode == Mode.EMBEDDED ? "ArcadeDB eingebettet (" + path + ")"
                    : "ArcadeDB " + host + ":" + port + "/" + database;
        }
    }

    /** Kopfdaten beim Umschalten auf eine neue Generation ({@code stats} als JSON). */
    public record Header(String commit, String builtAt, String generator, int version, long files, long nodes,
                         long edges, String stats, List<Community> communities) {

        static Header of(GraphFile data) {
            return new Header(data.commit(), data.builtAt(), data.generator(), data.version(), data.files().size(),
                    data.nodes().size(), data.edges().size(), JSON.writeValueAsString(data.stats()),
                    data.communities() == null ? List.of() : data.communities());
        }
    }

    private final Settings settings;
    private DatabaseFactory factory;
    private volatile BasicDatabase db;

    private final Object cleanLock = new Object();
    private boolean cleanPending;
    private Thread cleaner;
    private volatile boolean closing;

    @Autowired
    public GraphStorage(BackendHome home,
                        @Value("${devtools.graph.mode:embedded}") String mode,
                        @Value("${devtools.graph.path:}") String path,
                        @Value("${devtools.graph.host:localhost}") String host,
                        @Value("${devtools.graph.port:2480}") int port,
                        @Value("${devtools.graph.database:devtools}") String database,
                        @Value("${devtools.graph.user:root}") String user,
                        @Value("${devtools.graph.password:}") String password) {
        this(switch (mode.strip().toLowerCase(Locale.ROOT)) {
            case "embedded", "" -> Settings.embedded(path.isBlank() ? home.resolve("graphdb") : Path.of(path));
            case "remote", "external" -> Settings.remote(host, port, database, user, password);
            default -> throw new IllegalArgumentException("devtools.graph.mode: 'embedded' oder 'remote', nicht '"
                    + mode + "'");
        });
    }

    public GraphStorage(Settings settings) {
        this.settings = settings;
    }

    public Settings settings() {
        return settings;
    }

    // ------------------------------------------------------------------ Verbindung

    /** Offene Datenbank; beim ersten Zugriff geöffnet (bzw. angelegt) und mit Schema versehen. */
    BasicDatabase db() {
        BasicDatabase d = db;
        if (d != null) {
            return d;
        }
        synchronized (this) {
            if (db == null) {
                if (closing) {
                    throw new IllegalStateException("Graph-Storage ist geschlossen.");
                }
                BasicDatabase opened;
                try {
                    opened = open();
                } catch (RuntimeException e) {
                    throw explain(e);
                }
                try {
                    createSchema(opened);
                } catch (RuntimeException e) {
                    opened.close();
                    throw explain(e);
                }
                db = opened;
                LOG.info("Graph-Storage: {}", settings);
                scheduleCleanup(); // Reste früherer Läufe
            }
            return db;
        }
    }

    private BasicDatabase open() {
        if (settings.mode() == Mode.EMBEDDED) {
            factory = new DatabaseFactory(settings.path().toAbsolutePath().toString());
            return factory.exists() ? factory.open() : factory.create();
        }
        RemoteServer server = new RemoteServer(settings.host(), settings.port(), settings.user(),
                settings.password() == null ? "" : settings.password());
        if (!server.exists(settings.database())) {
            server.create(settings.database());
        }
        return new RemoteDatabase(settings.host(), settings.port(), settings.database(), settings.user(),
                settings.password() == null ? "" : settings.password());
    }

    /** Fehler beim Öffnen als Meldung mit Hinweis auf die Einstellungen. */
    private IllegalStateException explain(RuntimeException e) {
        String hint = settings.mode() == Mode.EMBEDDED
                ? " Läuft schon ein anderes Programm mit derselben Datenbank? Sonst devtools.graph.path prüfen."
                : " Läuft der ArcadeDB-Server? Sonst devtools.graph.host, -port, -database, -user und -password des "
                + "Backends prüfen.";
        return new IllegalStateException(settings + " ist nicht verfügbar: " + rootMessage(e) + "." + hint, e);
    }

    static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    /** Vertex-Typen der Knoten je Art, z.B. {@code Class} (erbt von {@code Type} und {@code CodeNode}). */
    static String typeOf(Kind kind) {
        return kind.name().charAt(0) + kind.name().substring(1).toLowerCase(Locale.ROOT);
    }

    private static void createSchema(BasicDatabase d) {
        StringBuilder sql = new StringBuilder();
        sql.append("CREATE VERTEX TYPE `CodeNode` IF NOT EXISTS;\n")
                .append("CREATE VERTEX TYPE `Type` IF NOT EXISTS EXTENDS `CodeNode`;\n")
                .append("CREATE VERTEX TYPE `Member` IF NOT EXISTS EXTENDS `CodeNode`;\n");
        for (Kind k : Kind.values()) {
            String parent = k.isType() ? "Type" : k.isMember() ? "Member" : "CodeNode";
            sql.append("CREATE VERTEX TYPE `").append(typeOf(k)).append("` IF NOT EXISTS EXTENDS `").append(parent)
                    .append("`;\n");
        }
        for (Relation r : Relation.values()) {
            sql.append("CREATE EDGE TYPE `").append(r.name()).append("` IF NOT EXISTS;\n");
        }
        sql.append("""
                CREATE PROPERTY `CodeNode`.uid IF NOT EXISTS STRING;
                CREATE PROPERTY `CodeNode`.g IF NOT EXISTS STRING;
                CREATE INDEX IF NOT EXISTS ON `CodeNode` (uid) UNIQUE;
                CREATE INDEX IF NOT EXISTS ON `CodeNode` (g) NOTUNIQUE;
                CREATE VERTEX TYPE `SourceFile` IF NOT EXISTS;
                CREATE PROPERTY `SourceFile`.g IF NOT EXISTS STRING;
                CREATE INDEX IF NOT EXISTS ON `SourceFile` (g) NOTUNIQUE;
                CREATE VERTEX TYPE `GraphBranch` IF NOT EXISTS;
                CREATE PROPERTY `GraphBranch`.branchKey IF NOT EXISTS STRING;
                CREATE PROPERTY `GraphBranch`.projectKey IF NOT EXISTS STRING;
                CREATE INDEX IF NOT EXISTS ON `GraphBranch` (branchKey) UNIQUE;
                CREATE INDEX IF NOT EXISTS ON `GraphBranch` (projectKey) NOTUNIQUE;
                """);
        d.command("sqlscript", sql.toString());
    }

    /** Beschreibung samt ArcadeDB-Version; öffnet dabei die Datenbank. */
    @Override
    public String check() {
        db();
        return settings + " · ArcadeDB " + Constants.getRawVersion();
    }

    @Override
    public String describe() {
        return settings.toString();
    }

    @PreDestroy
    @Override
    public void close() {
        closing = true;
        Thread c;
        synchronized (cleanLock) {
            c = cleaner;
        }
        if (c != null) {
            try {
                c.join(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (this) {
            BasicDatabase d = db;
            db = null;
            if (d != null) {
                try {
                    d.close();
                } catch (RuntimeException e) {
                    LOG.warn("Graph-Storage nicht sauber geschlossen: {}", e.toString());
                }
            }
            if (factory != null) {
                factory.close();
                factory = null;
            }
        }
    }

    // ------------------------------------------------------------------ Zugriff

    /** Ergebniszeilen als Maps (Spalte → Wert). */
    List<Map<String, Object>> rows(String language, String query, Map<String, Object> params) {
        List<Map<String, Object>> out = new ArrayList<>();
        try (ResultSet rs = db().query(language, query, params)) {
            while (rs.hasNext()) {
                out.add(row(rs.next()));
            }
        }
        return out;
    }

    static Map<String, Object> row(Result r) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String k : r.getPropertyNames()) {
            m.put(k, r.getProperty(k));
        }
        return m;
    }

    /**
     * Schreibendes Kommando – eingebettet in einer Transaktion, extern als einzelner Request (auf dem Server atomar);
     * bei Konflikten mit parallelen Schreibern (z.B. dem Aufräumer) wiederholt.
     */
    List<Map<String, Object>> exec(String language, String command, Map<String, Object> params) {
        BasicDatabase d = db();
        for (int attempt = 1; ; attempt++) {
            try {
                if (d instanceof Database local) {
                    List<Map<String, Object>> out = new ArrayList<>();
                    local.transaction(() -> {
                        out.clear();
                        try (ResultSet rs = local.command(language, command, params)) {
                            while (rs.hasNext()) {
                                out.add(row(rs.next()));
                            }
                        }
                    });
                    return out;
                }
                List<Map<String, Object>> out = new ArrayList<>();
                try (ResultSet rs = d.command(language, command, params)) {
                    while (rs.hasNext()) {
                        out.add(row(rs.next()));
                    }
                }
                return out;
            } catch (NeedRetryException e) {
                if (attempt >= RETRIES) {
                    throw e;
                }
                Thread.onSpinWait();
            }
        }
    }

    static String rootKey(String root) {
        return root.replace('\\', '/');
    }

    static String projectKey(String owner, String root) {
        return (owner.isEmpty() ? "" : owner + "|") + rootKey(root);
    }

    static String branchKey(String owner, String root, String branch) {
        return projectKey(owner, root) + "@" + (branch == null ? "" : branch);
    }

    private static Map<String, Object> params(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private Map<String, Object> branchRow(String key) {
        List<Map<String, Object>> r = rows("sql", "SELECT FROM GraphBranch WHERE branchKey = :k", params("k", key));
        return r.isEmpty() ? null : r.getFirst();
    }

    private String locationOf(String branchKey) {
        return settings + " · GraphBranch " + branchKey;
    }

    GraphReader.GraphInfo info(Map<String, Object> b) {
        Map<String, Object> stats = b.get("stats") instanceof String s
                ? JSON.readValue(s, new TypeReference<LinkedHashMap<String, Object>>() { }) : Map.of();
        List<Community> communities = b.get("communities") instanceof String s
                ? JSON.readValue(s, new TypeReference<List<Community>>() { }) : List.of();
        return new GraphReader.GraphInfo((String) b.get("project"), (String) b.get("branch"),
                (String) b.get("commitId"), (String) b.get("root"), (String) b.get("builtAt"),
                (String) b.get("generator"), stats, communities, locationOf((String) b.get("branchKey")));
    }

    // ------------------------------------------------------------------ GraphProvider (Local-Mode)

    @Override
    public String location(Key key) {
        return location(LOCAL, key);
    }

    @Override
    public State state(Key key) {
        return state(LOCAL, key);
    }

    @Override
    public GraphReader reader(Key key) {
        return reader(LOCAL, key);
    }

    @Override
    public GraphReader write(Key key, GraphFile data) {
        return write(LOCAL, key, data);
    }

    @Override
    public List<Stored> branches(String root) {
        return branches(LOCAL, root);
    }

    @Override
    public boolean delete(String root, String branch) {
        return delete(LOCAL, root, branch);
    }

    /** Die Ablage aus Sicht eines Benutzers der GraphQL-API: seine Graphen sind von denen anderer getrennt. */
    public GraphProvider forOwner(String owner) {
        GraphStorage s = this;
        return new GraphProvider() {
            @Override
            public String describe() {
                return s.describe();
            }

            @Override
            public String check() {
                return s.check();
            }

            @Override
            public String location(Key key) {
                return s.location(owner, key);
            }

            @Override
            public State state(Key key) {
                return s.state(owner, key);
            }

            @Override
            public GraphReader reader(Key key) {
                return s.reader(owner, key);
            }

            @Override
            public GraphReader write(Key key, GraphFile data) {
                return s.write(owner, key, data);
            }

            @Override
            public List<Stored> branches(String root) {
                return s.branches(owner, root);
            }

            @Override
            public boolean delete(String root, String branch) {
                return s.delete(owner, root, branch);
            }
        };
    }

    // ------------------------------------------------------------------ Lesen

    String location(String owner, Key key) {
        return locationOf(branchKey(owner, key.root(), key.branch()));
    }

    State state(String owner, Key key) {
        Map<String, Object> b = branchRow(branchKey(owner, key.root(), key.branch()));
        if (b == null || b.get("graphId") == null || !(b.get("version") instanceof Number v)
                || v.intValue() != CodeGraph.VERSION) {
            return null;
        }
        Map<String, String> hashes = new HashMap<>();
        rows("sql", "SELECT path, sha256 FROM SourceFile WHERE g = :g", params("g", b.get("graphId")))
                .forEach(r -> hashes.put((String) r.get("path"), (String) r.get("sha256")));
        return new State((String) b.get("generator"), hashes);
    }

    GraphReader reader(String owner, Key key) {
        Map<String, Object> b = branchRow(branchKey(owner, key.root(), key.branch()));
        if (b == null || b.get("graphId") == null) {
            return null;
        }
        return new ArcadeGraphReader(this, (String) b.get("graphId"), info(b));
    }

    /**
     * Leser für eine Generation des Benutzers (GraphQL: die App fragt mit der ID aus {@code graph}).
     *
     * @throws IllegalArgumentException wenn es sie nicht (mehr) gibt oder sie einem anderen gehört
     */
    public ArcadeGraphReader reader(String owner, String graphId) {
        List<Map<String, Object>> r = rows("sql", "SELECT FROM GraphBranch WHERE graphId = :g AND owner = :o",
                params("g", graphId, "o", owner));
        if (r.isEmpty()) {
            throw new IllegalArgumentException("Graph " + graphId + " gibt es nicht mehr (inzwischen neu gebaut oder "
                    + "gelöscht) – erneut abfragen.");
        }
        return new ArcadeGraphReader(this, graphId, info(r.getFirst()));
    }

    List<Stored> branches(String owner, String root) {
        List<Stored> out = new ArrayList<>();
        for (Map<String, Object> b : rows("sql", "SELECT FROM GraphBranch WHERE projectKey = :p",
                params("p", projectKey(owner, root)))) {
            if (b.get("graphId") == null) {
                continue; // erster Aufbau läuft noch oder ist abgebrochen
            }
            out.add(new Stored((String) b.get("branch"), (String) b.get("commitId"), (String) b.get("builtAt"),
                    number(b.get("files")), number(b.get("nodes")), number(b.get("edges")),
                    locationOf((String) b.get("branchKey"))));
        }
        out.sort(Comparator.comparing(s -> s.branch() == null ? "" : s.branch()));
        return out;
    }

    static long number(Object o) {
        return o instanceof Number n ? n.longValue() : 0;
    }

    // ------------------------------------------------------------------ Schreiben

    GraphReader write(String owner, Key key, GraphFile data) {
        String g = begin(owner, key);
        try {
            writeFiles(owner, g, data.files());
            writeNodes(owner, g, data.nodes());
            writeEdges(owner, g, data.edges());
            return publish(owner, key, g, Header.of(data));
        } catch (RuntimeException e) {
            try {
                abort(owner, g);
            } catch (RuntimeException suppressed) {
                e.addSuppressed(suppressed); // bleibt als pendingGraphId stehen und wird beim nächsten Aufbau ersetzt
            }
            throw e;
        }
    }

    /**
     * Legt den Branch an bzw. merkt eine neue Generation vor; eine vorgemerkte eines abgebrochenen Aufbaus wird dabei
     * ersetzt (und vom Aufräumer entfernt).
     *
     * @return ID der neuen Generation für {@code writeFiles}/{@code writeNodes}/{@code writeEdges}/{@code publish}
     */
    public String begin(String owner, Key key) {
        String g = UUID.randomUUID().toString();
        exec("sql", "UPDATE GraphBranch SET branchKey = :k, projectKey = :p, owner = :o, root = :root, project = :n, "
                        + "branch = :b, pendingGraphId = :g UPSERT WHERE branchKey = :k",
                params("k", branchKey(owner, key.root(), key.branch()), "p", projectKey(owner, key.root()), "o", owner,
                        "root", key.root(), "n", key.project(), "b", key.branch(), "g", g));
        return g;
    }

    /** Die Generation muss für einen Branch des Benutzers vorgemerkt sein – sonst wird nicht geschrieben. */
    private void requirePending(String owner, String g) {
        if (rows("sql", "SELECT branchKey FROM GraphBranch WHERE pendingGraphId = :g AND owner = :o",
                params("g", g, "o", owner)).isEmpty()) {
            throw new IllegalArgumentException("Kein laufender Aufbau mit der ID " + g + " (abgebrochen oder von einem "
                    + "neueren abgelöst).");
        }
    }

    private static void checkInterrupt() {
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("Graph-Aufbau abgebrochen", new InterruptedException());
        }
    }

    public void writeFiles(String owner, String g, List<FileEntry> files) {
        requirePending(owner, g);
        List<Map<String, Object>> rows = new ArrayList<>(Math.min(files.size(), BATCH));
        for (FileEntry f : files) {
            Map<String, Object> r = new HashMap<>();
            r.put("path", f.path());
            r.put("sha256", f.sha256());
            r.put("lines", f.lines());
            r.put("parseErrors", Boolean.TRUE.equals(f.parseErrors()) ? Boolean.TRUE : null);
            rows.add(r);
            if (rows.size() == BATCH) {
                flushFiles(g, rows);
            }
        }
        flushFiles(g, rows);
    }

    private void flushFiles(String g, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return;
        }
        checkInterrupt();
        exec(CYPHER, "UNWIND $rows AS r CREATE (f:SourceFile) SET f = r, f.g = $g", params("g", g, "rows",
                List.copyOf(rows)));
        rows.clear();
    }

    public void writeNodes(String owner, String g, List<Node> nodes) {
        requirePending(owner, g);
        Map<Kind, List<Map<String, Object>>> byKind = new EnumMap<>(Kind.class);
        for (Node n : nodes) {
            List<Map<String, Object>> rows = byKind.computeIfAbsent(n.kind(), k -> new ArrayList<>());
            rows.add(nodeRow(n));
            if (rows.size() == BATCH) {
                flushNodes(g, n.kind(), rows);
            }
        }
        byKind.forEach((kind, rows) -> flushNodes(g, kind, rows));
    }

    static Map<String, Object> nodeRow(Node n) {
        Map<String, Object> r = new HashMap<>();
        r.put("id", n.id());
        r.put("kind", n.kind().name());
        r.put("name", n.name());
        r.put("file", n.file());
        r.put("line", n.line());
        r.put("endLine", n.endLine());
        r.put("modifiers", n.modifiers());
        r.put("signature", n.signature());
        r.put("doc", n.doc());
        r.put("community", n.community());
        r.put("t", n.kind().isType() ? n.id() : n.kind().isMember() ? CodeGraph.ownerOf(n.id()) : null);
        r.put("ln", n.name().toLowerCase(Locale.ROOT));
        r.put("li", n.id().toLowerCase(Locale.ROOT));
        // uid = g|id wird beim Schreiben ergänzt (eindeutiger Einzelschlüssel für den Lookup beim Kantenimport)
        return r;
    }

    private void flushNodes(String g, Kind kind, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return;
        }
        checkInterrupt();
        // Typnamen können nicht als Parameter übergeben werden; sie stammen aus dem Enum, nicht aus Eingaben.
        exec(CYPHER, "UNWIND $rows AS r CREATE (n:" + typeOf(kind) + ") SET n = r, n.g = $g, n.uid = $g + '|' + r.id",
                params("g", g, "rows", List.copyOf(rows)));
        rows.clear();
    }

    public void writeEdges(String owner, String g, List<Edge> edges) {
        requirePending(owner, g);
        Map<Relation, List<Map<String, Object>>> byRel = new EnumMap<>(Relation.class);
        for (Edge e : edges) {
            List<Map<String, Object>> rows = byRel.computeIfAbsent(e.rel(), k -> new ArrayList<>());
            Map<String, Object> p = new HashMap<>();
            p.put("conf", e.conf() == null || e.conf() == Confidence.EXTRACTED ? null : e.conf().name());
            p.put("score", e.score());
            p.put("count", e.count());
            p.put("line", e.line());
            rows.add(Map.of("f", e.from(), "t", e.to(), "p", p));
            if (rows.size() == BATCH) {
                flushEdges(g, e.rel(), rows);
            }
        }
        byRel.forEach((rel, rows) -> flushEdges(g, rel, rows));
    }

    private void flushEdges(String g, Relation rel, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return;
        }
        checkInterrupt();
        exec(CYPHER, "UNWIND $rows AS r "
                        + "MATCH (a:CodeNode {uid: $g + '|' + r.f}) MATCH (b:CodeNode {uid: $g + '|' + r.t}) "
                        + "CREATE (a)-[e:" + rel.name() + "]->(b) SET e = r.p",
                params("g", g, "rows", List.copyOf(rows)));
        rows.clear();
    }

    /**
     * Schaltet den Branch auf die vorgemerkte Generation um – ein einziges Kommando, also atomar. Die vorige
     * Generation entfernt der Aufräumer.
     */
    public ArcadeGraphReader publish(String owner, Key key, String g, Header h) {
        String bkey = branchKey(owner, key.root(), key.branch());
        List<Map<String, Object>> r = exec("sql", "UPDATE GraphBranch SET graphId = :g, pendingGraphId = null, "
                        + "commitId = :commit, builtAt = :builtAt, generator = :generator, version = :version, "
                        + "files = :files, nodes = :nodes, edges = :edges, stats = :stats, communities = :communities "
                        + "RETURN AFTER WHERE branchKey = :k AND pendingGraphId = :g",
                params("g", g, "k", bkey, "commit", h.commit(), "builtAt", h.builtAt(), "generator", h.generator(),
                        "version", h.version(), "files", h.files(), "nodes", h.nodes(), "edges", h.edges(),
                        "stats", h.stats(), "communities", JSON.writeValueAsString(
                                h.communities() == null ? List.of() : h.communities())));
        if (r.isEmpty()) {
            throw new IllegalStateException("Der Aufbau " + g + " für " + bkey + " wurde abgebrochen oder von einem "
                    + "neueren abgelöst.");
        }
        scheduleCleanup();
        return new ArcadeGraphReader(this, g, info(r.getFirst()));
    }

    /** Verwirft eine vorgemerkte Generation (der Aufräumer entfernt, was schon geschrieben ist). */
    public void abort(String owner, String g) {
        exec("sql", "UPDATE GraphBranch SET pendingGraphId = null WHERE pendingGraphId = :g AND owner = :o",
                params("g", g, "o", owner));
        scheduleCleanup();
    }

    boolean delete(String owner, String root, String branch) {
        String bkey = branchKey(owner, root, branch);
        if (branchRow(bkey) == null) {
            return false;
        }
        exec("sql", "DELETE FROM GraphBranch WHERE branchKey = :k", params("k", bkey));
        scheduleCleanup();
        return true;
    }

    // ------------------------------------------------------------------ Aufräumen

    /** Stößt den Aufräumer an (läuft im Hintergrund, höchstens einer). */
    void scheduleCleanup() {
        synchronized (cleanLock) {
            cleanPending = true;
            if (cleaner == null && !closing) {
                cleaner = Thread.ofVirtual().name("graph-cleanup").start(this::cleanLoop);
            }
        }
    }

    private void cleanLoop() {
        while (true) {
            synchronized (cleanLock) {
                if (!cleanPending || closing) {
                    cleaner = null;
                    cleanLock.notifyAll();
                    return;
                }
                cleanPending = false;
            }
            try {
                cleanup();
            } catch (RuntimeException e) {
                if (!closing) {
                    LOG.warn("Alte Code-Graphen nicht entfernt: {}", rootMessage(e));
                }
            }
        }
    }

    /** Wartet, bis der Aufräumer fertig ist (Tests, Wartung). */
    public void awaitCleanup() throws InterruptedException {
        synchronized (cleanLock) {
            while (cleaner != null) {
                cleanLock.wait();
            }
        }
    }

    /**
     * Entfernt Generationen, auf die kein Branch mehr zeigt. Erst die vorhandenen Generationen lesen, dann die
     * benutzten: ein Aufbau merkt seine Generation vor, bevor er schreibt – was hier gefunden wird, ist also entweder
     * vorgemerkt bzw. veröffentlicht oder wirklich verwaist.
     */
    void cleanup() {
        Set<String> generations = new HashSet<>();
        rows("sql", "SELECT g FROM SourceFile GROUP BY g", Map.of()).forEach(r -> generations.add((String) r.get("g")));
        rows("sql", "SELECT g FROM CodeNode GROUP BY g", Map.of()).forEach(r -> generations.add((String) r.get("g")));
        if (generations.isEmpty()) {
            return;
        }
        for (Map<String, Object> b : rows("sql", "SELECT graphId, pendingGraphId FROM GraphBranch", Map.of())) {
            generations.remove((String) b.get("graphId"));
            generations.remove((String) b.get("pendingGraphId"));
        }
        generations.remove(null);
        for (String g : generations) {
            if (closing) {
                return;
            }
            deleteGeneration(g);
        }
    }

    /** Entfernt Knoten, Kanten und Quelldateien einer Generation in Batches; Quelldateien zuletzt. */
    void deleteGeneration(String g) {
        long deleted;
        do {
            if (closing) {
                return;
            }
            List<Map<String, Object>> r = exec(CYPHER, "MATCH (n:CodeNode {g: $g}) WITH n LIMIT " + DELETE_BATCH
                    + " DETACH DELETE n RETURN count(*) AS c", params("g", g));
            deleted = r.isEmpty() ? 0 : number(r.getFirst().get("c"));
        } while (deleted > 0);
        exec("sql", "DELETE FROM SourceFile WHERE g = :g", params("g", g));
        LOG.debug("Code-Graph-Generation {} entfernt", g);
    }

    /** Für Tests: Anzahl Datensätze einer Generation. */
    long count(String type, String g) {
        List<Map<String, Object>> r = rows("sql", "SELECT count(*) AS c FROM `" + type + "` WHERE g = :g",
                params("g", g));
        return r.isEmpty() ? 0 : number(r.getFirst().get("c"));
    }
}
