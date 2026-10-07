package systems.grebe.devtools.mcp.backend.graph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.ContextValue;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;
import systems.grebe.devtools.mcp.backend.GraphQlAuth;
import systems.grebe.devtools.mcp.backend.account.UserAccount;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Community;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Edge;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.FileEntry;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Node;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Relation;
import systems.grebe.devtools.mcp.modules.graph.GraphProvider;
import systems.grebe.devtools.mcp.modules.graph.GraphReader;
import systems.grebe.devtools.mcp.modules.graph.GraphReader.Direction;
import systems.grebe.devtools.mcp.modules.graph.GraphReader.NodeSearch;
import systems.grebe.devtools.mcp.modules.graph.GraphReader.TypeLink;
import tools.jackson.core.type.TypeReference;

/**
 * GraphQL-API der {@link GraphStorage} ({@code graph.graphqls}) für Desktop-Apps mit Team-Server: dieselben
 * Operationen wie {@link GraphProvider} und {@link GraphReader}, je Benutzer ein eigener Bereich
 * ({@link GraphStorage#forOwner}). Gelesen wird über die ID der Generation aus {@code graph}.
 */
@Controller
public class GraphGraphQlController {

    private final GraphStorage storage;

    public GraphGraphQlController(GraphStorage storage) {
        this.storage = storage;
    }

    /** Projekt + Branch wie {@code GraphKeyInput}. */
    public record KeyInput(String project, String root, String branch) {

        GraphProvider.Key key() {
            return new GraphProvider.Key(project, root, branch == null || branch.isBlank() ? null : branch);
        }
    }

    /** Kopfdaten beim Umschalten wie {@code GraphHeaderInput}. */
    public record HeaderInput(String commit, String builtAt, String generator, int version, int files, int nodes,
                              int edges, String stats, List<Community> communities) {
    }

    static String owner(UserAccount user) {
        return "user:" + GraphQlAuth.require(user).id();
    }

    private GraphProvider provider(UserAccount user) {
        return storage.forOwner(owner(user));
    }

    private ArcadeGraphReader graph(UserAccount user, String graphId) {
        return storage.reader(owner(user), graphId);
    }

    static Map<String, Object> head(ArcadeGraphReader r) {
        GraphReader.GraphInfo i = r.info();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.graphId());
        m.put("project", i.project());
        m.put("branch", i.branch());
        m.put("commit", i.commit());
        m.put("root", i.root());
        m.put("builtAt", i.builtAt());
        m.put("generator", i.generator());
        m.put("stats", GraphStorage.JSON.writeValueAsString(i.stats() == null ? Map.of() : i.stats()));
        m.put("communities", i.communities() == null ? List.of() : i.communities());
        m.put("location", i.location());
        return m;
    }

    private static List<Map<String, Object>> counts(Iterable<Map.Entry<String, Integer>> entries) {
        List<Map<String, Object>> out = new ArrayList<>();
        entries.forEach(e -> out.add(Map.of("id", e.getKey(), "count", e.getValue())));
        return out;
    }

    private static Set<Relation> relations(List<Relation> rels) {
        return rels == null ? null : rels.isEmpty() ? Set.of() : EnumSet.copyOf(rels);
    }

    // ---------------------------------------------------------------- Abfragen

    @QueryMapping
    public String graphStorage(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user) {
        GraphQlAuth.require(user);
        return storage.check();
    }

    @QueryMapping
    public List<GraphProvider.Stored> graphBranches(@ContextValue(name = GraphQlAuth.USER, required = false)
                                                    UserAccount user, @Argument String root) {
        return provider(user).branches(root);
    }

    @QueryMapping
    public Map<String, Object> graphState(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                                          @Argument KeyInput key) {
        GraphProvider.State s = provider(user).state(key.key());
        if (s == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("generator", s.generator());
        m.put("files", s.fileHashes().entrySet().stream()
                .map(e -> Map.of("path", e.getKey(), "sha256", e.getValue())).toList());
        return m;
    }

    @QueryMapping
    public Map<String, Object> graph(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                                     @Argument KeyInput key) {
        GraphReader r = provider(user).reader(key.key());
        return r == null ? null : head((ArcadeGraphReader) r);
    }

    @QueryMapping
    public Collection<Node> graphNodes(@ContextValue(name = GraphQlAuth.USER, required = false)
                                                 UserAccount user, @Argument String graph, @Argument List<String> ids) {
        return graph(user, graph).nodes(ids).values();
    }

    @QueryMapping
    public List<Edge> graphEdges(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                                 @Argument String graph, @Argument List<String> ids, @Argument Direction direction,
                                 @Argument List<Relation> relations) {
        return graph(user, graph).edges(ids, direction, relations(relations));
    }

    @QueryMapping
    public List<Map<String, Object>> graphDegrees(@ContextValue(name = GraphQlAuth.USER, required = false)
                                                  UserAccount user, @Argument String graph,
                                                  @Argument List<String> ids) {
        return counts(graph(user, graph).degrees(ids).entrySet());
    }

    @QueryMapping
    public List<Node> graphTypesNamed(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                                      @Argument String graph, @Argument String name) {
        return graph(user, graph).typesNamed(name);
    }

    @QueryMapping
    public List<Node> graphFilesNamed(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                                      @Argument String graph, @Argument String name) {
        return graph(user, graph).filesNamed(name);
    }

    @QueryMapping
    public List<Node> graphSearch(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                                  @Argument String graph, @Argument NodeSearch search) {
        return graph(user, graph).search(search);
    }

    @QueryMapping
    public List<Map<String, Object>> graphTopTypes(@ContextValue(name = GraphQlAuth.USER, required = false)
                                                   UserAccount user, @Argument String graph, @Argument int limit) {
        return counts(graph(user, graph).topTypes(limit));
    }

    @QueryMapping
    public List<Map<String, Object>> graphMostCalled(@ContextValue(name = GraphQlAuth.USER, required = false)
                                                     UserAccount user, @Argument String graph, @Argument int limit) {
        return counts(graph(user, graph).mostCalled(limit));
    }

    @QueryMapping
    public List<TypeLink> graphTypeLinks(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                                         @Argument String graph) {
        return graph(user, graph).typeLinks();
    }

    @QueryMapping
    public List<Map<String, Object>> graphCallersByType(@ContextValue(name = GraphQlAuth.USER, required = false)
                                                        UserAccount user, @Argument String graph,
                                                        @Argument String typeId) {
        return counts(graph(user, graph).callersByType(typeId).entrySet());
    }

    @QueryMapping
    public List<String> graphParseErrorFiles(@ContextValue(name = GraphQlAuth.USER, required = false)
                                             UserAccount user, @Argument String graph, @Argument int limit) {
        return graph(user, graph).parseErrorFiles(limit);
    }

    @QueryMapping
    public List<Edge> graphShortestPath(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                                        @Argument String graph, @Argument String from, @Argument String to,
                                        @Argument List<Relation> relations, @Argument boolean directed,
                                        @Argument int maxDepth) {
        return graph(user, graph).shortestPath(from, to, relations(relations), directed, maxDepth);
    }

    @QueryMapping
    public Map<String, Object> graphQuery(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                                          @Argument String graph, @Argument String query, @Argument String params,
                                          @Argument int maxRows) {
        Map<String, Object> p = params == null || params.isBlank() ? Map.of()
                : GraphStorage.JSON.readValue(params, new TypeReference<Map<String, Object>>() { });
        GraphReader.QueryResult r = graph(user, graph).query(query, p, Math.max(1, Math.min(maxRows, 1000)));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("columns", r.columns());
        m.put("rows", GraphStorage.JSON.writeValueAsString(r.rows()));
        m.put("truncated", r.truncated());
        return m;
    }

    // ---------------------------------------------------------------- Schreiben

    @MutationMapping
    public String beginGraph(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                             @Argument KeyInput key) {
        return storage.begin(owner(user), key.key());
    }

    @MutationMapping
    public int writeGraphFiles(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                               @Argument String graph, @Argument List<FileEntry> files) {
        storage.writeFiles(owner(user), graph, files);
        return files.size();
    }

    @MutationMapping
    public int writeGraphNodes(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                               @Argument String graph, @Argument List<Node> nodes) {
        storage.writeNodes(owner(user), graph, nodes);
        return nodes.size();
    }

    @MutationMapping
    public int writeGraphEdges(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                               @Argument String graph, @Argument List<Edge> edges) {
        storage.writeEdges(owner(user), graph, edges);
        return edges.size();
    }

    @MutationMapping
    public Map<String, Object> publishGraph(@ContextValue(name = GraphQlAuth.USER, required = false)
                                            UserAccount user, @Argument KeyInput key, @Argument String graph,
                                            @Argument HeaderInput header) {
        GraphStorage.Header h = new GraphStorage.Header(header.commit(), header.builtAt(), header.generator(),
                header.version(), header.files(), header.nodes(), header.edges(),
                header.stats() == null ? "{}" : header.stats(), header.communities());
        return head(storage.publish(owner(user), key.key(), graph, h));
    }

    @MutationMapping
    public boolean abortGraph(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                              @Argument String graph) {
        storage.abort(owner(user), graph);
        return true;
    }

    @MutationMapping
    public boolean deleteGraph(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                               @Argument String root, @Argument String branch) {
        return provider(user).delete(root, branch == null || branch.isBlank() ? null : branch);
    }
}
