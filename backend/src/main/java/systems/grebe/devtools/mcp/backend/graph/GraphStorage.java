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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongPredicate;

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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
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
import systems.grebe.devtools.mcp.modules.graph.GraphDelta;
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

    /** Version der Schlüssel in {@code GraphBranch}; Branches älterer Versionen (nach Pfad) entfernt der Start. */
    static final int KEY_VERSION = 2;

    public enum Mode { EMBEDDED, REMOTE }

    /**
     * Einstellungen ({@code devtools.graph.*} bzw. Reiter „Backend“ der Desktop-App).
     *
     * @param path     Verzeichnis der eingebetteten Datenbank; {@code null} = {@code graphdb/} im Datenverzeichnis
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

    /**
     * Einstellungen, die Vorrang vor {@code devtools.graph.*} haben – in der Desktop-App die aus dem Reiter „Backend“.
     * {@code null} = nicht eingestellt.
     */
    @FunctionalInterface
    public interface Configured {
        Settings settings();
    }

    /** Kopfdaten beim Umschalten auf eine neue Generation ({@code stats} als JSON). */
    public record Header(String commit, String builtAt, String builtBy, String generator, int version, long files,
                         long nodes, long edges, String stats, List<Community> communities) {

        static Header of(GraphFile data, String builtBy) {
            return new Header(data.commit(), data.builtAt(), builtBy, data.generator(), data.version(),
                    data.files().size(), data.nodes().size(), data.edges().size(), JSON.writeValueAsString(data.stats()),
                    data.communities() == null ? List.of() : data.communities());
        }
    }

    private final Path defaultPath;
    private volatile Settings settings;
    private DatabaseFactory factory;
    private volatile BasicDatabase db;
    private volatile String status = "nicht gestartet";
    private final List<Runnable> statusListeners = new CopyOnWriteArrayList<>();

    private final Object cleanLock = new Object();
    private boolean cleanPending;
    private Thread cleaner;
    private volatile boolean closing;

    @Autowired
    public GraphStorage(BackendHome home, ObjectProvider<Configured> configured,
                        @Value("${devtools.graph.mode:embedded}") String mode,
                        @Value("${devtools.graph.path:}") String path,
                        @Value("${devtools.graph.host:localhost}") String host,
                        @Value("${devtools.graph.port:2480}") int port,
                        @Value("${devtools.graph.database:devtools}") String database,
                        @Value("${devtools.graph.user:root}") String user,
                        @Value("${devtools.graph.password:}") String password) {
        this.defaultPath = path.isBlank() ? home.resolve("graphdb") : Path.of(path);
        Configured c = configured.getIfAvailable();
        Settings fromApp = c == null ? null : c.settings();
        this.settings = resolve(fromApp != null ? fromApp : switch (mode.strip().toLowerCase(Locale.ROOT)) {
            case "embedded", "" -> Settings.embedded(null);
            case "remote", "external" -> Settings.remote(host, port, database, user, password);
            default -> throw new IllegalArgumentException("devtools.graph.mode: 'embedded' oder 'remote', nicht '"
                    + mode + "'");
        });
    }

    public GraphStorage(Settings settings) {
        this.defaultPath = settings.path();
        this.settings = settings;
    }

    /** Eingebettet ohne Pfad: {@code graphdb/} im Datenverzeichnis. */
    private Settings resolve(Settings s) {
        return s.mode() == Mode.EMBEDDED && s.path() == null ? Settings.embedded(defaultPath) : s;
    }

    public Settings settings() {
        return settings;
    }

    // ------------------------------------------------------------------ Verbindung

    /** Startet die Datenbank beim Start des Backends – im Hintergrund, der Start wartet nicht darauf. */
    @EventListener(ApplicationReadyEvent.class)
    public void startWhenReady() {
        Thread.ofVirtual().name("graph-start").start(this::start);
    }

    /**
     * Öffnet die Datenbank (legt sie bei Bedarf an) und liefert den Status; Fehler stehen im Status, statt geworfen zu
     * werden.
     */
    public String start() {
        try {
            setStatus("läuft: " + check());
        } catch (RuntimeException e) {
            setStatus("Fehler: " + e.getMessage());
            LOG.warn("Graph-Storage nicht gestartet: {}", e.getMessage());
        }
        return status;
    }

    private void setStatus(String value) {
        status = value;
        statusListeners.forEach(Runnable::run);
    }

    /** Wird bei jeder Änderung des Status aufgerufen (beliebiger Thread). */
    public void addStatusListener(Runnable listener) {
        statusListeners.add(listener);
    }

    /** Zustand für die Oberfläche, z.B. {@code läuft: ArcadeDB eingebettet (…) · ArcadeDB 26.10.1}. */
    public String status() {
        return status;
    }

    /**
     * Stellt auf andere Einstellungen um (Reiter „Backend“): schließt die offene Datenbank und startet mit den neuen.
     * Laufende Abfragen auf der alten schlagen dabei fehl.
     *
     * @return Status nach dem Start
     */
    public String configure(Settings next) {
        synchronized (this) {
            closing = true;
            shutdown();
            settings = resolve(next);
            setStatus("wird gestartet …");
            closing = false;
        }
        return start();
    }

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
                ? " Läuft schon ein anderes Programm mit derselben Datenbank? Sonst devtools.graph.path bzw. den Reiter "
                + "„Backend“ der Desktop-App prüfen."
                : " Läuft der ArcadeDB-Server? Sonst Host, Port, Datenbank, Benutzer und Passwort prüfen (Reiter „Backend“ "
                + "der Desktop-App bzw. devtools.graph.* des Backends).";
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

    /**
     * Index auf einer String-Property. Indizes auf {@code CodeNode} gelten für alle Untertypen ({@code Class},
     * {@code Method}, …) – ArcadeDB legt sie über deren Buckets mit an.
     */
    record IndexDef(String type, String property, boolean unique) {

        /** Name in ArcadeDB, z.B. {@code CodeNode[uid]}. */
        String name() {
            return type + "[" + property + "]";
        }
    }

    /**
     * Alle Indizes der Ablage. Sie werden beim Öffnen automatisch angelegt – auch in bestehenden Datenbanken, dort
     * einmalig über die vorhandenen Datensätze. Jede Abfrage der Ablage greift über einen davon zu, keine durchsucht
     * einen Typ komplett: {@code uid} für Knoten-Lookups und Kantenimport, {@code g} für alles je Generation (auch das
     * Aufzählen der Generationen im Aufräumer), {@code graphId}/{@code pendingGraphId} für Leser und laufende Aufbauten.
     */
    static final List<IndexDef> INDEXES = List.of(
            new IndexDef("CodeNode", "uid", true),
            new IndexDef("CodeNode", "g", false),
            new IndexDef("SourceFile", "g", false),
            new IndexDef("GraphBranch", "branchKey", true),
            new IndexDef("GraphBranch", "projectKey", false),
            new IndexDef("GraphBranch", "graphId", false),
            new IndexDef("GraphBranch", "pendingGraphId", false));

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
        sql.append("CREATE VERTEX TYPE `SourceFile` IF NOT EXISTS;\n")
                .append("CREATE VERTEX TYPE `GraphBranch` IF NOT EXISTS;\n");
        for (IndexDef i : INDEXES) {
            sql.append("CREATE PROPERTY `").append(i.type()).append("`.`").append(i.property())
                    .append("` IF NOT EXISTS STRING;\n");
        }
        Set<String> existing = indexNames(d);
        for (IndexDef i : INDEXES) {
            sql.append("CREATE INDEX IF NOT EXISTS ON `").append(i.type()).append("` (`").append(i.property())
                    .append("`) ").append(i.unique() ? "UNIQUE" : "NOTUNIQUE").append(";\n");
        }
        d.command("sqlscript", sql.toString());
        List<String> created = INDEXES.stream().map(IndexDef::name).filter(n -> !existing.contains(n)).toList();
        if (!existing.isEmpty() && !created.isEmpty()) {
            LOG.info("Graph-Storage: Indizes {} angelegt", created);
        }
        // Branches älterer Schlüssel (nach Pfad) – ihre Generationen entfernt danach der Aufräumer. Eigenes Kommando:
        // im selben Skript kennt ArcadeDB den eben angelegten Typ noch nicht.
        String old = "DELETE FROM GraphBranch WHERE keyVersion IS NULL OR keyVersion < " + KEY_VERSION;
        if (d instanceof Database local) {
            local.transaction(() -> local.command("sql", old).close());
        } else {
            d.command("sql", old).close();
        }
    }

    /** Namen der vorhandenen Indizes, z.B. {@code CodeNode[uid]}. */
    static Set<String> indexNames(BasicDatabase d) {
        Set<String> out = new HashSet<>();
        try (ResultSet rs = d.query("sql", "SELECT name FROM schema:indexes")) {
            while (rs.hasNext()) {
                out.add(rs.next().getProperty("name"));
            }
        }
        return out;
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
        shutdown();
        setStatus("beendet");
    }

    /** Wartet auf den Aufräumer und schließt die Datenbank; {@code closing} muss gesetzt sein. */
    private void shutdown() {
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

    /**
     * Schlüssel des Projekts: ein Backend-Projekt gilt für alle Benutzer gemeinsam ({@code project:<id>}), andere
     * Verzeichnisse nach Namen im Bereich des Benutzers ({@code <bereich>|name:<name>}). Pfad und Rechner gehen nicht
     * ein – derselbe Branch desselben Projekts ist überall derselbe Graph.
     */
    static String projectKey(Access a, Key key) {
        if (key.projectId() != null) {
            return "project:" + key.projectId();
        }
        return (a.owner().isEmpty() ? "" : a.owner() + "|") + "name:" + key.project().strip().toLowerCase(Locale.ROOT);
    }

    static String branchKey(Access a, Key key) {
        return projectKey(a, key) + "@" + (key.branch() == null ? "" : key.branch());
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

    // ------------------------------------------------------------------ Zugriffsrechte

    /**
     * Wer zugreift: der eigene Bereich (für Verzeichnisse ohne Backend-Projekt) und die Rechte auf Backend-Projekte.
     *
     * @param owner    {@code ""} im Local-Mode, über die GraphQL-API {@code user:<id>}
     * @param canRead  Projekt sichtbar (eigenes oder freigegebenes)
     * @param canWrite Graph-Aufbau erlaubt (Eigentümer oder Freigabe mit Schreibrecht)
     */
    public record Access(String owner, LongPredicate canRead, LongPredicate canWrite) {

        /** Direkter Zugriff im Local-Mode: eigener Bereich, alle Projekte. */
        public static final Access LOCAL = new Access("", id -> true, id -> true);
    }

    private static Long projectIdOf(Map<String, Object> b) {
        return b.get("projectId") instanceof Number n ? n.longValue() : null;
    }

    private static void requireRead(Access a, Long projectId) {
        if (projectId != null && !a.canRead().test(projectId)) {
            throw new IllegalArgumentException("Kein Zugriff auf das Projekt " + projectId + " – es ist weder eigenes "
                    + "noch freigegeben.");
        }
    }

    private static void requireWrite(Access a, Long projectId) {
        requireRead(a, projectId);
        if (projectId != null && !a.canWrite().test(projectId)) {
            throw new IllegalArgumentException("Das Projekt " + projectId + " ist nur zum Lesen freigegeben – den Graphen "
                    + "bauen dürfen der Eigentümer und Freigaben mit Schreibrecht.");
        }
    }

    /** Gespeicherter Branch sichtbar: Backend-Projekt nach Rechten, sonst nur im eigenen Bereich. */
    private static boolean readable(Access a, Map<String, Object> b) {
        Long p = projectIdOf(b);
        return p != null ? a.canRead().test(p) : a.owner().equals(b.get("owner"));
    }

    private static boolean writable(Access a, Map<String, Object> b) {
        Long p = projectIdOf(b);
        return p != null ? a.canRead().test(p) && a.canWrite().test(p) : a.owner().equals(b.get("owner"));
    }

    // ------------------------------------------------------------------ GraphProvider (Local-Mode)

    @Override
    public String location(Key key) {
        return location(Access.LOCAL, key);
    }

    @Override
    public State state(Key key) {
        return state(Access.LOCAL, key);
    }

    @Override
    public GraphReader reader(Key key) {
        return reader(Access.LOCAL, key);
    }

    @Override
    public GraphReader write(Key key, GraphFile data) {
        return write(Access.LOCAL, key, data, GraphProvider.localBuilder());
    }

    @Override
    public List<Stored> branches(Key project) {
        return branches(Access.LOCAL, project);
    }

    @Override
    public boolean delete(Key key) {
        return delete(Access.LOCAL, key);
    }

    @Override
    public GraphReader update(Key key, String base, GraphDelta delta) {
        return update(Access.LOCAL, key, base, delta, GraphProvider.localBuilder());
    }

    @Override
    public GraphReader link(Key key, Key source) {
        return link(Access.LOCAL, key, source);
    }

    /** Die Ablage aus Sicht eines Benutzers der GraphQL-API (eigener Bereich, Rechte auf die Projekte). */
    public GraphProvider forAccess(Access a) {
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
                return s.location(a, key);
            }

            @Override
            public State state(Key key) {
                return s.state(a, key);
            }

            @Override
            public GraphReader reader(Key key) {
                return s.reader(a, key);
            }

            @Override
            public GraphReader write(Key key, GraphFile data) {
                return s.write(a, key, data, GraphProvider.localBuilder());
            }

            @Override
            public List<Stored> branches(Key project) {
                return s.branches(a, project);
            }

            @Override
            public boolean delete(Key key) {
                return s.delete(a, key);
            }

            @Override
            public GraphReader update(Key key, String base, GraphDelta delta) {
                return s.update(a, key, base, delta, GraphProvider.localBuilder());
            }

            @Override
            public GraphReader link(Key key, Key source) {
                return s.link(a, key, source);
            }
        };
    }

    // ------------------------------------------------------------------ Lesen

    String location(Access a, Key key) {
        return locationOf(branchKey(a, key));
    }

    State state(Access a, Key key) {
        requireRead(a, key.projectId());
        Map<String, Object> b = branchRow(branchKey(a, key));
        if (b == null || b.get("graphId") == null || !(b.get("version") instanceof Number v)
                || v.intValue() != CodeGraph.VERSION) {
            return null;
        }
        Map<String, String> hashes = new HashMap<>();
        rows("sql", "SELECT path, sha256 FROM SourceFile WHERE g = :g", params("g", b.get("graphId")))
                .forEach(r -> hashes.put((String) r.get("path"), (String) r.get("sha256")));
        return new State((String) b.get("generator"), hashes);
    }

    GraphReader reader(Access a, Key key) {
        requireRead(a, key.projectId());
        Map<String, Object> b = branchRow(branchKey(a, key));
        if (b == null || b.get("graphId") == null) {
            return null;
        }
        return new ArcadeGraphReader(this, (String) b.get("graphId"), info(b));
    }

    /**
     * Leser für eine Generation (GraphQL: die App fragt mit der ID aus {@code graph}).
     *
     * @throws IllegalArgumentException wenn es sie nicht (mehr) gibt oder der Benutzer sie nicht sehen darf
     */
    public ArcadeGraphReader reader(Access a, String graphId) {
        List<Map<String, Object>> r = rows("sql", "SELECT FROM GraphBranch WHERE graphId = :g", params("g", graphId));
        if (r.isEmpty() || !readable(a, r.getFirst())) {
            throw new IllegalArgumentException("Graph " + graphId + " gibt es nicht mehr (inzwischen neu gebaut oder "
                    + "gelöscht) – erneut abfragen.");
        }
        return new ArcadeGraphReader(this, graphId, info(r.getFirst()));
    }

    List<Stored> branches(Access a, Key project) {
        requireRead(a, project.projectId());
        List<Stored> out = new ArrayList<>();
        for (Map<String, Object> b : rows("sql", "SELECT FROM GraphBranch WHERE projectKey = :p",
                params("p", projectKey(a, project)))) {
            if (b.get("graphId") == null) {
                continue; // erster Aufbau läuft noch oder ist abgebrochen
            }
            out.add(new Stored((String) b.get("branch"), (String) b.get("commitId"), (String) b.get("builtAt"),
                    number(b.get("files")), number(b.get("nodes")), number(b.get("edges")),
                    locationOf((String) b.get("branchKey")), (String) b.get("builtBy")));
        }
        out.sort(Comparator.comparing(s -> s.branch() == null ? "" : s.branch()));
        return out;
    }

    static long number(Object o) {
        return o instanceof Number n ? n.longValue() : 0;
    }

    // ------------------------------------------------------------------ Schreiben

    GraphReader write(Access a, Key key, GraphFile data, String builtBy) {
        String g = begin(a, key);
        try {
            writeFiles(a, g, data.files());
            writeNodes(a, g, data.nodes());
            writeEdges(a, g, data.edges());
            return publish(a, key, g, Header.of(data, builtBy));
        } catch (RuntimeException e) {
            try {
                abort(a, g);
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
    public String begin(Access a, Key key) {
        requireWrite(a, key.projectId());
        String g = UUID.randomUUID().toString();
        exec("sql", "UPDATE GraphBranch SET branchKey = :k, projectKey = :p, projectId = :pid, owner = :o, "
                        + "keyVersion = :kv, root = :root, project = :n, branch = :b, pendingGraphId = :g "
                        + "UPSERT WHERE branchKey = :k",
                params("k", branchKey(a, key), "p", projectKey(a, key), "pid", key.projectId(),
                        "o", key.projectId() == null ? a.owner() : "", "kv", KEY_VERSION, "root", key.root(),
                        "n", key.project(), "b", key.branch(), "g", g));
        return g;
    }

    /** Die Generation muss für einen Branch vorgemerkt sein, den der Benutzer bauen darf – sonst wird nicht geschrieben. */
    private void requirePending(Access a, String g) {
        List<Map<String, Object>> r = rows("sql", "SELECT FROM GraphBranch WHERE pendingGraphId = :g", params("g", g));
        if (r.isEmpty() || !writable(a, r.getFirst())) {
            throw new IllegalArgumentException("Kein laufender Aufbau mit der ID " + g + " (abgebrochen oder von einem "
                    + "neueren abgelöst).");
        }
    }

    private static void checkInterrupt() {
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("Graph-Aufbau abgebrochen", new InterruptedException());
        }
    }

    public void writeFiles(Access a, String g, List<FileEntry> files) {
        requirePending(a, g);
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

    public void writeNodes(Access a, String g, List<Node> nodes) {
        requirePending(a, g);
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

    public void writeEdges(Access a, String g, List<Edge> edges) {
        requirePending(a, g);
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
    public ArcadeGraphReader publish(Access a, Key key, String g, Header h) {
        requireWrite(a, key.projectId());
        String bkey = branchKey(a, key);
        List<Map<String, Object>> r = exec("sql", "UPDATE GraphBranch SET graphId = :g, pendingGraphId = null, "
                        + "commitId = :commit, builtAt = :builtAt, builtBy = :builtBy, generator = :generator, "
                        + "version = :version, files = :files, nodes = :nodes, edges = :edges, stats = :stats, "
                        + "communities = :communities, root = :root, project = :n "
                        + "RETURN AFTER WHERE branchKey = :k AND pendingGraphId = :g",
                params("g", g, "k", bkey, "commit", h.commit(), "builtAt", h.builtAt(), "builtBy", h.builtBy(),
                        "generator", h.generator(), "version", h.version(), "files", h.files(), "nodes", h.nodes(),
                        "edges", h.edges(), "stats", h.stats(), "communities", JSON.writeValueAsString(
                                h.communities() == null ? List.of() : h.communities()),
                        "root", key.root(), "n", key.project()));
        if (r.isEmpty()) {
            throw new IllegalStateException("Der Aufbau " + g + " für " + bkey + " wurde abgebrochen oder von einem "
                    + "neueren abgelöst.");
        }
        scheduleCleanup();
        return new ArcadeGraphReader(this, g, info(r.getFirst()));
    }

    /** Verwirft eine vorgemerkte Generation (der Aufräumer entfernt, was schon geschrieben ist). */
    public void abort(Access a, String g) {
        List<Map<String, Object>> r = rows("sql", "SELECT FROM GraphBranch WHERE pendingGraphId = :g", params("g", g));
        if (r.isEmpty() || !writable(a, r.getFirst())) {
            return;
        }
        exec("sql", "UPDATE GraphBranch SET pendingGraphId = null WHERE pendingGraphId = :g", params("g", g));
        scheduleCleanup();
    }

    boolean delete(Access a, Key key) {
        requireWrite(a, key.projectId());
        String bkey = branchKey(a, key);
        if (branchRow(bkey) == null) {
            return false;
        }
        exec("sql", "DELETE FROM GraphBranch WHERE branchKey = :k", params("k", bkey));
        scheduleCleanup();
        return true;
    }

    // ------------------------------------------------------------------ Inkrementell

    /** Kommandos einer Transaktion (siehe {@link #inTransaction}). */
    @FunctionalInterface
    interface Tx {
        void run(String language, String command, Map<String, Object> params);
    }

    /**
     * Führt mehrere Kommandos in einer Transaktion aus – eingebettet über die Datenbank, extern über eine eigene
     * Sitzung ({@link RemoteDatabase} führt je Instanz nur eine, deshalb nicht die gemeinsame).
     */
    private void inTransaction(java.util.function.Consumer<Tx> body) {
        BasicDatabase d = db();
        if (d instanceof Database local) {
            local.transaction(() -> body.accept((l, c, p) -> local.command(l, c, p).close()), false, RETRIES);
            return;
        }
        RemoteDatabase session = new RemoteDatabase(settings.host(), settings.port(), settings.database(),
                settings.user(), settings.password() == null ? "" : settings.password());
        try {
            session.begin();
            body.accept((l, c, p) -> session.command(l, c, p).close());
            session.commit();
        } catch (RuntimeException e) {
            try {
                session.rollback();
            } catch (RuntimeException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        } finally {
            session.close();
        }
    }

    /** In Portionen à {@link #BATCH}. */
    private static <T> void batches(List<T> items, java.util.function.Consumer<List<T>> each) {
        for (int i = 0; i < items.size(); i += BATCH) {
            checkInterrupt();
            each.accept(items.subList(i, Math.min(items.size(), i + BATCH)));
        }
    }

    private static Map<String, Object> edgeRow(Edge e) {
        Map<String, Object> p = new HashMap<>();
        p.put("conf", e.conf() == null || e.conf() == Confidence.EXTRACTED ? null : e.conf().name());
        p.put("score", e.score());
        p.put("count", e.count());
        p.put("line", e.line());
        return Map.of("f", e.from(), "t", e.to(), "p", p);
    }

    private static Map<String, Object> fileRow(FileEntry f) {
        Map<String, Object> r = new HashMap<>();
        r.put("path", f.path());
        r.put("sha256", f.sha256());
        r.put("lines", f.lines());
        r.put("parseErrors", Boolean.TRUE.equals(f.parseErrors()) ? Boolean.TRUE : null);
        return r;
    }

    /**
     * Wendet die Änderungen eines inkrementellen Aufbaus auf die Generation {@code base} an – in einer Transaktion,
     * samt Kopfdaten des Branches. Abgelehnt ({@code null}), wenn für den Branch inzwischen eine andere Generation gilt,
     * ein Aufbau läuft oder die Generation mit anderen Branches geteilt ist ({@link #link}) – dann neu schreiben.
     */
    public ArcadeGraphReader update(Access a, Key key, String base, GraphDelta delta, String builtBy) {
        requireWrite(a, key.projectId());
        String bkey = branchKey(a, key);
        Map<String, Object> b = branchRow(bkey);
        if (b == null || !base.equals(b.get("graphId")) || b.get("pendingGraphId") != null) {
            return null;
        }
        List<Map<String, Object>> users = rows("sql", "SELECT count(*) AS c FROM GraphBranch WHERE graphId = :g",
                params("g", base));
        if (users.isEmpty() || number(users.getFirst().get("c")) != 1) {
            return null; // geteilte Generation nicht verändern – der andere Branch hat seinen Stand
        }
        String g = base;
        GraphDelta.Header h = delta.header();
        inTransaction(tx -> {
            // 1. Kanten und Knoten entfernen (DETACH nimmt die Kanten entfernter Knoten mit)
            Map<Relation, List<Map<String, Object>>> removedByRel = new EnumMap<>(Relation.class);
            delta.removedEdges().forEach(e -> removedByRel.computeIfAbsent(e.rel(), r -> new ArrayList<>())
                    .add(edgeRow(e)));
            removedByRel.forEach((rel, rows) -> batches(rows, part -> tx.run(CYPHER, "UNWIND $rows AS r "
                    + "MATCH (a:CodeNode {uid: $g + '|' + r.f})-[e:" + rel.name() + "]->(b:CodeNode {uid: $g + '|' + "
                    + "r.t}) DELETE e", params("g", g, "rows", List.copyOf(part)))));
            batches(delta.removedNodes(), part -> tx.run(CYPHER, "UNWIND $ids AS id MATCH (n:CodeNode {uid: $g + '|' + "
                    + "id}) DETACH DELETE n", params("g", g, "ids", List.copyOf(part))));
            // 2. Knoten ändern bzw. anlegen
            batches(delta.changedNodes().stream().map(GraphStorage::nodeRow).toList(), part -> tx.run(CYPHER,
                    "UNWIND $rows AS r MATCH (n:CodeNode {uid: $g + '|' + r.id}) SET n = r, n.g = $g, "
                            + "n.uid = $g + '|' + r.id", params("g", g, "rows", List.copyOf(part))));
            Map<Kind, List<Map<String, Object>>> addedByKind = new EnumMap<>(Kind.class);
            delta.addedNodes().forEach(n -> addedByKind.computeIfAbsent(n.kind(), k -> new ArrayList<>())
                    .add(nodeRow(n)));
            addedByKind.forEach((kind, rows) -> batches(rows, part -> tx.run(CYPHER, "UNWIND $rows AS r CREATE (n:"
                    + typeOf(kind) + ") SET n = r, n.g = $g, n.uid = $g + '|' + r.id",
                    params("g", g, "rows", List.copyOf(part)))));
            // 3. Kanten ändern bzw. anlegen
            Map<Relation, List<Map<String, Object>>> changedByRel = new EnumMap<>(Relation.class);
            delta.changedEdges().forEach(e -> changedByRel.computeIfAbsent(e.rel(), r -> new ArrayList<>())
                    .add(edgeRow(e)));
            changedByRel.forEach((rel, rows) -> batches(rows, part -> tx.run(CYPHER, "UNWIND $rows AS r "
                    + "MATCH (a:CodeNode {uid: $g + '|' + r.f})-[e:" + rel.name() + "]->(b:CodeNode {uid: $g + '|' + "
                    + "r.t}) SET e = r.p", params("g", g, "rows", List.copyOf(part)))));
            Map<Relation, List<Map<String, Object>>> addedByRel = new EnumMap<>(Relation.class);
            delta.addedEdges().forEach(e -> addedByRel.computeIfAbsent(e.rel(), r -> new ArrayList<>())
                    .add(edgeRow(e)));
            addedByRel.forEach((rel, rows) -> batches(rows, part -> tx.run(CYPHER, "UNWIND $rows AS r "
                    + "MATCH (a:CodeNode {uid: $g + '|' + r.f}) MATCH (b:CodeNode {uid: $g + '|' + r.t}) "
                    + "CREATE (a)-[e:" + rel.name() + "]->(b) SET e = r.p", params("g", g, "rows", List.copyOf(part)))));
            // 4. Quelldateien ersetzen
            List<String> replaced = new ArrayList<>(delta.removedFiles());
            delta.files().forEach(f -> replaced.add(f.path()));
            batches(replaced, part -> tx.run("sql", "DELETE FROM SourceFile WHERE g = :g AND path IN :paths",
                    params("g", g, "paths", List.copyOf(part))));
            batches(delta.files().stream().map(GraphStorage::fileRow).toList(), part -> tx.run(CYPHER,
                    "UNWIND $rows AS r CREATE (f:SourceFile) SET f = r, f.g = $g",
                    params("g", g, "rows", List.copyOf(part))));
            // 5. Kopfdaten
            tx.run("sql", "UPDATE GraphBranch SET commitId = :commit, builtAt = :builtAt, builtBy = :builtBy, "
                            + "generator = :generator, version = :version, files = :files, nodes = :nodes, edges = :edges, "
                            + "stats = :stats, communities = :communities, root = :root, project = :n WHERE branchKey = :k",
                    params("k", bkey, "commit", h.commit(), "builtAt", h.builtAt(), "builtBy", builtBy,
                            "generator", h.generator(), "version", h.version(), "files", h.files(), "nodes", h.nodes(),
                            "edges", h.edges(), "stats", JSON.writeValueAsString(h.stats() == null ? Map.of() : h.stats()),
                            "communities", JSON.writeValueAsString(h.communities() == null ? List.of()
                                    : h.communities()), "root", key.root(), "n", key.project()));
        });
        return new ArcadeGraphReader(this, g, info(branchRow(bkey)));
    }

    /**
     * Übernimmt für den Branch von {@code key} die Generation von {@code source} (gleiches Projekt): kein Aufbau, keine
     * Kopie. Die bisherige Generation des Branches entfernt der Aufräumer.
     */
    public ArcadeGraphReader link(Access a, Key key, Key source) {
        requireWrite(a, key.projectId());
        if (!projectKey(a, key).equals(projectKey(a, source))) {
            throw new IllegalArgumentException("Graphen lassen sich nur innerhalb desselben Projekts übernehmen.");
        }
        Map<String, Object> s = branchRow(branchKey(a, source));
        if (s == null || s.get("graphId") == null) {
            return null;
        }
        String bkey = branchKey(a, key);
        exec("sql", "UPDATE GraphBranch SET branchKey = :k, projectKey = :p, projectId = :pid, owner = :o, "
                        + "keyVersion = :kv, root = :root, project = :n, branch = :b, pendingGraphId = null, graphId = :g, "
                        + "commitId = :commit, builtAt = :builtAt, builtBy = :builtBy, generator = :generator, "
                        + "version = :version, files = :files, nodes = :nodes, edges = :edges, stats = :stats, "
                        + "communities = :communities UPSERT WHERE branchKey = :k",
                params("k", bkey, "p", projectKey(a, key), "pid", key.projectId(),
                        "o", key.projectId() == null ? a.owner() : "", "kv", KEY_VERSION, "root", key.root(),
                        "n", key.project(), "b", key.branch(), "g", s.get("graphId"), "commit", s.get("commitId"),
                        "builtAt", s.get("builtAt"), "builtBy", s.get("builtBy"), "generator", s.get("generator"),
                        "version", s.get("version"), "files", s.get("files"), "nodes", s.get("nodes"),
                        "edges", s.get("edges"), "stats", s.get("stats"), "communities", s.get("communities")));
        scheduleCleanup();
        return new ArcadeGraphReader(this, (String) s.get("graphId"), info(branchRow(bkey)));
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
        Set<String> generations = new HashSet<>(generations("SourceFile"));
        generations.addAll(generations("CodeNode"));
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

    /** Nächste Generation nach {@code :last} – über den Index auf {@code g} in dessen Reihenfolge, ohne Scan. */
    static String nextGenerationQuery(String type) {
        return "SELECT g FROM `" + type + "` WHERE g > :last ORDER BY g LIMIT 1";
    }

    /**
     * Die Generationen eines Typs, von Schlüssel zu Schlüssel im Index auf {@code g} gesprungen: eine Abfrage je
     * Generation statt eines Durchlaufs über alle Knoten ({@code GROUP BY g} liest jeden Datensatz).
     */
    List<String> generations(String type) {
        List<String> out = new ArrayList<>();
        String q = nextGenerationQuery(type);
        String after = "";
        while (!closing) {
            List<Map<String, Object>> r = rows("sql", q, params("last", after));
            if (r.isEmpty() || !(r.getFirst().get("g") instanceof String g)) {
                break;
            }
            out.add(g);
            after = g;
        }
        return out;
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
