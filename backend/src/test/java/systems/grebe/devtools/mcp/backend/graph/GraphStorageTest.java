package systems.grebe.devtools.mcp.backend.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import systems.grebe.devtools.mcp.modules.graph.GraphProvider.Key;
import systems.grebe.devtools.mcp.modules.graph.GraphReader;
import systems.grebe.devtools.mcp.modules.graph.GraphReader.Direction;

/**
 * Graph-Storage mit eingebetteter ArcadeDB (extern: {@link RemoteGraphStorageTest}): Generationen je Branch, Aufräumen, Trennung der Benutzer und lesende
 * Abfragen. Dass alle Abfragen genau wie auf dem geladenen Graphen antworten, prüft {@code DatabaseGraphStorageTest}
 * in der Desktop-App mit echten Graphen.
 */
class GraphStorageTest {

    @TempDir
    Path dir;

    GraphStorage storage;

    @BeforeEach
    void open() {
        storage = create();
    }

    /** Ablage für den Test; ein zweiter Aufruf öffnet dieselbe Datenbank erneut. */
    protected GraphStorage create() {
        return new GraphStorage(GraphStorage.Settings.embedded(dir.resolve("graphdb")));
    }

    @AfterEach
    void close() {
        storage.close();
    }

    static GraphFile graph(String commit, String... extraTypes) {
        List<Node> nodes = new ArrayList<>(List.of(
                new Node("file:src/A.java", Kind.FILE, "src/A.java", "src/A.java", null, null, null, null, null, null),
                new Node("a.A", Kind.CLASS, "A", "src/A.java", 3, 20, "public", null, "Die Klasse A.", 0),
                new Node("a.A#run()", Kind.METHOD, "run", null, 5, 9, "public", "void run()", null, 0),
                new Node("a.A#helper()", Kind.METHOD, "helper", null, 11, 12, "private", "int helper()", null, 0),
                new Node("a.B", Kind.INTERFACE, "B", "src/B.java", 1, 4, "public", null, null, 1),
                new Node("java.util.List", Kind.EXTERNAL, "List", null, null, null, null, null, null, null)));
        List<Edge> edges = new ArrayList<>(List.of(
                new Edge("file:src/A.java", "a.A", Relation.CONTAINS, Confidence.EXTRACTED, null, null, null),
                new Edge("a.A", "a.A#run()", Relation.CONTAINS, Confidence.EXTRACTED, null, null, null),
                new Edge("a.A", "a.A#helper()", Relation.CONTAINS, Confidence.EXTRACTED, null, null, null),
                new Edge("a.A#run()", "a.A#helper()", Relation.CALLS, Confidence.EXTRACTED, null, 2, 6),
                new Edge("a.A", "a.B", Relation.IMPLEMENTS, Confidence.EXTRACTED, null, null, 3),
                new Edge("a.A#run()", "a.B", Relation.CALLS, Confidence.INFERRED, 0.6, null, 7),
                new Edge("a.A", "java.util.List", Relation.HAS_TYPE, Confidence.EXTRACTED, null, null, 4)));
        for (String t : extraTypes) {
            nodes.add(new Node("a." + t, Kind.CLASS, t, "src/" + t + ".java", 1, 2, null, null, null, 1));
            edges.add(new Edge("a." + t, "a.A", Relation.EXTENDS, Confidence.EXTRACTED, null, null, 1));
        }
        return new GraphFile(CodeGraph.FORMAT, CodeGraph.VERSION, "demo", "/work/demo", "main", commit,
                "2026-10-07T10:00:00Z", "test-1", Map.of("files", 2, "nodes", nodes.size(), "edges", edges.size()),
                List.of(new FileEntry("src/A.java", "sha-a", 20, null), new FileEntry("src/B.java", "sha-b", 4, true)),
                List.of(new Community(0, "A", 3, List.of("a.A")), new Community(1, "B", 1, List.of("a.B"))), nodes,
                edges);
    }

    static final Key MAIN = new Key("demo", "/work/demo", "main");

    @Test
    void writesReadsAndReplacesGenerations() throws Exception {
        assertThat(storage.reader(MAIN)).isNull();
        assertThat(storage.state(MAIN)).isNull();
        assertThat(storage.check()).startsWith(storage.describe()).contains("ArcadeDB 26.");

        GraphReader r = storage.write(MAIN, graph("c1"));
        assertThat(r.info().commit()).isEqualTo("c1");
        assertThat(r.info().project()).isEqualTo("demo");
        assertThat(r.info().stat("nodes")).isEqualTo(6);
        assertThat(r.info().communities()).extracting(Community::label).containsExactly("A", "B");
        assertThat(r.info().location()).contains("GraphBranch /work/demo@main");
        assertThat(storage.state(MAIN).fileHashes()).containsEntry("src/A.java", "sha-a").hasSize(2);
        assertThat(storage.state(MAIN).generator()).isEqualTo("test-1");

        GraphReader g = storage.reader(MAIN);
        assertThat(g.node("a.A")).isEqualTo(graph("c1").nodes().get(1));
        assertThat(g.node("gibtsnicht")).isNull();
        assertThat(g.nodes(List.of("a.A", "a.B", "x")).keySet()).containsExactlyInAnyOrder("a.A", "a.B");
        assertThat(g.edges("a.A#run()", Direction.OUT, null)).containsExactly(
                new Edge("a.A#run()", "a.A#helper()", Relation.CALLS, Confidence.EXTRACTED, null, 2, 6),
                new Edge("a.A#run()", "a.B", Relation.CALLS, Confidence.INFERRED, 0.6, null, 7));
        assertThat(g.edges("a.A#helper()", Direction.IN, EnumSet.of(Relation.CALLS))).hasSize(1);
        assertThat(g.degrees(List.of("a.A", "a.A#run()"))).containsEntry("a.A", 2).containsEntry("a.A#run()", 2);
        assertThat(g.typesNamed("A")).extracting(Node::id).containsExactly("a.A");
        assertThat(g.typesNamed("List")).extracting(Node::id).containsExactly("java.util.List");
        assertThat(g.filesNamed("A.java")).extracting(Node::id).containsExactly("file:src/A.java");
        assertThat(g.search(new GraphReader.NodeSearch(Set.of(Kind.METHOD), List.of("help"), false, null, null, 10)))
                .extracting(Node::id).containsExactly("a.A#helper()");
        assertThat(g.search(new GraphReader.NodeSearch(null, List.of(), true, "r.*", null, 10)))
                .extracting(Node::id).containsExactly("a.A#run()");
        assertThat(g.search(new GraphReader.NodeSearch(null, List.of("klasse a"), true, null, null, 10)))
                .extracting(Node::id).containsExactly("a.A");
        assertThat(g.mostCalled(5)).containsExactly(Map.entry("a.A#helper()", 2), Map.entry("a.B", 1));
        assertThat(g.typeLinks()).singleElement().satisfies(l -> {
            assertThat(l.a()).isEqualTo("a.A");
            assertThat(l.b()).isEqualTo("a.B");
        });
        assertThat(g.callersByType("a.B")).isEmpty();
        assertThat(g.parseErrorFiles(10)).containsExactly("src/B.java");
        assertThat(g.shortestPath("a.A#helper()", "a.B", EnumSet.of(Relation.CALLS, Relation.CONTAINS,
                Relation.IMPLEMENTS), false, 5)).hasSize(2);
        assertThat(g.shortestPath("a.A#helper()", "a.B", EnumSet.of(Relation.CALLS), true, 5)).isNull();

        // Neubau: neue Generation, die alte verschwindet im Hintergrund vollständig
        String old = ((ArcadeGraphReader) g).graphId();
        GraphReader r2 = storage.write(MAIN, graph("c2", "C", "D"));
        assertThat(((ArcadeGraphReader) r2).graphId()).isNotEqualTo(old);
        assertThat(storage.reader(MAIN).info().commit()).isEqualTo("c2");
        storage.awaitCleanup();
        assertThat(storage.count("CodeNode", old)).isZero();
        assertThat(storage.count("SourceFile", old)).isZero();
        assertThat(storage.reader(MAIN).node("a.D")).isNotNull();

        // weiterer Branch, Liste, Löschen
        storage.write(new Key("demo", "/work/demo", "feature/x"), graph("c3"));
        storage.write(new Key("demo", "/work/demo", null), graph("c4"));
        assertThat(storage.branches("/work/demo")).extracting(GraphProvider.Stored::branch)
                .containsExactly(null, "feature/x", "main");
        assertThat(storage.delete("/work/demo", "feature/x")).isTrue();
        assertThat(storage.delete("/work/demo", "feature/x")).isFalse();
        assertThat(storage.branches("/work/demo")).extracting(GraphProvider.Stored::branch).containsExactly(null, "main");
        storage.awaitCleanup();

        // Nach einem Neustart ist alles noch da
        storage.close();
        storage = create();
        assertThat(storage.reader(MAIN).node("a.C")).isNotNull();
    }

    @Test
    void abortedBuildsLeaveNothingBehind() throws Exception {
        String g = storage.begin(GraphStorage.LOCAL, MAIN);
        storage.writeFiles(GraphStorage.LOCAL, g, graph("c1").files());
        storage.writeNodes(GraphStorage.LOCAL, g, graph("c1").nodes());
        assertThat(storage.reader(MAIN)).isNull(); // nicht veröffentlicht
        assertThat(storage.branches("/work/demo")).isEmpty();
        storage.abort(GraphStorage.LOCAL, g);
        storage.awaitCleanup();
        assertThat(storage.count("CodeNode", g)).isZero();
        assertThatThrownBy(() -> storage.writeEdges(GraphStorage.LOCAL, g, graph("c1").edges()))
                .hasMessageContaining("Kein laufender Aufbau");

        // ein neuer Aufbau löst einen hängengebliebenen ab
        String stale = storage.begin(GraphStorage.LOCAL, MAIN);
        storage.writeNodes(GraphStorage.LOCAL, stale, graph("c1").nodes());
        storage.write(MAIN, graph("c2"));
        storage.awaitCleanup();
        assertThat(storage.count("CodeNode", stale)).isZero();
        assertThat(storage.reader(MAIN).info().commit()).isEqualTo("c2");
    }

    @Test
    void usersOfTheApiOnlySeeTheirOwnGraphs() {
        GraphProvider felix = storage.forOwner("user:1");
        GraphProvider anna = storage.forOwner("user:2");
        GraphReader mine = felix.write(MAIN, graph("f"));
        anna.write(MAIN, graph("a"));
        assertThat(felix.reader(MAIN).info().commit()).isEqualTo("f");
        assertThat(anna.reader(MAIN).info().commit()).isEqualTo("a");
        assertThat(storage.reader(MAIN)).isNull(); // Local-Mode: eigener Bereich
        assertThat(anna.branches("/work/demo")).hasSize(1);

        String id = ((ArcadeGraphReader) mine).graphId();
        assertThat(storage.reader("user:1", id).node("a.A")).isNotNull();
        assertThatThrownBy(() -> storage.reader("user:2", id)).hasMessageContaining("gibt es nicht mehr");
        String g = storage.begin("user:1", MAIN);
        assertThatThrownBy(() -> storage.writeNodes("user:2", g, List.of())).hasMessageContaining("Kein laufender");
        assertThat(anna.delete("/work/demo", "main")).isTrue();
        assertThat(felix.reader(MAIN)).isNotNull();
    }

    @Test
    void freeQueriesAreReadOnlyAndShowNodesCompactly() {
        GraphReader g = storage.write(MAIN, graph("c1"));
        GraphReader.QueryResult r = g.query("MATCH (m:Method {g: $g})<-[c:CALLS]-(x) RETURN m.id AS id, "
                + "sum(coalesce(c.count, 1)) AS n ORDER BY n DESC, id", null, 10);
        assertThat(r.columns()).containsExactly("id", "n");
        assertThat(r.rows()).containsExactly(List.of("a.A#helper()", 2));
        GraphReader.QueryResult types = g.query("MATCH (t:Type {g: $g, name: $name}) RETURN t", Map.of("name", "A"), 10);
        assertThat(types.columns()).containsExactly("t");
        assertThat(types.rows()).containsExactly(List.of("a.A [class]"));
        assertThat(g.query("MATCH (n:CodeNode {g: $g}) RETURN n.id", null, 2).truncated()).isTrue();
        assertThat(g.query("MATCH (:Class {g: $g})-[e:IMPLEMENTS]->() RETURN e", null, 5).rows().getFirst().getFirst())
                .isEqualTo(":IMPLEMENTS {line=3}");
        assertThatThrownBy(() -> g.query("MATCH (n:CodeNode {g: $g}) DETACH DELETE n", null, 10))
                .hasMessageContaining("nur lesend");
        assertThatThrownBy(() -> g.query("CREATE (:Class {g: $g})", null, 10)).hasMessageContaining("nur lesend");
        assertThat(storage.reader(MAIN).node("a.A")).isNotNull();
    }
}
