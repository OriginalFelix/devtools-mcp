package systems.grebe.devtools.mcp.remote;

import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Community;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Edge;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.GraphFile;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Node;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Relation;
import systems.grebe.devtools.mcp.modules.graph.GraphProvider;
import systems.grebe.devtools.mcp.modules.graph.GraphReader;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link GraphProvider} über die GraphQL-API des Backends ({@code graph.graphqls}) – für Desktop-Apps, die nicht im
 * Local-Mode laufen, sondern mit einem Team-Server. Der Server legt die Graphen in seiner Graph-Storage ab, getrennt
 * je Benutzer; jede Abfrage des {@link GraphReader} ist eine GraphQL-Operation. Gespeichert wird in Portionen
 * ({@code beginGraph} → {@code writeGraph*} → {@code publishGraph}), damit auch große Graphen in Requests normaler
 * Größe passen.
 */
public class GraphQlGraphProvider implements GraphProvider {

    static final int FILE_CHUNK = 5_000;
    static final int NODE_CHUNK = 5_000;
    static final int EDGE_CHUNK = 10_000;

    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private static final String NODE = "id kind name file line endLine modifiers signature doc community";
    private static final String EDGE = "from to rel conf score count line";
    private static final String HEAD = "id project branch commit root builtAt generator stats location "
            + "communities { id label size top }";

    private final BackendConnection backend;

    public GraphQlGraphProvider(BackendConnection backend) {
        this.backend = backend;
    }

    /** Antwort von {@code graph}/{@code publishGraph}. */
    record Head(String id, String project, String branch, String commit, String root, String builtAt,
                String generator, String stats, String location, List<Community> communities) {
    }

    record FileHash(String path, String sha256) {
    }

    record StateDto(String generator, List<FileHash> files) {
    }

    record Count(String id, int count) {
    }

    record QueryDto(List<String> columns, String rows, boolean truncated) {
    }

    private static Map<String, Object> key(Key key) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("project", key.project());
        m.put("root", key.root());
        m.put("branch", key.branch());
        m.put("projectId", key.projectId());
        return m;
    }

    /** Variablen; {@code null}-Werte bleiben weg. */
    private static Map<String, Object> args(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            if (kv[i + 1] != null) {
                m.put((String) kv[i], kv[i + 1]);
            }
        }
        return m;
    }

    @Override
    public String describe() {
        return "Graph-Storage des Backends " + backend.url();
    }

    @Override
    public String check() {
        return backend.query("{ graphStorage }", Map.of(), "graphStorage", String.class) + " – über " + backend.url();
    }

    @Override
    public String location(Key key) {
        return describe() + " · " + key.root() + "@" + (key.branch() == null ? "" : key.branch());
    }

    @Override
    public State state(Key key) {
        StateDto s = backend.query("query($key: GraphKeyInput!) { graphState(key: $key) { generator files { path "
                + "sha256 } } }", Map.of("key", key(key)), "graphState", StateDto.class);
        if (s == null) {
            return null;
        }
        Map<String, String> hashes = new HashMap<>();
        s.files().forEach(f -> hashes.put(f.path(), f.sha256()));
        return new State(s.generator(), hashes);
    }

    @Override
    public GraphReader reader(Key key) {
        Head h = backend.query("query($key: GraphKeyInput!) { graph(key: $key) { " + HEAD + " } }",
                Map.of("key", key(key)), "graph", Head.class);
        return h == null ? null : new Reader(h);
    }

    @Override
    public GraphReader write(Key key, GraphFile data) {
        String g = backend.query("mutation($key: GraphKeyInput!) { beginGraph(key: $key) }", Map.of("key", key(key)),
                "beginGraph", String.class);
        try {
            chunks(data.files(), FILE_CHUNK, part -> mutate("writeGraphFiles", "[SourceFileInput!]!", "files", g, part));
            chunks(data.nodes(), NODE_CHUNK, part -> mutate("writeGraphNodes", "[CodeNodeInput!]!", "nodes", g, part));
            chunks(data.edges(), EDGE_CHUNK, part -> mutate("writeGraphEdges", "[CodeEdgeInput!]!", "edges", g, part));
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("commit", data.commit());
            header.put("builtAt", data.builtAt());
            header.put("builtBy", GraphProvider.localBuilder());
            header.put("generator", data.generator());
            header.put("version", data.version());
            header.put("files", data.files().size());
            header.put("nodes", data.nodes().size());
            header.put("edges", data.edges().size());
            header.put("stats", JSON.writeValueAsString(data.stats() == null ? Map.of() : data.stats()));
            header.put("communities", data.communities() == null ? List.of() : data.communities());
            Head h = backend.query("mutation($key: GraphKeyInput!, $graph: ID!, $header: GraphHeaderInput!) { "
                            + "publishGraph(key: $key, graph: $graph, header: $header) { " + HEAD + " } }",
                    Map.of("key", key(key), "graph", g, "header", header), "publishGraph", Head.class);
            return new Reader(h);
        } catch (RuntimeException e) {
            try {
                backend.query("mutation($graph: ID!) { abortGraph(graph: $graph) }", Map.of("graph", g), "abortGraph",
                        Boolean.class);
            } catch (RuntimeException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }

    private static <T> void chunks(List<T> items, int size, Consumer<List<T>> send) {
        for (int i = 0; i < items.size(); i += size) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("Graph-Aufbau abgebrochen", new InterruptedException());
            }
            send.accept(items.subList(i, Math.min(items.size(), i + size)));
        }
    }

    private void mutate(String field, String type, String name, String g, List<?> part) {
        backend.query("mutation($graph: ID!, $items: " + type + ") { " + field + "(graph: $graph, " + name
                + ": $items) }", Map.of("graph", g, "items", part), field, Integer.class);
    }

    @Override
    public List<Stored> branches(Key project) {
        return backend.queryList("query($key: GraphKeyInput!) { graphBranches(key: $key) { branch commit builtAt files "
                + "nodes edges location builtBy } }", Map.of("key", key(project)), "graphBranches", Stored.class);
    }

    @Override
    public boolean delete(Key key) {
        return Boolean.TRUE.equals(backend.query("mutation($key: GraphKeyInput!) { deleteGraph(key: $key) }",
                Map.of("key", key(key)), "deleteGraph", Boolean.class));
    }

    // ------------------------------------------------------------------ Lesen

    /** Leser auf einer Generation; jede Methode ist eine GraphQL-Abfrage mit deren ID. */
    final class Reader implements GraphReader {

        private final String g;
        private final GraphInfo info;

        Reader(Head h) {
            this.g = h.id();
            Map<String, Object> stats = h.stats() == null || h.stats().isBlank() ? Map.of()
                    : JSON.readValue(h.stats(), new TypeReference<LinkedHashMap<String, Object>>() { });
            this.info = new GraphInfo(h.project(), h.branch(), h.commit(), h.root(), h.builtAt(), h.generator(), stats,
                    h.communities() == null ? List.of() : h.communities(), h.location());
        }

        @Override
        public GraphInfo info() {
            return info;
        }

        private <T> List<T> list(String field, String params, String selection, Map<String, Object> vars,
                                 Class<T> type) {
            Map<String, Object> v = new LinkedHashMap<>(vars);
            v.put("graph", g);
            String decl = "$graph: ID!" + (params.isEmpty() ? "" : ", " + params);
            String call = "graph: $graph" + vars.keySet().stream().map(k -> ", " + k + ": $" + k)
                    .reduce("", String::concat);
            return backend.queryList("query(" + decl + ") { " + field + "(" + call + ")"
                    + (selection == null ? "" : " { " + selection + " }") + " }", v, field, type);
        }

        private List<Node> nodeList(String field, String params, Map<String, Object> vars) {
            return list(field, params, NODE, vars, Node.class);
        }

        @Override
        public Node node(String id) {
            List<Node> hits = nodeList("graphNodes", "$ids: [String!]!", Map.of("ids", List.of(id)));
            return hits.isEmpty() ? null : hits.getFirst();
        }

        @Override
        public Map<String, Node> nodes(Collection<String> ids) {
            Map<String, Node> out = new LinkedHashMap<>();
            if (!ids.isEmpty()) {
                nodeList("graphNodes", "$ids: [String!]!", Map.of("ids", List.copyOf(ids)))
                        .forEach(n -> out.put(n.id(), n));
            }
            return out;
        }

        @Override
        public List<Edge> edges(Collection<String> ids, Direction dir, Set<Relation> rels) {
            if (ids.isEmpty() || (rels != null && rels.isEmpty())) {
                return List.of();
            }
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("ids", List.copyOf(ids));
            vars.put("direction", dir.name());
            String params = "$ids: [String!]!, $direction: GraphDirection!";
            if (rels != null) {
                vars.put("relations", EnumSet.copyOf(rels).stream().map(Relation::name).toList());
                params += ", $relations: [GraphRelation!]";
            }
            return list("graphEdges", params, EDGE, vars, Edge.class);
        }

        @Override
        public Map<String, Integer> degrees(Collection<String> ids) {
            Map<String, Integer> out = new HashMap<>();
            if (!ids.isEmpty()) {
                list("graphDegrees", "$ids: [String!]!", "id count", Map.of("ids", List.copyOf(ids)), Count.class)
                        .forEach(c -> out.put(c.id(), c.count()));
            }
            return out;
        }

        @Override
        public List<Node> typesNamed(String name) {
            return nodeList("graphTypesNamed", "$name: String!", Map.of("name", name));
        }

        @Override
        public List<Node> filesNamed(String name) {
            return nodeList("graphFilesNamed", "$name: String!", Map.of("name", name));
        }

        @Override
        public List<Node> search(NodeSearch s) {
            Map<String, Object> search = new LinkedHashMap<>();
            search.put("kinds", s.kinds() == null ? null : s.kinds().stream().map(Enum::name).sorted().toList());
            search.put("needles", s.needles());
            search.put("withDoc", s.withDoc());
            search.put("nameRegex", s.nameRegex());
            search.put("idRegex", s.idRegex());
            search.put("limit", s.limit());
            return nodeList("graphSearch", "$search: NodeSearchInput!", Map.of("search", search));
        }

        private List<Map.Entry<String, Integer>> ranking(String field, int limit) {
            return list(field, "$limit: Int!", "id count", Map.of("limit", limit), Count.class).stream()
                    .map(c -> Map.entry(c.id(), c.count())).toList();
        }

        @Override
        public List<Map.Entry<String, Integer>> topTypes(int limit) {
            return ranking("graphTopTypes", limit);
        }

        @Override
        public List<Map.Entry<String, Integer>> mostCalled(int limit) {
            return ranking("graphMostCalled", limit);
        }

        @Override
        public List<TypeLink> typeLinks() {
            return list("graphTypeLinks", "", "a b weight sample { " + EDGE + " }", Map.of(), TypeLink.class);
        }

        @Override
        public Map<String, Integer> callersByType(String typeId) {
            Map<String, Integer> out = new HashMap<>();
            list("graphCallersByType", "$typeId: String!", "id count", Map.of("typeId", typeId), Count.class)
                    .forEach(c -> out.put(c.id(), c.count()));
            return out;
        }

        @Override
        public List<String> parseErrorFiles(int limit) {
            return list("graphParseErrorFiles", "$limit: Int!", null, Map.of("limit", limit), String.class);
        }

        @Override
        public List<Edge> shortestPath(String from, String to, Set<Relation> rels, boolean directed, int maxDepth) {
            if (rels.isEmpty()) {
                return null;
            }
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("graph", g);
            v.put("from", from);
            v.put("to", to);
            v.put("relations", EnumSet.copyOf(rels).stream().map(Relation::name).toList());
            v.put("directed", directed);
            v.put("maxDepth", maxDepth);
            Edge[] path = backend.query("query($graph: ID!, $from: String!, $to: String!, "
                    + "$relations: [GraphRelation!]!, $directed: Boolean!, $maxDepth: Int!) { graphShortestPath("
                    + "graph: $graph, from: $from, to: $to, relations: $relations, directed: $directed, "
                    + "maxDepth: $maxDepth) { " + EDGE + " } }", v, "graphShortestPath", Edge[].class);
            return path == null ? null : List.of(path);
        }

        @Override
        public QueryResult query(String query, Map<String, Object> params, int maxRows) {
            QueryDto r = backend.query("query($graph: ID!, $query: String!, $params: String, $maxRows: Int!) { "
                            + "graphQuery(graph: $graph, query: $query, params: $params, maxRows: $maxRows) { columns rows "
                            + "truncated } }",
                    args("graph", g, "query", query, "params", params == null || params.isEmpty() ? null
                            : JSON.writeValueAsString(params), "maxRows", maxRows), "graphQuery", QueryDto.class);
            List<List<Object>> rows = JSON.readValue(r.rows(), new TypeReference<List<List<Object>>>() { });
            return new QueryResult(r.columns(), rows, r.truncated());
        }
    }
}
