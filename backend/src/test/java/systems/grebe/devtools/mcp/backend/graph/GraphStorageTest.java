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
import systems.grebe.devtools.mcp.modules.graph.GraphDelta;
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
        assertThat(r.info().location()).contains("GraphBranch name:demo@main");
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
        assertThat(storage.branches(MAIN)).extracting(GraphProvider.Stored::branch)
                .containsExactly(null, "feature/x", "main");
        assertThat(storage.delete(MAIN.withBranch("feature/x"))).isTrue();
        assertThat(storage.delete(MAIN.withBranch("feature/x"))).isFalse();
        assertThat(storage.branches(MAIN)).extracting(GraphProvider.Stored::branch).containsExactly(null, "main");
        storage.awaitCleanup();

        // Nach einem Neustart ist alles noch da
        storage.close();
        storage = create();
        assertThat(storage.reader(MAIN).node("a.C")).isNotNull();
    }

    @Test
    void createsMissingIndexesAndFindsGenerationsWithoutScanning() throws Exception {
        assertThat(GraphStorage.indexNames(storage.db()))
                .containsAll(GraphStorage.INDEXES.stream().map(GraphStorage.IndexDef::name).toList());

        // Bestehende Datenbank ohne einen der Indizes: wird beim nächsten Öffnen nachgezogen
        storage.write(MAIN, graph("c1"));
        storage.exec("sql", "DROP INDEX `GraphBranch[graphId]`", Map.of());
        assertThat(GraphStorage.indexNames(storage.db())).doesNotContain("GraphBranch[graphId]");
        storage.close();
        storage = create();
        assertThat(GraphStorage.indexNames(storage.db())).contains("GraphBranch[graphId]");

        // Der Aufräumer zählt die Generationen über den Index auf, statt alle Knoten zu lesen
        String g1 = ((ArcadeGraphReader) storage.reader(MAIN)).graphId();
        String g2 = storage.begin(GraphStorage.Access.LOCAL, MAIN.withBranch("feature/x"));
        storage.writeNodes(GraphStorage.Access.LOCAL, g2, graph("c2").nodes());
        assertThat(storage.generations("CodeNode")).containsExactlyElementsOf(java.util.stream.Stream.of(g1, g2)
                .sorted().toList());
        if (!(storage.db() instanceof com.arcadedb.database.Database)) {
            return; // extern liefert EXPLAIN keine Zeilen; der Plan entsteht auf dem Server genauso
        }
        for (String type : List.of("CodeNode", "SourceFile")) {
            String plan = (String) storage.rows("sql", "EXPLAIN " + GraphStorage.nextGenerationQuery(type),
                    Map.of("last", "")).getFirst().get("executionPlanAsString");
            assertThat(plan).contains("FETCH FROM INDEX " + type + "[g]").doesNotContain("FETCH FROM TYPE")
                    .doesNotContain("ORDER BY");
        }
    }

    @Test
    void stopsWalkingGenerationsWhenIndexIsOutOfOrder() {
        Map<String, String> sorted = Map.of("", "a", "a", "b", "b", "c");
        assertThat(storage.ascendingKeys(sorted::get)).containsExactly("a", "b", "c");

        // Beschädigter Index: auf "> c" kommt "b" zurück – ohne Abbruch liefe der Sprung endlos im Kreis
        Map<String, String> cycling = Map.of("", "a", "a", "c", "c", "b", "b", "c");
        assertThat(storage.ascendingKeys(cycling::get)).isNull();
    }

    @Test
    void startsReportsStatusAndSwitchesToOtherSettings() {
        List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        storage.addStatusListener(() -> seen.add(storage.status()));
        assertThat(storage.status()).isEqualTo("nicht gestartet");
        assertThat(storage.start()).startsWith("läuft: " + storage.describe()).contains("ArcadeDB 26.");
        storage.write(MAIN, graph("c1"));

        // andere eingebettete Datenbank: sofort umgestellt, dort gibt es den Graphen noch nicht
        Path other = dir.resolve("andere");
        assertThat(storage.configure(GraphStorage.Settings.embedded(other))).startsWith("läuft: ArcadeDB eingebettet")
                .contains(other.toString());
        assertThat(storage.reader(MAIN)).isNull();
        assertThat(seen).anyMatch(s -> s.startsWith("läuft:")).contains("wird gestartet …");

        // nicht erreichbarer Server: Fehler im Status statt einer Ausnahme
        assertThat(storage.configure(GraphStorage.Settings.remote("127.0.0.1", 1, "x", "root", "pw")))
                .startsWith("Fehler: ArcadeDB 127.0.0.1:1/x ist nicht verfügbar");
        assertThatThrownBy(() -> storage.reader(MAIN)).isInstanceOf(IllegalStateException.class);

        // zurück: der Graph ist wieder da
        storage.configure(create().settings());
        assertThat(storage.reader(MAIN).info().commit()).isEqualTo("c1");
    }

    /** Zweiter Stand: Knoten geändert, entfernt, mit neuer Art (Interface → Klasse), Kanten geändert, Datei weg. */
    static GraphFile changed(GraphFile v1) {
        List<Node> nodes = new ArrayList<>();
        for (Node n : v1.nodes()) {
            if (n.id().equals("a.A#helper()")) {
                continue; // entfernt
            }
            if (n.id().equals("a.A#run()")) {
                n = new Node(n.id(), n.kind(), n.name(), n.file(), 6, 12, n.modifiers(), n.signature(), "Neu.", 0);
            }
            if (n.id().equals("a.B")) {
                n = new Node(n.id(), Kind.CLASS, n.name(), n.file(), n.line(), n.endLine(), n.modifiers(), null, null, 1);
            }
            nodes.add(n);
        }
        nodes.add(new Node("a.A#neu()", Kind.METHOD, "neu", null, 14, 15, "public", "void neu()", null, 0));
        List<Edge> edges = new ArrayList<>();
        for (Edge e : v1.edges()) {
            if (e.to().equals("a.A#helper()")) {
                continue;
            }
            if (e.rel() == Relation.CALLS && e.to().equals("a.B")) {
                e = new Edge(e.from(), e.to(), e.rel(), Confidence.AMBIGUOUS, null, 3, 8);
            }
            edges.add(e);
        }
        edges.add(new Edge("a.A", "a.A#neu()", Relation.CONTAINS, Confidence.EXTRACTED, null, null, null));
        edges.add(new Edge("a.A#run()", "a.A#neu()", Relation.CALLS, Confidence.EXTRACTED, null, null, 9));
        return new GraphFile(v1.format(), v1.version(), v1.project(), v1.root(), v1.branch(), "c2",
                "2026-10-08T10:00:00Z", v1.generator(), Map.of("files", 1, "nodes", nodes.size(), "edges", edges.size()),
                List.of(new FileEntry("src/A.java", "sha-a2", 21, null)), v1.communities(), nodes, edges);
    }

    /** Alle Knoten und Kanten eines gespeicherten Graphen, sortiert – zum Vergleich zweier Ablagen. */
    static List<String> content(GraphReader g, GraphFile expected) {
        List<String> ids = expected.nodes().stream().map(Node::id).toList();
        List<String> out = new ArrayList<>();
        g.nodes(ids).values().forEach(n -> out.add("N " + n));
        g.edges(ids, GraphReader.Direction.OUT, null).forEach(e -> out.add("E " + e));
        out.sort(String::compareTo);
        return out;
    }

    @Test
    void incrementalUpdateGivesTheSameGraphAsAFullWrite() throws Exception {
        GraphFile v1 = graph("c1");
        GraphFile v2 = changed(v1);
        GraphReader first = storage.write(MAIN, v1);
        GraphDelta delta = GraphDelta.between(v1, v2);
        assertThat(delta.removedNodes()).containsExactlyInAnyOrder("a.A#helper()", "a.B");
        assertThat(delta.addedNodes()).extracting(Node::id).containsExactlyInAnyOrder("a.A#neu()", "a.B");

        GraphReader updated = storage.update(MAIN, first.generation(), delta);
        assertThat(updated.generation()).isEqualTo(first.generation()); // in place, keine neue Generation
        assertThat(updated.info().commit()).isEqualTo("c2");
        Key fresh = new Key("frisch", "/x", "main");
        GraphReader full = storage.write(fresh, v2);
        assertThat(content(storage.reader(MAIN), v2)).isEqualTo(content(full, v2)).isNotEmpty();
        assertThat(storage.state(MAIN).fileHashes()).isEqualTo(storage.state(fresh).fileHashes())
                .containsOnly(Map.entry("src/A.java", "sha-a2"));
        assertThat(storage.reader(MAIN).info().stats()).isEqualTo(full.info().stats());

        // veraltete Basis: abgelehnt, dann schreibt der Aufrufer neu
        assertThat(storage.update(MAIN, "gibt-es-nicht", delta)).isNull();

        // neuer Branch mit gleichem Stand: übernimmt die Generation, ohne zu bauen
        Key feature = MAIN.withBranch("feature/neu");
        GraphReader linked = storage.link(feature, MAIN);
        assertThat(linked.generation()).isEqualTo(first.generation());
        assertThat(storage.branches(MAIN)).extracting(GraphProvider.Stored::branch).containsExactly("feature/neu",
                "main");
        // geteilte Generation wird nicht verändert – der andere Branch behielte sonst nicht seinen Stand
        assertThat(storage.update(feature, linked.generation(), GraphDelta.between(v2, v1))).isNull();
        GraphReader own = storage.write(feature, v1);
        assertThat(own.generation()).isNotEqualTo(first.generation());
        storage.awaitCleanup();
        assertThat(storage.reader(MAIN).info().commit()).isEqualTo("c2"); // main unberührt
        assertThat(content(storage.reader(feature), v1)).isEqualTo(content(storage.write(fresh, v1), v1));
    }

    @Test
    void abortedBuildsLeaveNothingBehind() throws Exception {
        String g = storage.begin(GraphStorage.Access.LOCAL, MAIN);
        storage.writeFiles(GraphStorage.Access.LOCAL, g, graph("c1").files());
        storage.writeNodes(GraphStorage.Access.LOCAL, g, graph("c1").nodes());
        assertThat(storage.reader(MAIN)).isNull(); // nicht veröffentlicht
        assertThat(storage.branches(MAIN)).isEmpty();
        storage.abort(GraphStorage.Access.LOCAL, g);
        storage.awaitCleanup();
        assertThat(storage.count("CodeNode", g)).isZero();
        assertThatThrownBy(() -> storage.writeEdges(GraphStorage.Access.LOCAL, g, graph("c1").edges()))
                .hasMessageContaining("Kein laufender Aufbau");

        // ein neuer Aufbau löst einen hängengebliebenen ab
        String stale = storage.begin(GraphStorage.Access.LOCAL, MAIN);
        storage.writeNodes(GraphStorage.Access.LOCAL, stale, graph("c1").nodes());
        storage.write(MAIN, graph("c2"));
        storage.awaitCleanup();
        assertThat(storage.count("CodeNode", stale)).isZero();
        assertThat(storage.reader(MAIN).info().commit()).isEqualTo("c2");
    }

    @Test
    void usersOfTheApiOnlySeeTheirOwnGraphs() {
        GraphProvider felix = storage.forAccess(user(1, Set.of(), Set.of()));
        GraphProvider anna = storage.forAccess(user(2, Set.of(), Set.of()));
        GraphReader mine = felix.write(MAIN, graph("f"));
        anna.write(MAIN, graph("a"));
        assertThat(felix.reader(MAIN).info().commit()).isEqualTo("f");
        assertThat(anna.reader(MAIN).info().commit()).isEqualTo("a");
        assertThat(storage.reader(MAIN)).isNull(); // Local-Mode: eigener Bereich
        assertThat(anna.branches(MAIN)).hasSize(1);

        String id = ((ArcadeGraphReader) mine).graphId();
        assertThat(storage.reader(user(1, Set.of(), Set.of()), id).node("a.A")).isNotNull();
        assertThatThrownBy(() -> storage.reader(user(2, Set.of(), Set.of()), id))
                .hasMessageContaining("gibt es nicht mehr");
        String g = storage.begin(user(1, Set.of(), Set.of()), MAIN);
        assertThatThrownBy(() -> storage.writeNodes(user(2, Set.of(), Set.of()), g, List.of()))
                .hasMessageContaining("Kein laufender");
        assertThat(anna.delete(MAIN)).isTrue();
        assertThat(felix.reader(MAIN)).isNotNull();
    }

    /** Benutzer der API mit lesbaren und beschreibbaren Backend-Projekten. */
    static GraphStorage.Access user(long id, Set<Long> read, Set<Long> write) {
        return new GraphStorage.Access("user:" + id, p -> read.contains(p) || write.contains(p), write::contains);
    }

    @Test
    void sameProjectAndBranchIsTheSameGraphWhateverThePath() {
        // ohne Backend-Projekt: Name + Branch, Pfad egal (anderer Rechner, anderes Verzeichnis)
        storage.write(MAIN, graph("c1"));
        Key elsewhere = new Key("Demo", "D:\\ganz\\woanders", "main");
        assertThat(storage.reader(elsewhere).info().commit()).isEqualTo("c1");
        assertThat(storage.state(elsewhere)).isNotNull();
        assertThat(storage.reader(new Key("anderes", "/work/demo", "main"))).isNull();

        // Backend-Projekt 7: gemeinsam für alle mit Zugriff, unabhängig vom Namen in der App
        Key felixKey = new Key("shop@felix", "/home/felix/shop", "main", 7L);
        Key annaKey = new Key("shop@felix", "C:/dev/shop", "main", 7L);
        GraphProvider felix = storage.forAccess(user(1, Set.of(), Set.of(7L)));
        GraphProvider anna = storage.forAccess(user(2, Set.of(7L), Set.of()));
        GraphProvider fremd = storage.forAccess(user(3, Set.of(), Set.of()));
        felix.write(felixKey, graph("shared"));
        assertThat(anna.reader(annaKey).info().commit()).isEqualTo("shared");
        assertThat(anna.reader(annaKey).info().root()).isEqualTo("/home/felix/shop"); // zuletzt gebaut dort
        assertThat(anna.branches(annaKey)).singleElement()
                .satisfies(s -> assertThat(s.builtBy()).isEqualTo(GraphProvider.localBuilder()));
        // nur lesend freigegeben: bauen und löschen abgelehnt
        assertThatThrownBy(() -> anna.write(annaKey, graph("x"))).hasMessageContaining("nur zum Lesen");
        assertThatThrownBy(() -> anna.delete(annaKey)).hasMessageContaining("nur zum Lesen");
        // ohne Zugriff: weder lesen noch über die Generation
        assertThatThrownBy(() -> fremd.reader(annaKey)).hasMessageContaining("Kein Zugriff");
        String g = ((ArcadeGraphReader) felix.reader(felixKey)).graphId();
        assertThatThrownBy(() -> storage.reader(user(3, Set.of(), Set.of()), g)).hasMessageContaining("gibt es nicht");
        assertThat(storage.reader(user(2, Set.of(7L), Set.of()), g).node("a.A")).isNotNull();
        // der Local-Mode sieht das Projekt ebenso
        assertThat(storage.reader(annaKey).info().commit()).isEqualTo("shared");
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
