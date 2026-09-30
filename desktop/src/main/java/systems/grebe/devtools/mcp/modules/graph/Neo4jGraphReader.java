package systems.grebe.devtools.mcp.modules.graph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.neo4j.driver.Value;
import org.neo4j.driver.types.MapAccessor;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Confidence;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Edge;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Kind;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Node;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Relation;

/**
 * {@link GraphReader} auf der Neo4j-Ablage: jede Methode ist eine Cypher-Abfrage auf den Knoten mit
 * {@code g = graphId}; der Graph wird nie als Ganzes geladen. Relationship-Typen und Labels im Cypher-Text stammen
 * ausschließlich aus den Enums, Eingaben gehen immer als Parameter.
 */
final class Neo4jGraphReader implements GraphReader {

    private static final String EDGE = "{f: a.id, t: b.id, r: type(e), p: properties(e)}";

    private final Neo4jConnection db;
    private final String g;
    private final GraphInfo info;

    Neo4jGraphReader(Neo4jConnection db, String graphId, GraphInfo info) {
        this.db = db;
        this.g = graphId;
        this.info = info;
    }

    String graphId() {
        return g;
    }

    @Override
    public GraphInfo info() {
        return info;
    }

    // ------------------------------------------------------------------ Hilfen

    private List<Node> nodeQuery(String cypher, Map<String, Object> params) {
        Map<String, Object> p = new HashMap<>(params);
        p.put("g", g);
        return new ArrayList<>(db.client.query(cypher).bindAll(p).fetchAs(Node.class)
                .mappedBy((t, r) -> node(r.get("n"))).all());
    }

    private List<Edge> edgeQuery(String cypher, Map<String, Object> params) {
        Map<String, Object> p = new HashMap<>(params);
        p.put("g", g);
        return new ArrayList<>(db.client.query(cypher).bindAll(p).fetchAs(Edge.class)
                .mappedBy((t, r) -> edge(r.get("e"))).all());
    }

    static Node node(Value v) {
        MapAccessor n = v.asNode();
        return new Node(n.get("id").asString(), Kind.valueOf(n.get("kind").asString()), n.get("name").asString(),
                str(n.get("file")), integer(n.get("line")), integer(n.get("endLine")), str(n.get("modifiers")),
                str(n.get("signature")), str(n.get("doc")), integer(n.get("community")));
    }

    static Edge edge(Value v) {
        Value p = v.get("p");
        Value conf = p.get("conf");
        Value score = p.get("score");
        return new Edge(v.get("f").asString(), v.get("t").asString(), Relation.valueOf(v.get("r").asString()),
                conf.isNull() ? Confidence.EXTRACTED : Confidence.valueOf(conf.asString()),
                score.isNull() ? null : score.asDouble(), integer(p.get("count")), integer(p.get("line")));
    }

    private static String str(Value v) {
        return v == null || v.isNull() ? null : v.asString();
    }

    private static Integer integer(Value v) {
        return v == null || v.isNull() ? null : v.asInt();
    }

    /** Relationship-Typen als Cypher-Muster, z.B. {@code :CALLS|EXTENDS}; leer = alle. */
    private static String types(Set<Relation> rels) {
        if (rels == null || rels.size() == Relation.values().length) {
            return "";
        }
        return ":" + rels.stream().map(Relation::name).sorted().collect(Collectors.joining("|"));
    }

    // ------------------------------------------------------------------ Knoten

    @Override
    public Node node(String id) {
        List<Node> hits = nodeQuery("MATCH (n:CodeNode {uid: $g + '|' + $id}) RETURN n", Map.of("id", id));
        return hits.isEmpty() ? null : hits.getFirst();
    }

    @Override
    public Map<String, Node> nodes(Collection<String> ids) {
        Map<String, Node> out = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        for (Node n : nodeQuery("UNWIND $ids AS id MATCH (n:CodeNode {uid: $g + '|' + id}) RETURN n",
                Map.of("ids", List.copyOf(Set.copyOf(ids))))) {
            out.put(n.id(), n);
        }
        return out;
    }

    @Override
    public List<Edge> edges(Collection<String> ids, Direction dir, Set<Relation> rels) {
        if (ids.isEmpty() || (rels != null && rels.isEmpty())) {
            return List.of();
        }
        String t = types(rels);
        Map<String, Object> p = Map.of("ids", List.copyOf(ids));
        List<Edge> out = new ArrayList<>();
        if (dir != Direction.IN) {
            out.addAll(edgeQuery("UNWIND $ids AS id MATCH (a:CodeNode {uid: $g + '|' + id})-[e" + t + "]->(b) "
                    + "WITH a, b, e ORDER BY b.id, type(e) RETURN " + EDGE + " AS e", p));
        }
        if (dir != Direction.OUT) {
            out.addAll(edgeQuery("UNWIND $ids AS id MATCH (b:CodeNode {uid: $g + '|' + id})<-[e" + t + "]-(a) "
                    + "WITH a, b, e ORDER BY a.id, type(e) RETURN " + EDGE + " AS e", p));
        }
        return out;
    }

    @Override
    public Map<String, Integer> degrees(Collection<String> ids) {
        Map<String, Integer> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        db.client.query("UNWIND $ids AS id MATCH (n:CodeNode {uid: $g + '|' + id}) "
                        // aus- und eingehend getrennt, damit Selbstkanten wie im Speicher doppelt zählen
                        + "RETURN id, COUNT { (n)-[e]->() WHERE type(e) <> 'CONTAINS' } "
                        + "+ COUNT { (n)<-[e]-() WHERE type(e) <> 'CONTAINS' } AS d")
                .bind(g).to("g").bind(List.copyOf(Set.copyOf(ids))).to("ids").fetch().all()
                .forEach(r -> out.put((String) r.get("id"), ((Number) r.get("d")).intValue()));
        return out;
    }

    @Override
    public List<Node> typesNamed(String name) {
        Node exact = node(name);
        if (exact != null && (exact.kind().isType() || exact.kind() == Kind.EXTERNAL)) {
            return List.of(exact);
        }
        Map<String, Object> p = Map.of("name", name, "suffix", "." + name);
        List<Node> out = nodeQuery("MATCH (n:Type {g: $g}) WHERE n.id = $name OR n.id ENDS WITH $suffix "
                + "RETURN n ORDER BY n.id", p);
        if (out.isEmpty()) {
            out = nodeQuery("MATCH (n:External {g: $g}) WHERE n.id = $name OR n.id ENDS WITH $suffix "
                    + "RETURN n ORDER BY n.id", p);
        }
        return out;
    }

    @Override
    public List<Node> filesNamed(String name) {
        return nodeQuery("MATCH (n:File {g: $g}) WHERE n.name = $name OR n.name ENDS WITH $suffix RETURN n ORDER BY n.id",
                Map.of("name", name, "suffix", "/" + name));
    }

    @Override
    public List<Node> search(NodeSearch s) {
        Map<String, Object> p = new HashMap<>();
        p.put("kinds", s.kinds() == null ? null : s.kinds().stream().map(Kind::name).toList());
        p.put("needles", s.needles() == null ? List.of() : s.needles());
        p.put("doc", s.withDoc());
        p.put("nameRx", s.nameRegex());
        p.put("idRx", s.idRegex());
        p.put("limit", s.limit());
        return nodeQuery("MATCH (n:CodeNode {g: $g}) "
                + "WHERE ($kinds IS NULL OR n.kind IN $kinds) AND ("
                + " any(x IN $needles WHERE n.ln CONTAINS x OR n.li CONTAINS x"
                + "     OR ($doc AND n.doc IS NOT NULL AND toLower(n.doc) CONTAINS x))"
                + " OR ($nameRx IS NOT NULL AND n.ln =~ $nameRx) OR ($idRx IS NOT NULL AND n.li =~ $idRx)) "
                + "RETURN n LIMIT $limit", p);
    }

    // ------------------------------------------------------------------ Bericht

    private List<Map.Entry<String, Integer>> ranking(String cypher, int limit) {
        List<Map.Entry<String, Integer>> out = new ArrayList<>();
        db.client.query(cypher).bind(g).to("g").bind(limit).to("limit").fetch().all()
                .forEach(r -> out.add(Map.entry((String) r.get("id"), ((Number) r.get("c")).intValue())));
        return out;
    }

    @Override
    public List<Map.Entry<String, Integer>> topTypes(int limit) {
        return ranking("MATCH (a:CodeNode {g: $g})-[e]->(b) WHERE NOT type(e) IN ['CONTAINS', 'IMPORTS'] "
                + "WITH a.t AS ta, b.t AS tb WHERE ta IS NULL OR tb IS NULL OR ta <> tb "
                + "UNWIND [x IN [ta, tb] WHERE x IS NOT NULL] AS id "
                + "RETURN id, count(*) AS c ORDER BY c DESC, id LIMIT $limit", limit);
    }

    @Override
    public List<Map.Entry<String, Integer>> mostCalled(int limit) {
        return ranking("MATCH (:CodeNode {g: $g})-[e:CALLS]->(b) "
                + "RETURN b.id AS id, sum(coalesce(e.count, 1)) AS c ORDER BY c DESC, id LIMIT $limit", limit);
    }

    @Override
    public List<TypeLink> typeLinks() {
        // Member tragen die Community ihres Typs – a.community ist also die Community von a.t
        return new ArrayList<>(db.client.query("MATCH (a:CodeNode {g: $g})-[e:CALLS|INSTANTIATES|HAS_TYPE]->(b) "
                        + "WHERE a.t IS NOT NULL AND b.t IS NOT NULL AND a.community IS NOT NULL "
                        + "AND b.community IS NOT NULL AND a.community <> b.community "
                        + "WITH a, b, e ORDER BY a.id, b.id, type(e) "
                        + "WITH a.t AS ta, b.t AS tb, sum(coalesce(e.count, 1)) AS w, head(collect(" + EDGE + ")) AS s "
                        + "RETURN ta, tb, w, s ORDER BY ta, tb")
                .bind(g).to("g").fetchAs(TypeLink.class)
                .mappedBy((t, r) -> new TypeLink(r.get("ta").asString(), r.get("tb").asString(), r.get("w").asInt(),
                        edge(r.get("s"))))
                .all());
    }

    @Override
    public Map<String, Integer> callersByType(String typeId) {
        Map<String, Integer> out = new HashMap<>();
        db.client.query("MATCH (:CodeNode {uid: $g + '|' + $id})-[:CONTAINS]->(m)<-[e:CALLS|OVERRIDES]-(c) "
                        + "WHERE c.t IS NOT NULL AND c.t <> $id RETURN c.t AS t, sum(coalesce(e.count, 1)) AS c")
                .bind(g).to("g").bind(typeId).to("id").fetch().all()
                .forEach(r -> out.put((String) r.get("t"), ((Number) r.get("c")).intValue()));
        return out;
    }

    @Override
    public List<String> parseErrorFiles(int limit) {
        return new ArrayList<>(db.client.query("MATCH (f:SourceFile {g: $g}) WHERE f.parseErrors "
                        + "RETURN f.path AS path ORDER BY path LIMIT $limit")
                .bind(g).to("g").bind(limit).to("limit").fetchAs(String.class)
                .mappedBy((t, r) -> r.get("path").asString()).all());
    }

    // ------------------------------------------------------------------ Pfad

    @Override
    public List<Edge> shortestPath(String from, String to, Set<Relation> rels, boolean directed, int maxDepth) {
        if (rels.isEmpty()) {
            return null;
        }
        int depth = Math.max(1, Math.min(maxDepth, 50));
        String pattern = "(a)-[" + types(rels) + "*.." + depth + "]-" + (directed ? ">" : "") + "(b)";
        List<List<Edge>> paths = new ArrayList<>(db.client.query(
                        "MATCH (a:CodeNode {uid: $g + '|' + $from}), (b:CodeNode {uid: $g + '|' + $to}) "
                                + "MATCH p = allShortestPaths(" + pattern + ") "
                                + "WHERE all(n IN nodes(p)[1..-1] WHERE NOT n:External AND NOT n:Package) "
                                + "RETURN [r IN relationships(p) | {f: startNode(r).id, t: endNode(r).id, r: type(r), "
                                + "p: properties(r)}] AS steps LIMIT 500")
                .bind(g).to("g").bind(from).to("from").bind(to).to("to")
                .fetchAs((Class<List<Edge>>) (Class<?>) List.class)
                .mappedBy((t, r) -> r.get("steps").asList(Neo4jGraphReader::edge))
                .all());
        return paths.isEmpty() ? null : GraphReader.smallest(paths, from);
    }

    // ------------------------------------------------------------------ freie Abfrage

    /**
     * Führt eine lesende Cypher-Abfrage aus; {@code $g} ist die graphId dieses Graphen. Die Transaktion läuft im
     * Lesemodus – schreibende Klauseln lehnt Neo4j ab.
     *
     * @return Spaltennamen und höchstens {@code maxRows} Zeilen; {@code truncated} = es gab mehr
     */
    CypherResult cypher(String query, Map<String, Object> params, int maxRows) {
        Map<String, Object> p = new HashMap<>(params == null ? Map.of() : params);
        p.put("g", g);
        var config = org.neo4j.driver.SessionConfig.builder().withDefaultAccessMode(org.neo4j.driver.AccessMode.READ);
        if (db.settings.database() != null) {
            config.withDatabase(db.settings.database());
        }
        try (var session = db.driver.session(config.build())) {
            return session.executeRead(tx -> {
                var result = tx.run(query, p);
                List<String> keys = result.keys();
                List<List<Object>> rows = new ArrayList<>();
                boolean truncated = false;
                while (result.hasNext()) {
                    var rec = result.next();
                    if (rows.size() >= maxRows) {
                        truncated = true;
                        break;
                    }
                    List<Object> row = new ArrayList<>(keys.size());
                    for (String k : keys) {
                        row.add(plain(rec.get(k)));
                    }
                    rows.add(row);
                }
                result.consume();
                return new CypherResult(keys, rows, truncated);
            });
        }
    }

    record CypherResult(List<String> columns, List<List<Object>> rows, boolean truncated) {
    }

    /** Wert für die Ausgabe: Knoten als Kurzform (ID + Art), Kanten als Typ, sonst Java-Objekte. */
    private static Object plain(Value v) {
        if (v == null || v.isNull()) {
            return null;
        }
        return switch (v.type().name()) {
            case "NODE" -> {
                var n = v.asNode();
                if (n.containsKey("id") && n.containsKey("kind")) {
                    yield n.get("id").asString() + " [" + n.get("kind").asString().toLowerCase() + "]";
                }
                Map<String, Object> m = new LinkedHashMap<>();
                n.labels().forEach(l -> m.put(":" + l, true));
                n.asMap().forEach((k, x) -> {
                    if (!k.equals("stats") && !k.equals("communities")) {
                        m.put(k, x);
                    }
                });
                yield m;
            }
            case "RELATIONSHIP" -> {
                var r = v.asRelationship();
                yield ":" + r.type() + (r.size() == 0 ? "" : " " + r.asMap());
            }
            case "PATH" -> {
                List<Object> steps = new ArrayList<>();
                v.asPath().forEach(seg -> steps.add(plain(org.neo4j.driver.Values.value(seg.start())) + " -:"
                        + seg.relationship().type() + "-> " + plain(org.neo4j.driver.Values.value(seg.end()))));
                yield steps;
            }
            case "LIST OF ANY?", "LIST" -> v.asList(Neo4jGraphReader::plain);
            case "MAP" -> {
                Map<String, Object> m = new LinkedHashMap<>();
                v.keys().forEach(k -> m.put(k, plain(v.get(k))));
                yield m;
            }
            default -> v.asObject();
        };
    }
}
