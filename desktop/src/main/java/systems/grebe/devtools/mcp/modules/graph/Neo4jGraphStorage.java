package systems.grebe.devtools.mcp.modules.graph;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Community;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Confidence;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Edge;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.FileEntry;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.GraphFile;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Kind;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Node;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Relation;

/**
 * Neo4j-Ablage über Spring Data Neo4j.
 *
 * <p>Datenmodell:
 * <pre>
 * (:GraphProject {root, name}) -[:HAS_BRANCH]-> (:GraphBranch {key, root, branch, commitId, graphId, stats, …})
 * (:SourceFile {g, path, sha256, lines, parseErrors})
 * (:CodeNode:&lt;Art&gt;[:Type|:Member] {g, uid = g|id, id, kind, name, file, line, endLine, modifiers, signature, doc, community,
 *                                   t = Typ-ID (Typ selbst bzw. Besitzer eines Members), ln/li = name/id klein})
 * (:CodeNode)-[:CALLS|EXTENDS|IMPLEMENTS|OVERRIDES|INSTANTIATES|HAS_TYPE|ANNOTATED_WITH|IMPORTS|CONTAINS
 *              {conf, score, count, line}]->(:CodeNode)
 * </pre>
 * {@code g} ist die {@code graphId} des Branches. {@code GraphProject}/{@code GraphBranch} sind Entities
 * ({@link GraphProjectEntity}, {@link GraphBranchEntity}) und werden über das {@code Neo4jTemplate} gespeichert; die
 * vielen Code-Knoten und Kanten schreibt {@code Neo4jClient} per {@code UNWIND}-Batches – Entity-Mapping wäre für
 * Graphen mit einer Million Kanten viel zu langsam.
 */
final class Neo4jGraphStorage implements GraphStorage {

    static final int BATCH = 10_000;

    static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final Neo4jConnection db;

    Neo4jGraphStorage(Neo4jConnection db) {
        this.db = db;
    }

    Neo4jConnection connection() {
        return db;
    }

    @Override
    public String describe() {
        return "Neo4j " + db.settings;
    }

    static String rootKey(Path root) {
        return root.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    static String branchKey(Key key) {
        return GraphBranchEntity.key(rootKey(key.root()), key.branch());
    }

    @Override
    public String location(Key key) {
        return "neo4j " + db.settings.uri() + " · GraphBranch " + branchKey(key);
    }

    GraphBranchEntity branchEntity(Key key) {
        db.ensureSchema();
        return db.template.findById(branchKey(key), GraphBranchEntity.class).orElse(null);
    }

    // ------------------------------------------------------------------ Lesen

    @Override
    public State state(Key key) {
        GraphBranchEntity b = branchEntity(key);
        if (b == null || b.getGraphId() == null || b.getVersion() != CodeGraph.VERSION) {
            return null;
        }
        Map<String, String> hashes = new HashMap<>();
        db.client.query("MATCH (f:SourceFile {g: $g}) RETURN f.path AS path, f.sha256 AS sha")
                .bind(b.getGraphId()).to("g").fetch().all()
                .forEach(r -> hashes.put((String) r.get("path"), (String) r.get("sha")));
        return new State(b.getGenerator(), hashes);
    }

    @Override
    public GraphReader reader(Key key) {
        GraphBranchEntity b = branchEntity(key);
        if (b == null || b.getGraphId() == null) {
            return null;
        }
        return new Neo4jGraphReader(db, b.getGraphId(), info(key, b));
    }

    GraphReader.GraphInfo info(Key key, GraphBranchEntity b) {
        Map<String, Object> stats = b.getStats() == null ? Map.of()
                : JSON.readValue(b.getStats(), new TypeReference<LinkedHashMap<String, Object>>() { });
        List<Community> communities = b.getCommunities() == null ? List.of()
                : JSON.readValue(b.getCommunities(), new TypeReference<List<Community>>() { });
        String project = b.getProject() != null ? b.getProject().getName() : key.project();
        return new GraphReader.GraphInfo(project, b.getBranch(), b.getCommitId(), b.getRoot(), b.getBuiltAt(),
                b.getGenerator(), stats, communities, location(key));
    }

    @Override
    public List<Stored> branches(Path root) {
        db.ensureSchema();
        String rootKey = rootKey(root);
        List<Stored> out = new ArrayList<>();
        for (GraphBranchEntity b : db.template.findAll("MATCH (b:GraphBranch {root: $root}) RETURN b",
                Map.of("root", rootKey), GraphBranchEntity.class)) {
            if (b.getGraphId() == null) {
                continue; // erster Aufbau läuft noch oder ist abgebrochen
            }
            out.add(new Stored(b.getBranch(), b.getCommitId(), b.getBuiltAt(), b.getFiles(), b.getNodes(), b.getEdges(),
                    "neo4j " + db.settings.uri() + " · GraphBranch " + b.getKey()));
        }
        out.sort(Comparator.comparing(s -> s.branch() == null ? "" : s.branch()));
        return out;
    }

    // ------------------------------------------------------------------ Schreiben

    @Override
    public GraphReader write(Key key, GraphFile data) {
        db.ensureSchema();
        String bkey = branchKey(key);
        String rootKey = rootKey(key.root());
        String graphId = UUID.randomUUID().toString();

        // 1. Branch anlegen bzw. neue Generation vormerken; Reste eines abgebrochenen Aufbaus entfernen
        String stale = db.tx.execute(s -> {
            GraphProjectEntity project = db.template.findById(rootKey, GraphProjectEntity.class)
                    .orElseGet(() -> new GraphProjectEntity(rootKey, key.project()));
            project.setName(key.project());
            GraphBranchEntity b = db.template.findById(bkey, GraphBranchEntity.class)
                    .orElseGet(() -> new GraphBranchEntity(bkey, rootKey, key.branch(), project));
            b.setProject(project);
            String previousPending = b.getPendingGraphId();
            b.setPendingGraphId(graphId);
            db.template.save(b);
            return previousPending;
        });
        if (stale != null) {
            deleteGeneration(stale);
        }

        try {
            // 2. Quelldateien, Knoten und Kanten der neuen Generation (je Batch eine Transaktion)
            writeFiles(graphId, data.files());
            writeNodes(graphId, data.nodes());
            writeEdges(graphId, data.edges());

            // 3. Umschalten auf die neue Generation – atomar
            String old = db.tx.execute(s -> {
                GraphBranchEntity b = db.template.findById(bkey, GraphBranchEntity.class).orElseThrow();
                String previous = b.getGraphId();
                b.publish(graphId, data.commit(), data.builtAt(), data.generator(), data.version(), data.files().size(),
                        data.nodes().size(), data.edges().size(), JSON.writeValueAsString(data.stats()),
                        JSON.writeValueAsString(data.communities()));
                db.template.save(b);
                return previous;
            });
            // 4. alte Generation entfernen
            if (old != null) {
                deleteGeneration(old);
            }
        } catch (RuntimeException e) {
            try {
                deleteGeneration(graphId);
            } catch (RuntimeException suppressed) {
                e.addSuppressed(suppressed); // bleibt als pendingGraphId stehen und wird beim nächsten Mal entfernt
            }
            throw e;
        }
        GraphBranchEntity b = branchEntity(key);
        return new Neo4jGraphReader(db, graphId, info(key, b));
    }

    private static void checkInterrupt() {
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("Graph-Aufbau abgebrochen", new InterruptedException());
        }
    }

    private void writeFiles(String g, List<FileEntry> files) {
        List<Map<String, Object>> rows = new ArrayList<>(Math.min(files.size(), BATCH));
        for (FileEntry f : files) {
            Map<String, Object> r = new HashMap<>();
            r.put("path", f.path());
            r.put("sha256", f.sha256());
            r.put("lines", f.lines());
            r.put("parseErrors", f.parseErrors());
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
        db.client.query("UNWIND $rows AS r CREATE (f:SourceFile) SET f = r, f.g = $g")
                .bind(g).to("g").bind(List.copyOf(rows)).to("rows").run();
        rows.clear();
    }

    /** Labels je Art: {@code CodeNode}, die Art ({@code Class}, {@code Method} …) und {@code Type}/{@code Member}. */
    static String labels(Kind kind) {
        String k = kind.name().charAt(0) + kind.name().substring(1).toLowerCase(Locale.ROOT);
        return ":CodeNode:" + k + (kind.isType() ? ":Type" : kind.isMember() ? ":Member" : "");
    }

    private void writeNodes(String g, List<Node> nodes) {
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
        // uid = g|id wird beim Schreiben ergänzt (eindeutig, ein Einzelschlüssel – verlässlicher Index-Seek)
        r.put("li", n.id().toLowerCase(Locale.ROOT));
        return r;
    }

    private void flushNodes(String g, Kind kind, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return;
        }
        checkInterrupt();
        // Labels können nicht als Parameter übergeben werden; sie stammen aus dem Enum, nicht aus Eingaben.
        db.client.query("UNWIND $rows AS r CREATE (n" + labels(kind) + ") SET n = r, n.g = $g, n.uid = $g + '|' + r.id")
                .bind(g).to("g").bind(List.copyOf(rows)).to("rows").run();
        rows.clear();
    }

    private void writeEdges(String g, List<Edge> edges) {
        Map<Relation, List<Map<String, Object>>> byRel = new EnumMap<>(Relation.class);
        for (Edge e : edges) {
            List<Map<String, Object>> rows = byRel.computeIfAbsent(e.rel(), k -> new ArrayList<>());
            Map<String, Object> p = new HashMap<>();
            p.put("conf", e.conf() == Confidence.EXTRACTED ? null : e.conf().name());
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
        db.client.query("UNWIND $rows AS r "
                        + "MATCH (a:CodeNode {uid: $g + '|' + r.f}) MATCH (b:CodeNode {uid: $g + '|' + r.t}) "
                        + "CREATE (a)-[e:" + rel.name() + "]->(b) SET e = r.p")
                .bind(g).to("g").bind(List.copyOf(rows)).to("rows").run();
        rows.clear();
    }

    // ------------------------------------------------------------------ Löschen

    /** Entfernt Knoten, Kanten und Quelldateien einer Generation in Batches. */
    void deleteGeneration(String g) {
        for (String q : List.of(
                "MATCH (n:CodeNode {g: $g}) WITH n LIMIT 5000 DETACH DELETE n RETURN count(*) AS c",
                "MATCH (f:SourceFile {g: $g}) WITH f LIMIT 20000 DELETE f RETURN count(*) AS c")) {
            long deleted;
            do {
                deleted = db.client.query(q).bind(g).to("g").fetchAs(Long.class)
                        .mappedBy((t, r) -> r.get("c").asLong()).one().orElse(0L);
            } while (deleted > 0);
        }
    }

    @Override
    public boolean delete(Path root, String branch) {
        Key key = new Key(null, root, branch);
        GraphBranchEntity b = branchEntity(key);
        if (b == null) {
            return false;
        }
        if (b.getPendingGraphId() != null) {
            deleteGeneration(b.getPendingGraphId());
        }
        if (b.getGraphId() != null) {
            deleteGeneration(b.getGraphId());
        }
        db.template.deleteById(b.getKey(), GraphBranchEntity.class);
        // Projekt ohne Branches entfernen
        db.client.query("MATCH (p:GraphProject {root: $root}) WHERE NOT (p)-[:HAS_BRANCH]->() DELETE p")
                .bind(rootKey(root)).to("root").run();
        return true;
    }
}
