package systems.grebe.devtools.mcp.modules.graph;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import systems.grebe.devtools.mcp.core.ModuleAction;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Confidence;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Edge;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Kind;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Relation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraphToolsTest {

    @TempDir
    Path project;

    @BeforeEach
    void writeFixture() throws Exception {
        Files.writeString(project.resolve("build.gradle"), "");
        GraphToolsTestFixture.write(project);
    }

    private void write(String rel, String content) throws Exception {
        Path file = project.resolve("src/main/java").resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private GraphTools tools() {
        Map<String, String> values = new HashMap<>();
        values.put(GraphModule.PROJECTS, project.toString());
        values.put(GraphModule.STORAGE, GraphModule.STORAGE_FILE);
        return new GraphTools(new GraphService(ModuleConfig.of(new GraphModule().configSchema(), values)));
    }

    private CodeGraph graph() {
        tools().build(null, false);
        return GraphStore.load(project);
    }

    private static Edge edge(CodeGraph g, String from, String to, Relation rel) {
        return g.outgoing(from).stream().filter(e -> e.to().equals(to) && e.rel() == rel).findFirst()
                .orElseThrow(() -> new AssertionError("Kante fehlt: " + from + " --" + rel + "--> " + to
                        + "\nvorhanden: " + g.outgoing(from)));
    }

    @Test
    void extractsDeclarationsWithLocationSignatureAndJavadoc() {
        CodeGraph g = graph();
        assertThat(g.node("com.acme.shop.OrderService").kind()).isEqualTo(Kind.CLASS);
        assertThat(g.node("com.acme.shop.OrderService").doc()).isEqualTo("Service für Aufträge.");
        assertThat(g.node("com.acme.shop.OrderService").line()).isEqualTo(7);
        assertThat(g.node("com.acme.shop.Service").kind()).isEqualTo(Kind.INTERFACE);
        assertThat(g.node("com.acme.shop.Order").kind()).isEqualTo(Kind.RECORD);
        assertThat(g.node("com.acme.shop.Order#amount").kind()).isEqualTo(Kind.FIELD); // Record-Komponente
        assertThat(g.node("com.acme.shop.Order#amount()").kind()).isEqualTo(Kind.METHOD); // Accessor
        assertThat(g.node("com.acme.shop.OrderService#<init>(OrderRepository)").kind()).isEqualTo(Kind.CONSTRUCTOR);
        assertThat(g.node("com.acme.shop.OrderService#save(Order)").signature()).isEqualTo("void save(Order o)");
        assertThat(g.node("com.acme.shop.OrderService#repo").modifiers()).isEqualTo("private final");
        assertThat(g.node("file:src/main/java/com/acme/shop/Order.java").kind()).isEqualTo(Kind.FILE);
        edge(g, "pkg:com.acme.shop", "file:src/main/java/com/acme/shop/Order.java", Relation.CONTAINS);
        edge(g, "file:src/main/java/com/acme/shop/OrderService.java", "com.acme.shop.repo.OrderRepository", Relation.IMPORTS);
        edge(g, "file:src/main/java/com/acme/shop/OrderService.java", "java.util.List", Relation.IMPORTS);
        assertThat(g.node("java.util.List").kind()).isEqualTo(Kind.EXTERNAL);
    }

    @Test
    void resolvesHierarchyAnnotationsAndFieldTypes() {
        CodeGraph g = graph();
        edge(g, "com.acme.shop.OrderService", "com.acme.shop.Service", Relation.IMPLEMENTS);
        edge(g, "com.acme.shop.repo.JpaOrderRepository", "com.acme.shop.repo.OrderRepository", Relation.IMPLEMENTS);
        edge(g, "com.acme.shop.repo.JpaOrderRepository", "jakarta.persistence.Entity", Relation.ANNOTATED_WITH);
        edge(g, "com.acme.shop.OrderService#repo", "com.acme.shop.repo.OrderRepository", Relation.HAS_TYPE);
        // @Override vorhanden -> EXTRACTED, ohne @Override nur INFERRED
        assertThat(edge(g, "com.acme.shop.repo.JpaOrderRepository#save(Order)",
                "com.acme.shop.repo.OrderRepository#save(Order)", Relation.OVERRIDES).conf()).isEqualTo(Confidence.EXTRACTED);
        assertThat(edge(g, "com.acme.shop.repo.JpaOrderRepository#findById(long)",
                "com.acme.shop.repo.OrderRepository#findById(long)", Relation.OVERRIDES).conf()).isEqualTo(Confidence.INFERRED);
        assertThat(edge(g, "com.acme.shop.OrderService#start()", "com.acme.shop.Service#start()", Relation.OVERRIDES)
                .conf()).isEqualTo(Confidence.EXTRACTED);
    }

    @Test
    void buildsCallGraphWithConfidence() {
        CodeGraph g = graph();
        String save = "com.acme.shop.OrderService#save(Order)";
        // Feld mit deklariertem Typ
        assertThat(edge(g, save, "com.acme.shop.repo.OrderRepository#save(Order)", Relation.CALLS).conf())
                .isEqualTo(Confidence.EXTRACTED);
        // Aufruf ohne Empfänger im eigenen Typ, statischer Aufruf über Klassennamen
        assertThat(edge(g, save, "com.acme.shop.OrderService#validate(Order)", Relation.CALLS).conf())
                .isEqualTo(Confidence.EXTRACTED);
        assertThat(edge(g, save, "com.acme.shop.Util#check(Object)", Relation.CALLS).conf()).isEqualTo(Confidence.EXTRACTED);
        // Parameter mit Typ, dann Kette über den Rückgabetyp -> INFERRED
        assertThat(edge(g, save, "com.acme.shop.Order#total()", Relation.CALLS).conf()).isEqualTo(Confidence.EXTRACTED);
        Edge add = edge(g, save, "com.acme.shop.Money#add(int)", Relation.CALLS);
        assertThat(add.conf()).isEqualTo(Confidence.INFERRED);
        assertThat(add.score()).isEqualTo(0.8);
        // Überladung gleicher Stelligkeit -> AMBIGUOUS auf beide
        assertThat(edge(g, save, "com.acme.shop.Printer#print(Order)", Relation.CALLS).conf()).isEqualTo(Confidence.AMBIGUOUS);
        assertThat(edge(g, save, "com.acme.shop.Printer#print(String)", Relation.CALLS).conf()).isEqualTo(Confidence.AMBIGUOUS);
        // Konstruktor und Instanziierung
        edge(g, "com.acme.shop.Order#total()", "com.acme.shop.Money", Relation.INSTANTIATES);
        edge(g, "com.acme.shop.OrderService#printer", "com.acme.shop.Printer", Relation.INSTANTIATES);
        // Kette über eigene Methode
        String chain = "com.acme.shop.OrderService#chain(List)";
        edge(g, chain, "com.acme.shop.OrderService#load(long)", Relation.CALLS);
        assertThat(edge(g, chain, "com.acme.shop.Order#total()", Relation.CALLS).conf()).isEqualTo(Confidence.INFERRED);
        // unbekannter Empfänger (externe Liste): nur über den Namen geraten, einziger Kandidat
        Edge guessed = edge(g, chain, "com.acme.shop.Weird#frobnicate()", Relation.CALLS);
        assertThat(guessed.conf()).isEqualTo(Confidence.INFERRED);
        assertThat(guessed.score()).isEqualTo(0.6);
        // Methodenreferenz
        edge(g, chain, "com.acme.shop.OrderService#start()", Relation.CALLS);
        // Aufrufe auf externe Typen (List#get) erzeugen keine Kante
        assertThat(g.outgoing(chain)).noneMatch(e -> e.to().startsWith("java."));
    }

    @Test
    void writesSingleFileAndRebuildsOnlyOnChange() throws Exception {
        GraphTools t = tools();
        String first = t.build(null, false);
        Path file = project.resolve("devtools-fileinfo.graph");
        assertThat(first).startsWith("Graph gebaut").contains(file.toString()).contains("# Code-Graph");
        assertThat(Files.exists(file)).isTrue();
        String json = Files.readString(file);
        assertThat(json).startsWith("{\n  \"format\": \"devtools-fileinfo-graph\",")
                .contains("\"nodes\": [\n    {\"id\":")
                .contains("\"edges\": [\n    [");
        assertThat(Files.list(project).map(p -> p.getFileName().toString()))
                .contains("devtools-fileinfo.graph").doesNotContain("devtools-fileinfo.graph.tmp");

        assertThat(t.build(null, false)).startsWith("Graph ist aktuell");
        assertThat(t.build(null, true)).startsWith("Graph gebaut");

        write("com/acme/shop/Extra.java", "package com.acme.shop;\nclass Extra { void x() { new Money().add(2); } }\n");
        Files.writeString(project.resolve("src/main/java/com/acme/shop/Order.java"),
                Files.readString(project.resolve("src/main/java/com/acme/shop/Order.java")) + "\n// geändert\n");
        assertThat(t.build(null, false)).startsWith("Graph gebaut").contains("geändert 1, neu 1, entfernt 0");
        CodeGraph g = GraphStore.load(project);
        edge(g, "com.acme.shop.Extra#x()", "com.acme.shop.Money#add(int)", Relation.CALLS);
    }

    @Test
    void fileRoundTripRestoresIdenticalGraph() {
        CodeGraph built = graph();
        GraphStore.clearCache();
        CodeGraph loaded = GraphStore.load(project);
        assertThat(loaded).isNotSameAs(built);
        assertThat(loaded.nodes()).containsExactlyElementsOf(built.nodes());
        assertThat(loaded.edges()).containsExactlyElementsOf(built.edges());
        assertThat(loaded.data().files()).containsExactlyElementsOf(built.data().files());
        assertThat(loaded.data().communities()).containsExactlyElementsOf(built.data().communities());
        assertThat(loaded.data().stats().toString()).isEqualTo(built.data().stats().toString());
    }

    @Test
    void emptyProjectAndForeignFile() throws Exception {
        Path empty = Files.createDirectories(project.resolve("leer"));
        Files.writeString(empty.resolve("pom.xml"), "<project/>");
        Map<String, String> values = new HashMap<>();
        values.put(GraphModule.PROJECTS, empty.toString());
        values.put(GraphModule.STORAGE, GraphModule.STORAGE_FILE);
        GraphTools t = new GraphTools(new GraphService(ModuleConfig.of(new GraphModule().configSchema(), values)));
        assertThat(t.build(null, false)).startsWith("Graph gebaut").contains("0 Dateien, 0 Knoten");
        GraphStore.clearCache();
        assertThat(GraphStore.load(empty).nodes()).isEmpty();

        Files.writeString(empty.resolve(GraphStore.FILE_NAME), "{\n  \"format\": \"etwas-anderes\",\n  \"nodes\": [\n  ]\n}\n");
        GraphStore.clearCache();
        assertThatThrownBy(() -> GraphStore.load(empty)).hasMessageContaining("keine Graph-Datei")
                .hasMessageContaining("force=true");
        assertThat(t.build(null, false)).startsWith("Graph gebaut"); // wird überschrieben
    }

    @Test
    void queryToolsAnswerStructureQuestions() {
        GraphTools t = tools();
        t.build(null, false);

        assertThat(t.find(null, "Order*", "class", null, null)).contains("com.acme.shop.OrderService  [class]")
                .doesNotContain("[record]");
        assertThat(t.find(null, "save", "method", null, null))
                .contains("com.acme.shop.OrderService#save(Order)", "com.acme.shop.repo.OrderRepository#save(Order)");

        String explain = t.explain(null, "OrderRepository#save", null, null);
        assertThat(explain).contains("com.acme.shop.repo.OrderRepository#save(Order)  [method]  void save(Order o)")
                .contains("Eingehend:", "calls (1):", "com.acme.shop.OrderService#save(Order)",
                        "overrides (1):", "JpaOrderRepository#save(Order)");
        assertThat(t.explain(null, "OrderService", null, null)).contains("Doku: Service für Aufträge.", "Enthält (",
                "implements (1):", "com.acme.shop.Service");

        // Wer ruft Money#add auf – über zwei Ebenen
        String callers = t.neighbors(null, "Money#add", "in", List.of("calls"), 2, null, null);
        assertThat(callers).contains("<-- calls com.acme.shop.OrderService#save(Order)")
                .contains("INFERRED 0.8");
        // Implementierungen eines Interfaces
        assertThat(t.neighbors(null, "OrderRepository", "in", List.of("implements"), 1, null, null))
                .contains("<-- implements com.acme.shop.repo.JpaOrderRepository");

        String path = t.path(null, "OrderService#start", "JpaOrderRepository#findById", false, null, null, null);
        assertThat(path).startsWith("Pfad (").contains("--calls--> com.acme.shop.OrderService#load(long)")
                .contains("com.acme.shop.repo.JpaOrderRepository#findById(long)");
        assertThat(t.path(null, "Money", "Weird", true, List.of("calls"), 3, null)).startsWith("Kein Pfad");

        String answer = t.query(null, "Wie wird ein Order gespeichert (save)?", null, null);
        assertThat(answer).contains("Suchbegriffe: [order, gespeichert, save]")
                .contains("Beste Treffer:", "OrderService", "Zusammenhang (");

        String report = t.report(null, 5, null);
        assertThat(report).contains("## God Nodes", "## Communities", "## Meistaufgerufene Methoden",
                "OrderService", "EXTRACTED");
    }

    @Test
    void filesToolFindsFilesWithLineRanges() {
        GraphTools t = tools();
        t.build(null, false);
        String byName = t.files(null, "OrderRepository", null, null, null, null, null, null, null);
        assertThat(byName).startsWith("4 Dateien (Stichworte [orderrepository, order, repository]):\n"
                        + "src/main/java/com/acme/shop/repo/OrderRepository.java  (10 Z.)\n"
                        + "  OrderRepository [interface] Z5-9 · OrderRepository#findById(long) Z6 · "
                        + "OrderRepository#save(Order) Z8")
                .contains("JpaOrderRepository#save(Order) Z12-15", "graph_read");

        assertThat(t.files(null, "shop/repo/*.java", null, null, null, null, null, null, null))
                .startsWith("2 Dateien (Pfad):\n")
                .contains("src/main/java/com/acme/shop/repo/OrderRepository.java  (10 Z.)\n"
                        + "  OrderRepository [interface] Z5-9\n")
                .doesNotContain("OrderService");
        assertThat(t.files(null, "OrderService.java", null, null, null, null, null, null, null))
                .startsWith("1 Datei (Pfad):\nsrc/main/java/com/acme/shop/OrderService.java  (45 Z.)");
        assertThat(t.files(null, "*Repository", null, null, null, null, null, 2, null))
                .startsWith("3 Dateien (Namensmuster, die besten 2):\n");
        assertThat(t.files(null, "Nirgendwo", null, null, null, null, null, null, null)).startsWith("Keine Datei passt");

        // Wer verwendet OrderRepository – mit Begründung und Zeile je Datei
        String users = t.files(null, null, List.of("OrderRepository"), "in", null, null, null, null, null);
        assertThat(users).contains("Ausgang:\n  com.acme.shop.repo.OrderRepository  [interface]  Z5-9\n",
                        "2 verbundene Dateien (verwenden den Ausgang):",
                        "  OrderService#save(Order) --calls--> OrderRepository#save(Order) (Z25)",
                        "  JpaOrderRepository --implements--> OrderRepository (Z6)")
                .doesNotContain("Order.java ");

        // Was OrderService#save über zwei Ebenen aufruft; Aufrufe in der eigenen Datei zählen mit
        String deep = t.files(null, null, List.of("OrderService#save"), "out", List.of("calls"), 2, null, null, null);
        assertThat(deep).contains("vom Ausgang verwendet, Tiefe 2",
                "src/main/java/com/acme/shop/OrderService.java  (45 Z.)  [Ausgangsdatei]",
                "OrderService#save(Order) --calls--> Money#add(int) (INFERRED 0.8, Z26)");

        assertThatThrownBy(() -> t.files(null, "x", List.of("OrderService"), null, null, null, null, null, null))
                .hasMessageContaining("Genau eins angeben");
        assertThatThrownBy(() -> t.files(null, null, null, null, null, null, null, null, null))
                .hasMessageContaining("Genau eins angeben");
    }

    @Test
    void searchBuildsMissingGraphFirstAndSaysSo() {
        GraphTools t = tools();
        assertThat(GraphStore.files(project)).isEmpty();
        String first = t.files(null, "OrderRepository", null, null, null, null, null, null, null);
        assertThat(first).startsWith("Noch kein Graph für " + project.getFileName() + " (Branch ")
                .contains(" – eben gebaut in ", " ms, 4 Dateien.\n\n4 Dateien (Stichworte ")
                .contains("src/main/java/com/acme/shop/repo/OrderRepository.java  (10 Z.)");
        // danach ohne Hinweis – der Graph ist gespeichert
        assertThat(t.files(null, "OrderRepository", null, null, null, null, null, null, null)).startsWith("4 Dateien");
    }

    @Test
    void readBuildsMissingGraphFirst() {
        assertThat(tools().read(null, List.of("OrderRepository#save"), null, null, null, null))
                .startsWith("Noch kein Graph für ").contains("8\t    void save(Order o);");
    }

    @Test
    void filesToolCanHideTests() throws Exception {
        Path test = project.resolve("src/test/java/com/acme/shop/OrderServiceTest.java");
        Files.createDirectories(test.getParent());
        Files.writeString(test, "package com.acme.shop;\nclass OrderServiceTest { void saves() { new OrderService(null)"
                + ".save(null); } }\n");
        GraphTools t = tools();
        t.build(null, false);
        assertThat(t.files(null, null, List.of("OrderService#save"), "in", null, null, null, null, null))
                .contains("src/test/java/com/acme/shop/OrderServiceTest.java");
        assertThat(t.files(null, null, List.of("OrderService#save"), "in", null, null, false, null, null))
                .doesNotContain("OrderServiceTest");
        assertThat(t.files(null, "OrderService", null, null, null, null, false, null, null))
                .doesNotContain("OrderServiceTest");
    }

    @Test
    void readToolReadsMembersOutlinesAndRanges() {
        GraphTools t = tools();
        t.build(null, false);
        assertThat(t.read(null, List.of("OrderService#save"), null, null, null, null)).isEqualTo("""
                src/main/java/com/acme/shop/OrderService.java:24-30  (45 Z.)  com.acme.shop.OrderService#save(Order) [method]
                24\t    public void save(Order o) {
                25\t        repo.save(o);
                26\t        o.total().add(1);
                27\t        validate(o);
                28\t        Util.check(o);
                29\t        printer.print(o);
                30\t    }""");

        // Überladungen und mehrere Knoten in einem Aufruf, mit Kontextzeilen
        String several = t.read(null, List.of("OrderRepository#save", "Printer#print"), null, null, 1, null);
        assertThat(several).contains("OrderRepository.java:7-9  (10 Z.)", "8\t    void save(Order o);",
                "com.acme.shop.Printer#print(Order) [method]", "com.acme.shop.Printer#print(String) [method]",
                "24\t    void print(String s) {").doesNotContain("Achtung");

        // Gliederung: Signaturen und Zeilenbereiche ohne Rümpfe
        String outline = t.read(null, List.of("OrderService.java"), null, true, null, null);
        assertThat(outline).startsWith("src/main/java/com/acme/shop/OrderService.java  (45 Z.)  Gliederung\n"
                        + "Z7-40 public class OrderService  – Service für Aufträge.\n"
                        + "  Z8 private final OrderRepository repo\n")
                .contains("  Z24-30 public void save(Order o)\n", "Z42-44 interface Service\n  Z43 void start()\n")
                .doesNotContain("repo.save(o)");

        assertThat(t.read(null, List.of("Order.java"), "5+3", null, null, null)).isEqualTo("""
                src/main/java/com/acme/shop/Order.java:5-7  (32 Z.)
                5\t    Money total() {
                6\t        return new Money();
                7\t    }""");

        assertThat(t.read(null, List.of("OrderService"), null, null, null, 5))
                .contains("7\tpublic class OrderService implements Service {", "11\t")
                .endsWith("… abgeschnitten nach Zeile 11 (maxLines erhöhen oder mit lines='12-40' weiterlesen)");

        assertThatThrownBy(() -> t.read(null, List.of("java.util.List"), null, null, null, null))
                .hasMessageContaining("keinen Quelltext");
        assertThatThrownBy(() -> t.read(null, List.of("Order.java"), "40-50", null, null, null))
                .hasMessageContaining("außerhalb der Datei (1-32)");
    }

    @Test
    void readToolWarnsWhenFileChangedSinceBuild() throws Exception {
        GraphTools t = tools();
        t.build(null, false);
        Path file = project.resolve("src/main/java/com/acme/shop/repo/OrderRepository.java");
        Files.writeString(file, "// neu\n// noch neuer\n" + Files.readString(file));
        assertThat(t.read(null, List.of("OrderRepository#save"), null, null, null, null))
                .contains("Achtung: Datei wurde seit graph_build geändert");
    }

    @Test
    void queryMatchesWordFormsAndPrefersProductionCode() throws Exception {
        assertThat(GraphQueries.stem("gebucht")).isEqualTo(GraphQueries.stem("buchen")).isEqualTo("buch");
        assertThat(GraphQueries.stem("gespeichert")).isEqualTo(GraphQueries.stem("speichern"));
        assertThat(GraphQueries.stem("saved")).isEqualTo(GraphQueries.stem("saves"));
        write("com/acme/shop/Ledger.java", """
                package com.acme.shop;
                /** Hauptbuch. */
                public class Ledger {
                    public void buchen(Order o) { o.total(); }
                }
                """);
        Path test = project.resolve("src/test/java/com/acme/shop/LedgerBuchenTest.java");
        Files.createDirectories(test.getParent());
        Files.writeString(test, "package com.acme.shop;\nclass LedgerBuchenTest { void testBuchenWirdGebucht() { new Ledger().buchen(null); } }\n");
        GraphTools t = tools();
        t.build(null, false);
        String answer = t.query(null, "Wie wird gebucht?", 10, null);
        int prod = answer.indexOf("com.acme.shop.Ledger#buchen(Order)");
        int testHit = answer.indexOf("LedgerBuchenTest#testBuchenWirdGebucht()");
        assertThat(prod).as(answer).isPositive();
        assertThat(testHit < 0 || prod < testHit).as(answer).isTrue();
    }

    @Test
    void globMatchingIsAnchoredLikeShellPatterns() {
        String[] dao = "*dao".split("\\*", -1);
        assertThat(GraphQueries.globMatches(dao, "orderdao", true)).isTrue();
        assertThat(GraphQueries.globMatches(dao, "orderdaoimpl", true)).isFalse();
        assertThat(GraphQueries.globMatches(dao, "orderdaoimpl", false)).isTrue();
        String[] mid = "*buch*".split("\\*", -1);
        assertThat(GraphQueries.globMatches(mid, "fibubuchung", true)).isTrue();
        assertThat(GraphQueries.globMatches(mid, "fibu", true)).isFalse();
        String[] both = "order*dao".split("\\*", -1);
        assertThat(GraphQueries.globMatches(both, "orderdao", true)).isTrue();
        assertThat(GraphQueries.globMatches(both, "orderjpadao", true)).isTrue();
        assertThat(GraphQueries.globMatches(both, "orderdaox", true)).isFalse();
        assertThat(GraphQueries.globMatches("aba*aba".split("\\*", -1), "aba", true)).isFalse(); // keine Überlappung
    }

    @Test
    void indexActionReportsProgressStateAndCanBeCancelled() throws Exception {
        Map<String, String> values = new HashMap<>();
        values.put(GraphModule.PROJECTS, project.toString());
        values.put(GraphModule.STORAGE, GraphModule.STORAGE_FILE);
        ModuleConfig cfg = ModuleConfig.of(new GraphModule().configSchema(), values);
        GraphIndexAction action = (GraphIndexAction) new GraphModule().actions().getFirst();

        assertThat(action.targets(cfg)).containsExactly(project.getFileName().toString());
        String target = action.targets(cfg).getFirst();
        assertThat(action.describe(cfg, target)).endsWith("noch kein Graph");
        assertThat(action.run(cfg, null, Set.of(), ModuleAction.Progress.NONE).success()).isFalse();

        List<Double> fractions = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<String> messages = new java.util.concurrent.CopyOnWriteArrayList<>();
        ModuleAction.ActionResult first = action.run(cfg, target, Set.of(), (m, f) -> {
            messages.add(m);
            fractions.add(f);
        });
        assertThat(first.success()).isTrue();
        assertThat(first.message()).startsWith("Graph gebaut").contains("4 Dateien", GraphStore.FILE_NAME);
        assertThat(messages).anyMatch(m -> m.startsWith("Deklarationen"))
                .anyMatch(m -> m.startsWith("Aufrufe und Referenzen")).contains("Fertig");
        assertThat(fractions.getLast()).isEqualTo(1.0);
        assertThat(fractions.stream().filter(f -> f >= 0).toList()).isSorted();

        assertThat(action.describe(cfg, target)).contains("Graph vom", "4 Dateien", "Knoten");
        assertThat(action.run(cfg, target, Set.of(), ModuleAction.Progress.NONE).message()).startsWith("Graph ist aktuell");
        assertThat(action.run(cfg, target, Set.of(GraphIndexAction.FORCE), ModuleAction.Progress.NONE).message())
                .startsWith("Graph gebaut");

        // Abbruch: Interrupt vor dem Start -> kein Graph geschrieben, Fehler statt Ergebnis
        Files.delete(GraphStore.fileFor(project));
        GraphStore.clearCache();
        Thread t = Thread.ofVirtual().unstarted(() -> {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> action.run(cfg, target, Set.of(GraphIndexAction.FORCE), ModuleAction.Progress.NONE))
                    .hasMessageContaining("abgebrochen");
        });
        t.start();
        t.join();
        assertThat(Files.exists(GraphStore.fileFor(project))).isFalse();
    }

    @Test
    void reportsHelpfulErrors() {
        GraphTools t = tools();
        t.build(null, false);
        assertThatThrownBy(() -> t.explain(null, "Printer#print", null, null)).hasMessageContaining("mehrdeutig")
                .hasMessageContaining("Printer#print(Order)").hasMessageContaining("Printer#print(String)");
        assertThat(t.explain(null, "Printer#print(String)", null, null)).contains("void print(String s)");
        assertThatThrownBy(() -> t.explain(null, "Gibtsnicht", null, null)).hasMessageContaining("Kein Knoten");
        assertThatThrownBy(() -> t.neighbors(null, "Money", "seitwärts", null, null, null, null))
                .hasMessageContaining("direction");
        assertThatThrownBy(() -> t.neighbors(null, "Money", "in", List.of("ruft"), null, null, null))
                .hasMessageContaining("Unbekannte Relation");
        assertThatThrownBy(() -> t.build("anderswo", false)).hasMessageContaining("nicht freigegeben");
    }

    @Test
    void honoursExcludesAndTestSwitch() throws Exception {
        Path gen = project.resolve("build/generated/Gen.java");
        Files.createDirectories(gen.getParent());
        Files.writeString(gen, "class Gen {}");
        Path test = project.resolve("src/test/java/com/acme/shop/OrderServiceTest.java");
        Files.createDirectories(test.getParent());
        Files.writeString(test, "package com.acme.shop;\nclass OrderServiceTest { void t() { new Money().add(1); } }");
        write("com/acme/build/Tool.java", "package com.acme.build;\npublic class Tool {}\n"); // Paket heißt wie Ausschluss

        CodeGraph withTests = graph();
        assertThat(withTests.node("Gen")).isNull();
        assertThat(withTests.node("com.acme.build.Tool")).isNotNull();
        assertThat(withTests.node("com.acme.shop.OrderServiceTest")).isNotNull();

        Map<String, String> values = new HashMap<>();
        values.put(GraphModule.PROJECTS, project.toString());
        values.put(GraphModule.STORAGE, GraphModule.STORAGE_FILE);
        values.put(GraphModule.INCLUDE_TESTS, "false");
        new GraphTools(new GraphService(ModuleConfig.of(new GraphModule().configSchema(), values))).build(null, true);
        assertThat(GraphStore.load(project).node("com.acme.shop.OrderServiceTest")).isNull();
    }

    @Test
    void handlesDegenerateCommentsBeforeDeclarations() throws Exception {
        write("com/acme/shop/Comments.java", """
                package com.acme.shop;
                /**/
                class EmptyComment { }
                /***/
                class StarsOnly { }
                /** */
                class BlankDoc { }
                /**
                 * @deprecated nur Tags
                 */
                class TagsOnly { }
                /** Richtig. */
                class Proper {
                    /**/ void m() { }
                    /**/ int f;
                }
                """);
        CodeGraph g = graph();
        assertThat(g.node("com.acme.shop.EmptyComment").doc()).isNull();
        assertThat(g.node("com.acme.shop.StarsOnly").doc()).isNull();
        assertThat(g.node("com.acme.shop.BlankDoc").doc()).isNull();
        assertThat(g.node("com.acme.shop.TagsOnly").doc()).isNull();
        assertThat(g.node("com.acme.shop.Proper").doc()).isEqualTo("Richtig.");
        assertThat(g.node("com.acme.shop.Proper#m()")).isNotNull();
    }

    @Test
    void handlesLatin1SourcesAndSyntaxErrors() throws Exception {
        Path file = project.resolve("src/main/java/com/acme/shop/Alt.java");
        Files.write(file, "package com.acme.shop;\n/** Grüße aus Köln. */\nclass Alt { void m() { } }\n"
                .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        write("com/acme/shop/Broken.java", "package com.acme.shop;\nclass Broken { void ok() {} void kaputt( { }\n");
        CodeGraph g = graph();
        assertThat(g.node("com.acme.shop.Alt").doc()).isEqualTo("Grüße aus Köln.");
        assertThat(g.node("com.acme.shop.Broken")).isNotNull();
        assertThat(g.data().files()).filteredOn(f -> f.path().endsWith("Broken.java")).singleElement()
                .extracting(CodeGraph.FileEntry::parseErrors).isEqualTo(Boolean.TRUE);
        assertThat(tools().report(null, 5, null)).contains("Syntax- oder Lesefehlern", "src/main/java/com/acme/shop/Broken.java");
    }

    /** Plattform → Ressourcenname der nativen Bibliothek (liegt in den bonede-Artefakten). */
    @Test
    void resolvesNativeLibraryPerPlatform() {
        assertThat(TreeSitterNatives.resource("tree-sitter", "Mac OS X", "aarch64")).isEqualTo("lib/aarch64-macos-tree-sitter.dylib");
        assertThat(TreeSitterNatives.resource("tree-sitter-java", "Linux", "amd64")).isEqualTo("lib/x86_64-linux-gnu-tree-sitter-java.so");
        // Windows: eigene Kernbibliothek (bonede exportiert dort nur JNI), Grammatik weiter von bonede
        assertThat(TreeSitterNatives.resource("tree-sitter", "Windows 11", "amd64")).isEqualTo("natives/x86_64-windows-tree-sitter.dll");
        assertThat(TreeSitterNatives.resource("tree-sitter-java", "Windows 11", "amd64")).isEqualTo("lib/x86_64-windows-tree-sitter-java.dll");
        assertThatThrownBy(() -> TreeSitterNatives.resource("tree-sitter", "Windows 11", "aarch64"))
                .hasMessageContaining("nicht verfügbar");
        for (String os : List.of("Mac OS X|aarch64", "Mac OS X|x86_64", "Linux|aarch64", "Linux|amd64", "Windows 11|amd64")) {
            String[] p = os.split("\\|");
            for (String lib : List.of("tree-sitter", "tree-sitter-java")) {
                String res = TreeSitterNatives.resource(lib, p[0], p[1]);
                assertThat(getClass().getClassLoader().getResource(res)).as(res).isNotNull();
            }
        }
    }

    /** Die JNI-Bindings (org.treesitter) stürzen bei vollem Heap mit SIGSEGV ab – sie dürfen nicht benutzt werden. */
    @Test
    void graphCodeDoesNotUseJniBindings() throws Exception {
        try (Stream<Path> files = Files.walk(Path.of("src/main/java/systems/grebe/devtools/mcp/modules/graph"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                assertThat(Files.readString(f)).as(f.toString()).doesNotContain("import org.treesitter");
            }
        }
    }

    /** Echter Code: die Quellen dieses Servers samt Plugin-API. Prüft Robustheit und einige bekannte Kanten. */
    @Test
    void graphOfOwnSources() throws Exception {
        Path copy = project.resolve("self");
        for (Path own : List.of(Path.of("src/main/java"), Path.of("../plugin-api/src/main/java"))) {
            Path root = own.toAbsolutePath().normalize();
            try (Stream<Path> files = Files.walk(root)) {
                for (Path p : files.filter(Files::isRegularFile).toList()) {
                    Path target = copy.resolve("src/main/java").resolve(root.relativize(p).toString());
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target);
                }
            }
        }
        Files.writeString(copy.resolve("build.gradle.kts"), "");
        Map<String, String> values = new HashMap<>();
        values.put(GraphModule.PROJECTS, copy.toString());
        values.put(GraphModule.STORAGE, GraphModule.STORAGE_FILE);
        GraphTools t = new GraphTools(new GraphService(ModuleConfig.of(new GraphModule().configSchema(), values)));
        String out = t.build(null, false);
        assertThat(out).startsWith("Graph gebaut").doesNotContain("Lesefehlern");

        CodeGraph g = GraphStore.load(copy);
        String p = "systems.grebe.devtools.mcp.modules.graph.";
        edge(g, p + "GraphTools#build(String,Boolean)", p + "GraphService#build(String,String,boolean,Progress)", Relation.CALLS);
        edge(g, p + "GraphModule", "systems.grebe.devtools.mcp.core.ToolModule", Relation.IMPLEMENTS);
        edge(g, p + "GraphModule#id()", "systems.grebe.devtools.mcp.core.ToolModule#id()", Relation.OVERRIDES);
        assertThat(t.neighbors(null, "systems.grebe.devtools.mcp.core.ToolModule", "in", List.of("implements"), 1, 100, null))
                .contains("GitModule", "BuildModule", "SkillsModule", "GraphModule");
        assertThat(((Number) g.data().stats().get("communities")).intValue()).isGreaterThan(3);
    }
}
