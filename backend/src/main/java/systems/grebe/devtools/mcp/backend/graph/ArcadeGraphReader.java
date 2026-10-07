package systems.grebe.devtools.mcp.backend.graph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.arcadedb.database.Document;
import com.arcadedb.graph.Vertex;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Confidence;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Edge;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Kind;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Node;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Relation;
import systems.grebe.devtools.mcp.modules.graph.GraphReader;

/**
 * {@link GraphReader} auf der {@link GraphStorage}: jede Methode ist eine OpenCypher-Abfrage auf den Knoten mit
 * {@code g = graphId}; der Graph wird nie als Ganzes geladen. Relationship-Typen und Labels im Abfragetext stammen
 * ausschließlich aus den Enums, Eingaben gehen immer als Parameter. Knoten und Kanten kommen als Maps zurück
 * ({@code properties(n)}), damit eingebettete und externe Datenbank dieselben Werte liefern.
 */
public final class ArcadeGraphReader implements GraphReader {

    private static final String CYPHER = GraphStorage.CYPHER;
    private static final String EDGE = "{f: a.id, t: b.id, r: type(e), p: properties(e)}";
    private static final Pattern RETURN_ALIAS = Pattern.compile(
            "(?is).*\\bRETURN\\s+(?:DISTINCT\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s*(?:(?:ORDER|SKIP|LIMIT)\\b.*)?");

    private final GraphStorage storage;
    private final String g;
    private final GraphInfo info;

    ArcadeGraphReader(GraphStorage storage, String graphId, GraphInfo info) {
        this.storage = storage;
        this.g = graphId;
        this.info = info;
    }

    /** ID der Generation, z.B. für die GraphQL-API. */
    public String graphId() {
        return g;
    }

    @Override
    public GraphInfo info() {
        return info;
    }

    // ------------------------------------------------------------------ Hilfen

    private List<Map<String, Object>> rows(String cypher, Map<String, Object> params) {
        Map<String, Object> p = new HashMap<>(params);
        p.put("g", g);
        return storage.rows(CYPHER, cypher, p);
    }

    private List<Node> nodeQuery(String cypher, Map<String, Object> params) {
        List<Node> out = new ArrayList<>();
        rows(cypher, params).forEach(r -> out.add(node(r.get("n"))));
        return out;
    }

    private List<Edge> edgeQuery(String cypher, Map<String, Object> params) {
        List<Edge> out = new ArrayList<>();
        rows(cypher, params).forEach(r -> out.add(edge(r.get("e"))));
        return out;
    }

    /** Knoten aus {@code properties(n)}. */
    static Node node(Object value) {
        Map<?, ?> n = (Map<?, ?>) value;
        return new Node((String) n.get("id"), Kind.valueOf((String) n.get("kind")), (String) n.get("name"),
                (String) n.get("file"), integer(n.get("line")), integer(n.get("endLine")), (String) n.get("modifiers"),
                (String) n.get("signature"), (String) n.get("doc"), integer(n.get("community")));
    }

    /** Kante aus {@code {f, t, r, p: properties(e)}}. */
    static Edge edge(Object value) {
        Map<?, ?> v = (Map<?, ?>) value;
        Map<?, ?> p = v.get("p") instanceof Map<?, ?> m ? m : Map.of();
        Object conf = p.get("conf");
        return new Edge((String) v.get("f"), (String) v.get("t"), Relation.valueOf((String) v.get("r")),
                conf == null ? Confidence.EXTRACTED : Confidence.valueOf((String) conf),
                p.get("score") instanceof Number s ? s.doubleValue() : null, integer(p.get("count")),
                integer(p.get("line")));
    }

    private static Integer integer(Object v) {
        return v instanceof Number n ? n.intValue() : null;
    }

    /** Relationship-Typen als Muster, z.B. {@code :CALLS|EXTENDS}; leer = alle. */
    private static String types(Set<Relation> rels) {
        if (rels == null || rels.size() == Relation.values().length) {
            return "";
        }
        return ":" + rels.stream().map(Relation::name).sorted().collect(Collectors.joining("|"));
    }

    // ------------------------------------------------------------------ Knoten

    @Override
    public Node node(String id) {
        List<Node> hits = nodeQuery("MATCH (n:CodeNode {uid: $g + '|' + $id}) RETURN properties(n) AS n",
                Map.of("id", id));
        return hits.isEmpty() ? null : hits.getFirst();
    }

    @Override
    public Map<String, Node> nodes(Collection<String> ids) {
        Map<String, Node> out = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        for (Node n : nodeQuery("UNWIND $ids AS id MATCH (n:CodeNode {uid: $g + '|' + id}) RETURN properties(n) AS n",
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
        rows("UNWIND $ids AS id MATCH (n:CodeNode {uid: $g + '|' + id}) "
                // aus- und eingehend getrennt, damit Selbstkanten wie im Speicher doppelt zählen
                + "RETURN id, COUNT { (n)-[e]->() WHERE type(e) <> 'CONTAINS' } "
                + "+ COUNT { (n)<-[e]-() WHERE type(e) <> 'CONTAINS' } AS d", Map.of("ids", List.copyOf(Set.copyOf(ids))))
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
                + "RETURN properties(n) AS n ORDER BY n.id", p);
        if (out.isEmpty()) {
            out = nodeQuery("MATCH (n:External {g: $g}) WHERE n.id = $name OR n.id ENDS WITH $suffix "
                    + "RETURN properties(n) AS n ORDER BY n.id", p);
        }
        return out;
    }

    @Override
    public List<Node> filesNamed(String name) {
        return nodeQuery("MATCH (n:File {g: $g}) WHERE n.name = $name OR n.name ENDS WITH $suffix "
                + "RETURN properties(n) AS n ORDER BY n.id", Map.of("name", name, "suffix", "/" + name));
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
                + "RETURN properties(n) AS n LIMIT $limit", p);
    }

    // ------------------------------------------------------------------ Bericht

    private List<Map.Entry<String, Integer>> ranking(String cypher, int limit) {
        List<Map.Entry<String, Integer>> out = new ArrayList<>();
        rows(cypher, Map.of("limit", limit))
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
        List<TypeLink> out = new ArrayList<>();
        rows("MATCH (a:CodeNode {g: $g})-[e:CALLS|INSTANTIATES|HAS_TYPE]->(b) "
                + "WHERE a.t IS NOT NULL AND b.t IS NOT NULL AND a.community IS NOT NULL "
                + "AND b.community IS NOT NULL AND a.community <> b.community "
                + "WITH a, b, e ORDER BY a.id, b.id, type(e) "
                + "WITH a.t AS ta, b.t AS tb, sum(coalesce(e.count, 1)) AS w, head(collect(" + EDGE + ")) AS s "
                + "RETURN ta, tb, w, s ORDER BY ta, tb", Map.of())
                .forEach(r -> out.add(new TypeLink((String) r.get("ta"), (String) r.get("tb"),
                        ((Number) r.get("w")).intValue(), edge(r.get("s")))));
        return out;
    }

    @Override
    public Map<String, Integer> callersByType(String typeId) {
        Map<String, Integer> out = new HashMap<>();
        rows("MATCH (:CodeNode {uid: $g + '|' + $id})-[:CONTAINS]->(m)<-[e:CALLS|OVERRIDES]-(c) "
                + "WHERE c.t IS NOT NULL AND c.t <> $id RETURN c.t AS t, sum(coalesce(e.count, 1)) AS c",
                Map.of("id", typeId))
                .forEach(r -> out.put((String) r.get("t"), ((Number) r.get("c")).intValue()));
        return out;
    }

    @Override
    public List<String> parseErrorFiles(int limit) {
        List<String> out = new ArrayList<>();
        rows("MATCH (f:SourceFile {g: $g}) WHERE f.parseErrors = true RETURN f.path AS path ORDER BY path "
                + "LIMIT $limit", Map.of("limit", limit)).forEach(r -> out.add((String) r.get("path")));
        return out;
    }

    // ------------------------------------------------------------------ Pfad

    @Override
    public List<Edge> shortestPath(String from, String to, Set<Relation> rels, boolean directed, int maxDepth) {
        if (rels.isEmpty()) {
            return null;
        }
        int depth = Math.max(1, Math.min(maxDepth, 50));
        String pattern = "(a)-[" + types(rels) + "*.." + depth + "]-" + (directed ? ">" : "") + "(b)";
        List<List<Edge>> paths = new ArrayList<>();
        for (Map<String, Object> r : rows("MATCH (a:CodeNode {uid: $g + '|' + $from}), "
                + "(b:CodeNode {uid: $g + '|' + $to}) "
                + "MATCH p = allShortestPaths(" + pattern + ") "
                + "WHERE all(n IN nodes(p)[1..-1] WHERE n.kind <> 'EXTERNAL' AND n.kind <> 'PACKAGE') "
                + "RETURN [r IN relationships(p) | {f: startNode(r).id, t: endNode(r).id, r: type(r), "
                + "p: properties(r)}] AS steps LIMIT 500", Map.of("from", from, "to", to))) {
            List<Edge> steps = new ArrayList<>();
            ((List<?>) r.get("steps")).forEach(s -> steps.add(edge(s)));
            paths.add(steps);
        }
        return paths.isEmpty() ? null : GraphReader.smallest(paths, from);
    }

    // ------------------------------------------------------------------ freie Abfrage

    /**
     * Führt eine lesende OpenCypher-Abfrage aus; {@code $g} ist die graphId dieses Graphen. ArcadeDB lehnt schreibende
     * Klauseln in einer Abfrage ab ({@code QueryNotIdempotentException}).
     */
    @Override
    public QueryResult query(String query, Map<String, Object> params, int maxRows) {
        Map<String, Object> p = new HashMap<>(params == null ? Map.of() : params);
        p.put("g", g);
        List<String> columns = new ArrayList<>();
        List<List<Object>> rows = new ArrayList<>();
        boolean truncated = false;
        try (ResultSet rs = storage.db().query(CYPHER, query, p)) {
            while (rs.hasNext()) {
                Result rec = rs.next();
                if (rows.size() >= maxRows) {
                    truncated = true;
                    break;
                }
                if (rec.isElement()) {
                    // Externe Datenbank: ein einzeln zurückgegebener Knoten kommt als Zeile selbst, ohne Spaltennamen
                    if (columns.isEmpty()) {
                        columns.add(returnAlias(query));
                    }
                    rows.add(List.of(plain(rec.getElement().orElseThrow())));
                    continue;
                }
                for (String k : rec.getPropertyNames()) {
                    if (!columns.contains(k)) {
                        columns.add(k);
                    }
                }
                List<Object> row = new ArrayList<>(columns.size());
                for (String k : columns) {
                    row.add(plain(rec.getProperty(k)));
                }
                rows.add(row);
            }
        } catch (com.arcadedb.exception.QueryNotIdempotentException e) {
            throw new IllegalArgumentException("graph_cypher ist nur lesend – schreibende Klauseln sind nicht erlaubt. "
                    + "Den Graphen ändert nur graph_build.", e);
        } catch (RuntimeException e) {
            String msg = GraphStorage.rootMessage(e);
            if (msg.contains("not idempotent")) { // externe Datenbank: Fehler kommt als Text über HTTP
                throw new IllegalArgumentException("graph_cypher ist nur lesend – schreibende Klauseln sind nicht "
                        + "erlaubt. Den Graphen ändert nur graph_build.", e);
            }
            throw new IllegalArgumentException("Abfrage fehlgeschlagen: " + msg, e);
        }
        return new QueryResult(columns, rows, truncated);
    }

    /** Spaltenname für {@code RETURN n}; sonst {@code result}. */
    static String returnAlias(String query) {
        Matcher m = RETURN_ALIAS.matcher(query.strip());
        return m.matches() ? m.group(1) : "result";
    }

    /**
     * Wert für die Ausgabe: Code-Knoten als Kurzform (ID + Art), Kanten als Typ mit Properties, sonst einfache
     * Java-Objekte. Externe Datenbanken liefern Knoten als Maps mit {@code @cat}/{@code @type}.
     */
    static Object plain(Object v) {
        return switch (v) {
            case null -> null;
            case com.arcadedb.graph.Edge e -> edgeText(e.getTypeName(), e.toMap(false));
            case Vertex n -> nodeValue(n.getTypeName(), n.toMap(false));
            case Document d -> strip(d.toMap(false));
            case Result r -> plain(r.isElement() ? r.getElement().orElseThrow() : GraphStorage.row(r));
            case Map<?, ?> m when "v".equals(m.get("@cat")) -> nodeValue((String) m.get("@type"), m);
            case Map<?, ?> m when "e".equals(m.get("@cat")) -> edgeText((String) m.get("@type"), m);
            case Map<?, ?> m -> {
                Map<String, Object> out = new LinkedHashMap<>();
                m.forEach((k, x) -> {
                    if (!String.valueOf(k).startsWith("@")) {
                        out.put(String.valueOf(k), plain(x));
                    }
                });
                yield out;
            }
            case Collection<?> c -> c.stream().map(ArcadeGraphReader::plain).toList();
            default -> v;
        };
    }

    private static Object nodeValue(String type, Map<?, ?> props) {
        if (props.get("id") instanceof String id && props.get("kind") instanceof String kind) {
            return id + " [" + kind.toLowerCase() + "]";
        }
        Map<String, Object> m = strip(props);
        m.remove("stats");
        m.remove("communities");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(":" + type, true);
        out.putAll(m);
        return out;
    }

    private static String edgeText(String type, Map<?, ?> props) {
        Map<String, Object> m = strip(props);
        return ":" + type + (m.isEmpty() ? "" : " " + m);
    }

    private static Map<String, Object> strip(Map<?, ?> props) {
        Map<String, Object> m = new LinkedHashMap<>();
        props.forEach((k, x) -> {
            String key = String.valueOf(k);
            if (!key.startsWith("@") && !key.equals("g") && !key.equals("uid") && !key.equals("ln")
                    && !key.equals("li")) {
                m.put(key, x);
            }
        });
        return m;
    }
}
