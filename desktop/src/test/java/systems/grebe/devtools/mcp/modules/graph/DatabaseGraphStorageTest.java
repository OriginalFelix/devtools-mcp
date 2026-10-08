package systems.grebe.devtools.mcp.modules.graph;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import systems.grebe.devtools.mcp.backend.graph.ArcadeGraphReader;
import systems.grebe.devtools.mcp.backend.graph.GraphStorage;
import systems.grebe.devtools.mcp.core.ModuleAction;
import systems.grebe.devtools.mcp.core.ModuleConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Datenbank-Ablage: die Graph-Storage des Backends (eingebettete ArcadeDB) wie im Local-Mode, direkt als
 * {@link GraphProvider}. Kernprüfung: Alle Abfragen liefern über OpenCypher exakt dieselbe Ausgabe wie auf dem
 * geladenen Graphen (Datei-Ablage). {@link #compareWithFileStorage} nutzt auch der Test über GraphQL
 * ({@code GraphQlGraphProviderTest}).
 */
class DatabaseGraphStorageTest {

    @TempDir
    Path project;

    @TempDir
    Path data;

    Git git;
    GraphStorage storage;

    @BeforeEach
    void setUp() throws Exception {
        storage = new GraphStorage(GraphStorage.Settings.embedded(data.resolve("graphdb")));
        init(project);
        git = Git.init().setDirectory(project.toFile()).setInitialBranch("main").call();
        git.add().addFilepattern(".").call();
        git.commit().setMessage("init").setSign(false).call();
    }

    static void init(Path project) throws Exception {
        Files.writeString(project.resolve("build.gradle"), "");
        GraphToolsTestFixture.write(project);
    }

    @AfterEach
    void tearDown() {
        if (git != null) {
            git.close();
        }
        storage.close();
    }

    static Map<String, String> values(Path project, String storage) {
        Map<String, String> v = new HashMap<>();
        v.put(GraphModule.PROJECTS, project.toString());
        v.put(GraphModule.STORAGE, storage);
        return v;
    }

    static ModuleConfig config(Path project, String storage) {
        return ModuleConfig.of(new GraphModule().configSchema(), values(project, storage));
    }

    private GraphService service(String kind) {
        return new GraphService(config(project, kind), () -> storage);
    }

    private GraphTools tools(String kind) {
        return new GraphTools(service(kind));
    }

    @Test
    void queriesAnswerExactlyLikeTheInMemoryGraph() {
        String built = compareWithFileStorage(project, () -> storage);
        assertThat(built).contains("ArcadeDB eingebettet", "GraphBranch ");
    }

    /**
     * Baut den Graphen des Projekts mit Datei- und Datenbank-Ablage und vergleicht die Ausgaben aller Abfrage-Tools.
     *
     * @return Ausgabe von {@code graph_build} mit der Datenbank-Ablage
     */
    static String compareWithFileStorage(Path project, Supplier<GraphProvider> database) {
        GraphTools file = new GraphTools(new GraphService(config(project, GraphModule.STORAGE_FILE)));
        GraphTools db = new GraphTools(new GraphService(config(project, GraphModule.STORAGE_DATABASE), database));
        file.build(null, true);
        String built = db.build(null, true);
        assertThat(built).startsWith("Graph gebaut").contains("Branch main");

        record Q(String name, Function<GraphTools, String> call) {
        }
        List<Q> queries = List.of(
                new Q("find glob", t -> t.find(null, "Order*", "class", null, null)),
                new Q("find method", t -> t.find(null, "save", "method", null, null)),
                new Q("find all", t -> t.find(null, "o", null, 50, null)),
                new Q("find *dao", t -> t.find(null, "*repository", null, null, null)),
                new Q("find *x*", t -> t.find(null, "*order*", null, null, null)),
                new Q("explain member", t -> t.explain(null, "OrderRepository#save", null, null)),
                new Q("explain type", t -> t.explain(null, "OrderService", null, null)),
                new Q("explain file", t -> t.explain(null, "src/main/java/com/acme/shop/Order.java", null, null)),
                new Q("explain dot", t -> t.explain(null, "Printer.print(String)", null, null)),
                new Q("explain ctor", t -> t.explain(null, "OrderService#<init>", null, null)),
                new Q("callers", t -> t.neighbors(null, "Money#add", "in", List.of("calls"), 3, null, null)),
                new Q("impls", t -> t.neighbors(null, "OrderRepository", "in", List.of("implements"), 1, null, null)),
                new Q("both all", t -> t.neighbors(null, "OrderService", "both", List.of("all"), 2, 40, null)),
                new Q("path", t -> t.path(null, "OrderService#start", "JpaOrderRepository#findById", false, null, null,
                        null)),
                new Q("path directed", t -> t.path(null, "OrderService#save", "Money#add", true, List.of("calls"), 4,
                        null)),
                new Q("no path", t -> t.path(null, "Money", "Weird", true, List.of("calls"), 3, null)),
                new Q("query", t -> t.query(null, "Wie wird ein Order gespeichert (save)?", null, null)),
                new Q("query small", t -> t.query(null, "Printer print", 6, null)),
                new Q("files name", t -> t.files(null, "OrderRepository", null, null, null, null, null, null, null)),
                new Q("files path", t -> t.files(null, "shop/repo/*.java", null, null, null, null, null, null, null)),
                new Q("files glob", t -> t.files(null, "*Repository", null, null, null, null, null, null, null)),
                new Q("files users", t -> t.files(null, null, List.of("OrderRepository"), "in", null, null, null, null,
                        null)),
                new Q("files deep", t -> t.files(null, null, List.of("OrderService#save"), "out", List.of("calls"), 2,
                        null, null, null)),
                new Q("read member", t -> t.read(null, List.of("Printer#print"), null, null, 1, null)),
                new Q("read outline", t -> t.read(null, List.of("OrderService.java"), null, true, null, null)));
        for (Q q : queries) {
            assertThat(q.call().apply(db)).as(q.name()).isEqualTo(q.call().apply(file));
        }
        // Bericht: identisch bis auf die Ortsangabe
        String fileReport = file.report(null, 10, null);
        String dbReport = db.report(null, 10, null);
        assertThat(dbReport.lines().skip(2).toList()).isEqualTo(fileReport.lines().skip(2).toList());
        assertThat(dbReport.lines().findFirst().orElseThrow()).isEqualTo(fileReport.lines().findFirst().orElseThrow())
                .contains("(Branch main @ ");
        // Fehlermeldungen ebenso
        for (String spec : List.of("Printer#print", "Gibtsnicht", "Ordr")) {
            assertThat(message(() -> db.explain(null, spec, null, null)))
                    .isEqualTo(message(() -> file.explain(null, spec, null, null)));
        }
        // freie Abfrage: nur lesend, auf den Graphen eingegrenzt
        String out = db.cypher(null, "MATCH (m:Method {g: $g})<-[c:CALLS]-() RETURN m.id AS id, "
                + "sum(coalesce(c.count, 1)) AS n ORDER BY n DESC, id LIMIT 3", null, null, null);
        assertThat(out).startsWith("3 Zeile(n) · id | n");
        assertThat(db.cypher(null, "MATCH (t:Type {g: $g, name: $name}) RETURN t", Map.of("name", "OrderService"),
                null, null)).contains("com.acme.shop.OrderService [class]");
        assertThat(db.cypher(null, "MATCH (n:CodeNode {g: $g}) RETURN n.id", null, 2, null)).startsWith("2+ Zeile(n)")
                .contains("abgeschnitten");
        assertThatThrownBy(() -> db.cypher(null, "MATCH (n:CodeNode {g: $g}) DETACH DELETE n", null, null, null))
                .hasMessageContaining("nur lesend");
        assertThatThrownBy(() -> db.cypher(null, "MATCH (n:CodeNode) RETURN count(n)", null, null, null))
                .hasMessageContaining("$g");
        // auch ohne erkennbares Schlüsselwort schreibt nichts: die Datenbank lehnt in Abfragen ab
        assertThatThrownBy(() -> db.cypher(null, "WITH $g AS g CALL { WITH g CREATE (:Class {g: g}) } RETURN 1",
                null, null, null)).hasMessageContaining("nur lesend");
        assertThat(db.find(null, "OrderService", "class", null, null)).contains("com.acme.shop.OrderService");
        assertThatThrownBy(() -> file.cypher(null, "RETURN $g", null, null, null))
                .hasMessageContaining("Datenbank-Ablage");
        return built;
    }

    private static String message(Runnable r) {
        try {
            r.run();
            return "(kein Fehler)";
        } catch (RuntimeException e) {
            return e.getMessage();
        }
    }

    private long nodes(GraphReader generation) {
        return ((Number) generation.query("MATCH (n:CodeNode {g: $g}) RETURN count(n) AS c", null, 1).rows()
                .getFirst().getFirst()).longValue();
    }

    @Test
    void graphsAreBoundToProjectAndBranchAndRebuildsReplaceTheGeneration() throws Exception {
        GraphTools t = tools(GraphModule.STORAGE_DATABASE);
        t.build(null, false);
        assertThat(t.build(null, false)).startsWith("Graph ist aktuell");
        GraphReader main = service(GraphModule.STORAGE_DATABASE).graph(null, "main");
        assertThat(nodes(main)).isGreaterThan(20);

        // Feature-Branch mit zusätzlicher Klasse
        git.checkout().setCreateBranch(true).setName("feature/x").call();
        GraphToolsTestFixture.writeSource(project, "com/acme/shop/Extra.java",
                "package com.acme.shop;\nclass Extra { void x() { new Money().add(2); } }\n");
        String built = t.build(null, false);
        assertThat(built).startsWith("Graph gebaut").contains("Branch feature/x");
        assertThat(t.find(null, "Extra", "class", null, null)).contains("com.acme.shop.Extra");
        assertThat(t.find(null, "Extra", "class", null, "main")).startsWith("Keine Treffer");
        assertThat(t.branches(null)).contains("ausgecheckt: feature/x", "- feature/x *", "- main @ ");
        assertThat(graphId("main")).isEqualTo(((ArcadeGraphReader) main).graphId()); // main unberührt

        // Neubau ersetzt die Generation vollständig (keine Reste der alten)
        GraphReader feature = service(GraphModule.STORAGE_DATABASE).graph(null, "feature/x");
        t.build(null, true);
        assertThat(graphId("feature/x")).isNotEqualTo(((ArcadeGraphReader) feature).graphId());
        storage.awaitCleanup();
        assertThat(nodes(feature)).isZero();

        // Anderer Branch ohne Graph: verständlicher Fehler
        assertThatThrownBy(() -> t.report(null, 5, "gibt-es-nicht")).hasMessageContaining("kein Graph gespeichert")
                .hasMessageContaining("main");
        // Bauen eines nicht ausgecheckten Branches wird abgelehnt
        assertThatThrownBy(() -> service(GraphModule.STORAGE_DATABASE).build(null, "main", false,
                ModuleAction.Progress.NONE)).hasMessageContaining("ausgecheckt");

        // Branch löschen -> Graph verschwindet beim nächsten Aufbau automatisch
        git.checkout().setName("main").call();
        git.branchDelete().setBranchNames("feature/x").setForce(true).call();
        String again = t.build(null, false);
        assertThat(again).contains("Entfernt (Branch existiert nicht mehr): feature/x");
        assertThat(t.branches(null)).doesNotContain("feature/x");
    }

    @Test
    void commitsAreIndexedIncrementallyAndNewBranchesTakeOverTheGraph() throws Exception {
        GraphTools db = tools(GraphModule.STORAGE_DATABASE);
        db.build(null, false);
        String generation = graphId("main");

        // Commit mit geändertem Rumpf: nur diese Datei wird gelesen, gespeichert nur der Unterschied – in place
        Path order = project.resolve("src/main/java/com/acme/shop/Order.java");
        String source = Files.readString(order);
        Files.writeString(order, source.replace("    void print(String s) {\n    }",
                "    void print(String s) {\n        new Money().add(3);\n    }"));
        git.add().addFilepattern(".").call();
        git.commit().setMessage("print zählt").setSign(false).call();
        String built = db.build(null, false);
        assertThat(built).startsWith("Graph gebaut").contains("Inkrementell: 1 Datei(en) gelesen");
        assertThat(graphId("main")).isEqualTo(generation);

        // neue Methode (Deklaration geändert): weiter inkrementell gespeichert, Kanten aller Dateien neu aufgelöst
        Files.writeString(order, Files.readString(order).replace("    void add(int x) {\n    }",
                "    void add(int x) {\n    }\n\n    void twice() {\n        add(1);\n        add(1);\n    }"));
        assertThat(db.build(null, false)).contains("Inkrementell: 1 Datei(en) gelesen");

        // Ergebnis wie ein kompletter Neuaufbau (Datei-Ablage baut immer komplett)
        GraphTools file = tools(GraphModule.STORAGE_FILE);
        file.build(null, true);
        for (String node : List.of("Printer", "Money", "Money#add", "Money#twice", "OrderService", "Printer#print(String)")) {
            assertThat(db.explain(null, node, null, null)).as(node).isEqualTo(file.explain(null, node, null, null));
        }
        assertThat(db.report(null, 10, null).lines().skip(2).toList())
                .isEqualTo(file.report(null, 10, null).lines().skip(2).toList());

        // nach einem Neustart der App (kein Zwischenstand): komplett, Ergebnis gleich
        GraphService.forgetSessions();
        GraphToolsTestFixture.writeSource(project, "com/acme/shop/Extra.java",
                "package com.acme.shop;\nclass Extra { void x() { new Money().twice(); } }\n");
        assertThat(db.build(null, false)).startsWith("Graph gebaut").doesNotContain("Inkrementell:");
        assertThat(graphId("main")).isNotEqualTo(generation);
        git.add().addFilepattern(".").call();
        git.commit().setMessage("extra").setSign(false).call();

        // neuer Branch mit gleichem Stand: übernimmt den Graphen, ohne zu bauen
        git.checkout().setCreateBranch(true).setName("feature/neu").call();
        assertThat(db.build(null, false)).startsWith("Graph gebaut")
                .contains("Übernommen von Branch main (gleicher Stand, nichts gebaut)");
        assertThat(graphId("feature/neu")).isEqualTo(graphId("main"));
        // erster eigener Commit auf dem Branch: eigene Generation, main bleibt
        GraphToolsTestFixture.writeSource(project, "com/acme/shop/Extra.java",
                "package com.acme.shop;\nclass Extra { void y() { } }\n");
        db.build(null, false);
        assertThat(graphId("feature/neu")).isNotEqualTo(graphId("main"));
        assertThat(db.find(null, "Extra#x", null, null, "main")).contains("Extra#x()");
        assertThat(db.find(null, "Extra#x", null, null, null)).startsWith("Keine Treffer");
    }

    @Test
    void commitsAndNewBranchesAreIndexedAutomatically() throws Exception {
        ModuleConfig cfg = config(project, GraphModule.STORAGE_DATABASE);
        GraphAutoIndexer indexer = new GraphAutoIndexer(() -> cfg, c -> new GraphService(c, () -> storage),
                java.time.Duration.ofSeconds(1));
        assertThat(indexer.poll()).isEmpty(); // Start: nur den Stand merken
        assertThat(tools(GraphModule.STORAGE_DATABASE).branches(null)).contains("Noch kein Graph");

        // Commit → indiziert (beim ersten Mal komplett)
        GraphToolsTestFixture.writeSource(project, "com/acme/shop/Extra.java",
                "package com.acme.shop;\nclass Extra { void x() { new Money().add(2); } }\n");
        commit("extra");
        assertThat(indexer.poll()).singleElement().asString().contains("(Branch main)", "komplett gebaut");
        assertThat(indexer.poll()).isEmpty(); // nichts geändert

        // nächster Commit → inkrementell
        GraphToolsTestFixture.writeSource(project, "com/acme/shop/Extra.java",
                "package com.acme.shop;\nclass Extra { void x() { new Money().add(3); new Printer(); } }\n");
        commit("extra 2");
        assertThat(indexer.poll()).singleElement().asString().contains("inkrementell: 1 Datei(en) gelesen");

        // neuer Branch ohne Auschecken (git branch) → übernimmt den Graphen von main
        git.branchCreate().setName("feature/y").call();
        assertThat(indexer.poll()).singleElement().asString()
                .contains("(Branch feature/y)", "übernommen von Branch main");
        assertThat(graphId("feature/y")).isEqualTo(graphId("main"));

        // neuer Branch ausgecheckt (checkout -b) → ebenso übernommen
        git.checkout().setCreateBranch(true).setName("feature/z").call();
        assertThat(indexer.poll()).singleElement().asString()
                .contains("(Branch feature/z)", "übernommen von Branch", "gleicher Stand");
        assertThat(graphId("feature/z")).isEqualTo(graphId("main"));

        // abgeschaltet: nichts
        Map<String, String> off = values(project, GraphModule.STORAGE_DATABASE);
        off.put(GraphModule.AUTO_INDEX, "false");
        ModuleConfig offCfg = ModuleConfig.of(new GraphModule().configSchema(), off);
        GraphAutoIndexer disabled = new GraphAutoIndexer(() -> offCfg, c -> new GraphService(c, () -> storage),
                java.time.Duration.ofSeconds(1));
        disabled.poll();
        commit("leer");
        assertThat(disabled.poll()).isEmpty();
    }

    @Test
    void failedAutoIndexingIsRetriedWithoutANewCommit() throws Exception {
        ModuleConfig cfg = config(project, GraphModule.STORAGE_DATABASE);
        java.util.concurrent.atomic.AtomicBoolean down = new java.util.concurrent.atomic.AtomicBoolean();
        GraphAutoIndexer indexer = new GraphAutoIndexer(() -> cfg, c -> new GraphService(c, () -> {
            if (down.get()) {
                throw new IllegalStateException("Backend kurz nicht erreichbar");
            }
            return storage;
        }), java.time.Duration.ofSeconds(1));
        indexer.retryBase = java.time.Duration.ZERO;
        assertThat(indexer.poll()).isEmpty();

        GraphToolsTestFixture.writeSource(project, "com/acme/shop/Extra.java",
                "package com.acme.shop;\nclass Extra { void x() { new Money().add(2); } }\n");
        commit("extra");
        down.set(true);
        assertThat(indexer.poll()).singleElement().asString().contains("Backend kurz nicht erreichbar");
        assertThat(indexer.poll()).isEmpty(); // weiter gestört: erneut versucht, aber nicht noch einmal gemeldet

        // Backend wieder da: derselbe Commit wird jetzt indiziert, obwohl nichts Neues dazukam
        down.set(false);
        assertThat(indexer.poll()).singleElement().asString().contains("(Branch main)", "komplett gebaut");
        assertThat(indexer.poll()).isEmpty();
    }

    @Test
    void failedAutoIndexingBacksOffBeforeTheNextAttempt() throws Exception {
        ModuleConfig cfg = config(project, GraphModule.STORAGE_DATABASE);
        java.util.concurrent.atomic.AtomicBoolean down = new java.util.concurrent.atomic.AtomicBoolean();
        GraphAutoIndexer indexer = new GraphAutoIndexer(() -> cfg, c -> new GraphService(c, () -> {
            if (down.get()) {
                throw new IllegalStateException("Backend kurz nicht erreichbar");
            }
            return storage;
        }), java.time.Duration.ofSeconds(1));
        indexer.retryBase = java.time.Duration.ofHours(1);
        assertThat(indexer.poll()).isEmpty();
        commit("extra");
        down.set(true);
        assertThat(indexer.poll()).hasSize(1);

        down.set(false);
        assertThat(indexer.poll()).isEmpty(); // Wartezeit läuft noch
        assertThat(tools(GraphModule.STORAGE_DATABASE).branches(null)).contains("Noch kein Graph");
    }

    private void commit(String message) throws Exception {
        git.add().addFilepattern(".").call();
        git.commit().setMessage(message).setAllowEmpty(true).setSign(false).call();
    }

    @Test
    void deletedBranchesAreOnlyCleanedUpWhereTheyWereBuilt() throws Exception {
        // feature/x hat „jemand anders“ gebaut – den Branch kennt das eigene Git nicht, der Graph bleibt
        git.checkout().setCreateBranch(true).setName("feature/x").call();
        tools(GraphModule.STORAGE_DATABASE).build(null, false);
        git.checkout().setName("main").call();
        git.branchDelete().setBranchNames("feature/x").setForce(true).call();
        GraphProvider others = new GraphProvider() {
            @Override
            public String describe() {
                return storage.describe();
            }

            @Override
            public String location(Key key) {
                return storage.location(key);
            }

            @Override
            public State state(Key key) {
                return storage.state(key);
            }

            @Override
            public GraphReader reader(Key key) {
                return storage.reader(key);
            }

            @Override
            public GraphReader write(Key key, CodeGraph.GraphFile data) {
                return storage.write(key, data);
            }

            @Override
            public List<Stored> branches(Key project) {
                return storage.branches(project).stream().map(s -> !"feature/x".equals(s.branch()) ? s
                        : new Stored(s.branch(), s.commit(), s.builtAt(), s.files(), s.nodes(), s.edges(), s.location(),
                        "anna@anderer-rechner")).toList();
            }

            @Override
            public boolean delete(Key key) {
                return storage.delete(key);
            }
        };
        GraphTools t = new GraphTools(new GraphService(config(project, GraphModule.STORAGE_DATABASE), () -> others));
        assertThat(t.build(null, false)).doesNotContain("Entfernt");
        assertThat(t.branches(null)).contains("feature/x");

        // selbst gebaut: wird entfernt
        String built = tools(GraphModule.STORAGE_DATABASE).build(null, false);
        assertThat(built).contains("Entfernt (Branch existiert nicht mehr): feature/x");
    }

    private String graphId(String branch) {
        return ((ArcadeGraphReader) service(GraphModule.STORAGE_DATABASE).graph(null, branch)).graphId();
    }

    @Test
    void indexActionAndConnectionTest() {
        ModuleConfig cfg = config(project, GraphModule.STORAGE_DATABASE);
        GraphModule module = new GraphModule((Supplier<GraphProvider>) () -> storage);
        GraphIndexAction action = (GraphIndexAction) module.actions().getFirst();
        String target = action.targets(cfg).getFirst();
        assertThat(action.describe(cfg, target)).contains("Branch main").endsWith("noch kein Graph");
        ModuleAction.ActionResult r = action.run(cfg, target, Set.of(), ModuleAction.Progress.NONE);
        assertThat(r.success()).isTrue();
        assertThat(r.message()).startsWith("Graph gebaut").contains("(Branch main)", "4 Dateien", "ArcadeDB");
        assertThat(action.describe(cfg, target)).contains("Branch main", "Graph vom", "4 Dateien");

        assertThat(module.testConnection(cfg).message()).contains("Verbunden: ArcadeDB eingebettet", "ArcadeDB 26.",
                "[Graph: main]");
        // ohne Graph-Storage (Backend nicht verbunden): verständliche Meldung
        assertThat(new GraphModule().testConnection(cfg).message()).contains("Keine Graph-Storage verfügbar");
        // ältere Einstellung „neo4j“ gilt als Datenbank-Ablage
        assertThat(GraphService.usesDatabase(ModuleConfig.of(new GraphModule().configSchema(),
                Map.of(GraphModule.STORAGE, "neo4j")))).isTrue();
    }
}
