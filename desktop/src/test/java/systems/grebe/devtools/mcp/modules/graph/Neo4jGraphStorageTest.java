package systems.grebe.devtools.mcp.modules.graph;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import systems.grebe.devtools.mcp.core.ModuleAction;
import systems.grebe.devtools.mcp.core.ModuleConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Neo4j-Ablage gegen einen echten Server. Verbindung aus {@code DEVTOOLS_TEST_NEO4J_URI} (Standard
 * {@code bolt://localhost:7687}), {@code DEVTOOLS_TEST_NEO4J_USER} und {@code DEVTOOLS_TEST_NEO4J_PASSWORD}; ist kein
 * Server erreichbar, werden die Tests übersprungen.
 *
 * <p>Kernprüfung: Alle Abfragen liefern über Cypher (Neo4j) exakt dieselbe Ausgabe wie auf dem geladenen Graphen
 * (Datei-Ablage).
 */
class Neo4jGraphStorageTest {

    static final String URI = env("DEVTOOLS_TEST_NEO4J_URI", "bolt://localhost:7687");
    static final String USER = env("DEVTOOLS_TEST_NEO4J_USER", "neo4j");
    static final String PASSWORD = env("DEVTOOLS_TEST_NEO4J_PASSWORD", "password4neo4j");

    @TempDir
    Path project;

    Git git;

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v;
    }

    @BeforeEach
    void setUp() throws Exception {
        try (Driver d = GraphDatabase.driver(URI, AuthTokens.basic(USER, PASSWORD))) {
            d.verifyConnectivity();
        } catch (RuntimeException e) {
            Assumptions.abort("Neo4j " + URI + " nicht erreichbar: " + e.getMessage());
        }
        Files.writeString(project.resolve("build.gradle"), "");
        GraphToolsTestFixture.write(project);
        git = Git.init().setDirectory(project.toFile()).setInitialBranch("main").call();
        git.add().addFilepattern(".").call();
        git.commit().setMessage("init").setSign(false).call();
    }

    @AfterEach
    void tearDown() {
        if (git != null) {
            try {
                Neo4jGraphStorage store = (Neo4jGraphStorage) service(GraphModule.STORAGE_NEO4J).storage();
                store.branches(project).forEach(s -> store.delete(project, s.branch()));
            } finally {
                git.close();
            }
        }
    }

    private Map<String, String> values(String storage) {
        Map<String, String> v = new HashMap<>();
        v.put(GraphModule.PROJECTS, project.toString());
        v.put(GraphModule.STORAGE, storage);
        v.put(GraphModule.NEO4J_URI, URI);
        v.put(GraphModule.NEO4J_USER, USER);
        v.put(GraphModule.NEO4J_PASSWORD, PASSWORD);
        return v;
    }

    private GraphService service(String storage) {
        return new GraphService(ModuleConfig.of(new GraphModule().configSchema(), values(storage)));
    }

    private GraphTools tools(String storage) {
        return new GraphTools(service(storage));
    }

    private long count(String cypher, Map<String, Object> params) {
        Neo4jConnection c = ((Neo4jGraphStorage) service(GraphModule.STORAGE_NEO4J).storage()).connection();
        return c.client.query(cypher).bindAll(params).fetchAs(Long.class).mappedBy((t, r) -> r.get(0).asLong())
                .one().orElse(0L);
    }

    @Test
    void cypherQueriesAnswerExactlyLikeTheInMemoryGraph() {
        GraphTools file = tools(GraphModule.STORAGE_FILE);
        GraphTools neo = tools(GraphModule.STORAGE_NEO4J);
        file.build(null, true);
        String built = neo.build(null, true);
        assertThat(built).startsWith("Graph gebaut").contains("Branch main", "neo4j " + URI, "GraphBranch ");

        record Q(String name, java.util.function.Function<GraphTools, String> call) {
        }
        List<Q> queries = List.of(
                new Q("find glob", t -> t.find(null, "Order*", "class", null, null)),
                new Q("find method", t -> t.find(null, "save", "method", null, null)),
                new Q("find all", t -> t.find(null, "o", null, 50, null)),
                new Q("find *dao", t -> t.find(null, "*repository", null, null, null)),
                new Q("explain member", t -> t.explain(null, "OrderRepository#save", null, null)),
                new Q("explain type", t -> t.explain(null, "OrderService", null, null)),
                new Q("explain file", t -> t.explain(null, "src/main/java/com/acme/shop/Order.java", null, null)),
                new Q("explain dot", t -> t.explain(null, "Printer.print(String)", null, null)),
                new Q("explain ctor", t -> t.explain(null, "OrderService#<init>", null, null)),
                new Q("callers", t -> t.neighbors(null, "Money#add", "in", List.of("calls"), 3, null, null)),
                new Q("impls", t -> t.neighbors(null, "OrderRepository", "in", List.of("implements"), 1, null, null)),
                new Q("both all", t -> t.neighbors(null, "OrderService", "both", List.of("all"), 2, 40, null)),
                new Q("path", t -> t.path(null, "OrderService#start", "JpaOrderRepository#findById", false, null, null, null)),
                new Q("path directed", t -> t.path(null, "OrderService#save", "Money#add", true, List.of("calls"), 4, null)),
                new Q("no path", t -> t.path(null, "Money", "Weird", true, List.of("calls"), 3, null)),
                new Q("query", t -> t.query(null, "Wie wird ein Order gespeichert (save)?", null, null)),
                new Q("query small", t -> t.query(null, "Printer print", 6, null)));
        for (Q q : queries) {
            assertThat(q.call().apply(neo)).as(q.name()).isEqualTo(q.call().apply(file));
        }
        // Bericht: identisch bis auf die Ortsangabe
        String fileReport = file.report(null, 10, null);
        String neoReport = neo.report(null, 10, null);
        assertThat(neoReport.lines().skip(2).toList()).isEqualTo(fileReport.lines().skip(2).toList());
        assertThat(neoReport.lines().findFirst().orElseThrow()).isEqualTo(fileReport.lines().findFirst().orElseThrow())
                .contains("(Branch main @ ");
        // Fehlermeldungen ebenso
        for (String spec : List.of("Printer#print", "Gibtsnicht", "Ordr")) {
            assertThat(message(() -> neo.explain(null, spec, null, null)))
                    .isEqualTo(message(() -> file.explain(null, spec, null, null)));
        }
    }

    private static String message(Runnable r) {
        try {
            r.run();
            return "(kein Fehler)";
        } catch (RuntimeException e) {
            return e.getMessage();
        }
    }

    @Test
    void graphsAreBoundToProjectAndBranchAndRebuildsReplaceTheGeneration() throws Exception {
        GraphTools t = tools(GraphModule.STORAGE_NEO4J);
        t.build(null, false);
        assertThat(t.build(null, false)).startsWith("Graph ist aktuell");
        String gMain = graphId("main");
        long nodesMain = count("MATCH (n:CodeNode {g: $g}) RETURN count(n)", Map.of("g", gMain));
        assertThat(nodesMain).isGreaterThan(20);
        assertThat(count("MATCH (:GraphProject {root: $r})-[:HAS_BRANCH]->(b:GraphBranch) RETURN count(b)",
                Map.of("r", Neo4jGraphStorage.rootKey(project)))).isEqualTo(1);

        // Feature-Branch mit zusätzlicher Klasse
        git.checkout().setCreateBranch(true).setName("feature/x").call();
        GraphToolsTestFixture.writeSource(project, "com/acme/shop/Extra.java",
                "package com.acme.shop;\nclass Extra { void x() { new Money().add(2); } }\n");
        String built = t.build(null, false);
        assertThat(built).startsWith("Graph gebaut").contains("Branch feature/x");
        assertThat(t.find(null, "Extra", "class", null, null)).contains("com.acme.shop.Extra");
        assertThat(t.find(null, "Extra", "class", null, "main")).startsWith("Keine Treffer");
        assertThat(t.branches(null)).contains("ausgecheckt: feature/x", "- feature/x *", "- main @ ");
        assertThat(graphId("main")).isEqualTo(gMain); // main unberührt

        // Neubau ersetzt die Generation vollständig (keine Reste der alten)
        String gFeature = graphId("feature/x");
        t.build(null, true);
        assertThat(graphId("feature/x")).isNotEqualTo(gFeature);
        assertThat(count("MATCH (n:CodeNode {g: $g}) RETURN count(n)", Map.of("g", gFeature))).isZero();
        assertThat(count("MATCH (n:SourceFile {g: $g}) RETURN count(n)", Map.of("g", gFeature))).isZero();

        // Anderer Branch ohne Graph: verständlicher Fehler
        assertThatThrownBy(() -> t.report(null, 5, "gibt-es-nicht")).hasMessageContaining("kein Graph gespeichert")
                .hasMessageContaining("main");
        // Bauen eines nicht ausgecheckten Branches wird abgelehnt
        assertThatThrownBy(() -> service(GraphModule.STORAGE_NEO4J).build(null, "main", false,
                ModuleAction.Progress.NONE)).hasMessageContaining("ausgecheckt");

        // Branch löschen -> Graph verschwindet beim nächsten Aufbau automatisch
        git.checkout().setName("main").call();
        git.branchDelete().setBranchNames("feature/x").setForce(true).call();
        String again = t.build(null, false);
        assertThat(again).contains("Entfernt (Branch existiert nicht mehr): feature/x");
        assertThat(t.branches(null)).doesNotContain("feature/x");
        assertThat(count("MATCH (b:GraphBranch {branch: 'feature/x', root: $r}) RETURN count(b)",
                Map.of("r", Neo4jGraphStorage.rootKey(project)))).isZero();
    }

    private String graphId(String branch) {
        Neo4jGraphStorage store = (Neo4jGraphStorage) service(GraphModule.STORAGE_NEO4J).storage();
        return store.branchEntity(new GraphStorage.Key("p", project, branch)).getGraphId();
    }

    @Test
    void cypherToolIsReadOnlyAndScopedToTheGraph() {
        GraphTools t = tools(GraphModule.STORAGE_NEO4J);
        t.build(null, false);
        String out = t.cypher(null, "MATCH (m:Method {g: $g})<-[c:CALLS]-() RETURN m.id AS id, "
                + "sum(coalesce(c.count, 1)) AS n ORDER BY n DESC, id LIMIT 3", null, null, null);
        assertThat(out).startsWith("3 Zeile(n) · id | n");
        assertThat(t.cypher(null, "MATCH (t:Type {g: $g, name: $name}) RETURN t", Map.of("name", "OrderService"), null,
                null)).contains("com.acme.shop.OrderService [class]");
        assertThat(t.cypher(null, "MATCH (n:CodeNode {g: $g}) RETURN n.id", null, 2, null)).startsWith("2+ Zeile(n)")
                .contains("abgeschnitten");
        assertThatThrownBy(() -> t.cypher(null, "MATCH (n:CodeNode {g: $g}) DETACH DELETE n", null, null, null))
                .hasMessageContaining("nur lesend");
        assertThatThrownBy(() -> t.cypher(null, "MATCH (n:CodeNode) RETURN count(n)", null, null, null))
                .hasMessageContaining("$g");
        // Auch ohne erkennbares Schlüsselwort schreibt nichts: Lesetransaktion
        assertThatThrownBy(() -> t.cypher(null, "WITH $g AS g CALL { WITH g CREATE (:X {g: g}) } RETURN 1", null, null,
                null)).hasMessageContaining("nur lesend");
        assertThatThrownBy(() -> tools(GraphModule.STORAGE_FILE).cypher(null, "RETURN $g", null, null, null))
                .hasMessageContaining("Neo4j-Ablage");
    }

    @Test
    void indexActionAndConnectionTest() {
        ModuleConfig cfg = ModuleConfig.of(new GraphModule().configSchema(), values(GraphModule.STORAGE_NEO4J));
        GraphIndexAction action = (GraphIndexAction) new GraphModule().actions().getFirst();
        String target = action.targets(cfg).getFirst();
        assertThat(action.describe(cfg, target)).contains("Branch main").endsWith("noch kein Graph");
        ModuleAction.ActionResult r = action.run(cfg, target, Set.of(), ModuleAction.Progress.NONE);
        assertThat(r.success()).isTrue();
        assertThat(r.message()).startsWith("Graph gebaut").contains("(Branch main)", "4 Dateien", "neo4j");
        assertThat(action.describe(cfg, target)).contains("Branch main", "Graph vom", "4 Dateien");

        assertThat(new GraphModule().testConnection(cfg).message()).contains("Verbunden: Neo4j Kernel", "[Graph: main]");
        // Anmeldung: fehlendes bzw. falsches Passwort -> verständliche Meldung, und zwar vor dem Parsen
        Map<String, String> noPassword = values(GraphModule.STORAGE_NEO4J);
        noPassword.remove(GraphModule.NEO4J_PASSWORD);
        ModuleConfig noPwCfg = ModuleConfig.of(new GraphModule().configSchema(), noPassword);
        assertThat(new GraphModule().testConnection(noPwCfg).message()).contains("Anmeldung abgelehnt",
                "kein Neo4j-Passwort eingetragen");
        assertThatThrownBy(() -> action.run(noPwCfg, target, Set.of(GraphIndexAction.FORCE), ModuleAction.Progress.NONE))
                .hasMessageContaining("kein Neo4j-Passwort eingetragen");
        Map<String, String> badPassword = values(GraphModule.STORAGE_NEO4J);
        badPassword.put(GraphModule.NEO4J_PASSWORD, "falsch");
        assertThat(new GraphModule().testConnection(ModuleConfig.of(new GraphModule().configSchema(), badPassword))
                .message()).contains("Anmeldung abgelehnt", "Benutzer 'neo4j' oder Passwort ist falsch");

        Map<String, String> wrong = values(GraphModule.STORAGE_NEO4J);
        wrong.put(GraphModule.NEO4J_URI, "bolt://localhost:1");
        assertThat(new GraphModule().testConnection(ModuleConfig.of(new GraphModule().configSchema(), wrong)).success())
                .isFalse();
        Neo4jConnection.closeAll();
    }
}
