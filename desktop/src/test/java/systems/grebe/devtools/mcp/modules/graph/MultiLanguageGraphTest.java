package systems.grebe.devtools.mcp.modules.graph;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Confidence;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Edge;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Kind;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Relation;

import static org.assertj.core.api.Assertions.assertThat;

/** Nicht nur Java: Typen, Funktionen, Vererbung, Imports und Aufrufe der übrigen tree-sitter-Sprachen. */
class MultiLanguageGraphTest {

    @TempDir
    Path project;

    private CodeGraph g;

    @BeforeEach
    void build() throws Exception {
        write("web/src/user.service.ts", """
                import { formatName } from './util';

                /** Lädt Benutzer. */
                export class UserService extends BaseService implements Loader {
                    load(id: string): User {
                        this.check(id);
                        return formatName(id);
                    }
                    check(id: string) {}
                    handler = (e: Event) => this.load(e.id);
                }
                interface Loader { load(id: string): User }
                class BaseService {}
                """);
        write("web/src/util.ts", """
                export function formatName(x: string) { return x; }
                export const shout = (x: string) => formatName(x).toUpperCase();
                """);
        write("app/models.py", """
                from .db import connect

                class Order(Base):
                    \"\"\"Ein Auftrag. Mit Details.\"\"\"
                    def __init__(self, amount):
                        self.amount = amount

                    def save(self):
                        connect().commit()
                        self.validate()

                    def validate(self):
                        pass

                def create(amount):
                    return Order(amount)
                """);
        write("app/db.py", "def connect():\n    return None\n\nclass Base:\n    pass\n");
        write("svc/server.go", """
                package svc

                type Server struct { port int }

                func (s *Server) Start() { helper(s.port) }
                """);
        write("svc/helper.go", "package svc\n\nfunc helper(p int) int { return p }\n");
        write("core/src/lib.rs", """
                trait Shape { fn area(&self) -> f64; }
                struct Circle { r: f64 }
                impl Shape for Circle {
                    fn area(&self) -> f64 { square(self.r) }
                }
                fn square(x: f64) -> f64 { x * x }
                """);
        write("native/math.c", "#include \"math.h\"\nstatic int twice(int x) { return x * 2; }\nint quad(int x) { return twice(twice(x)); }\n");
        write("native/math.h", "int quad(int x);\n");
        write("Billing/Invoice.cs", """
                namespace Billing {
                    public class Invoice : IPrintable {
                        public decimal Total { get; set; }
                        public void Print() { Format(); }
                        private string Format() { return ""; }
                    }
                    public interface IPrintable { void Print(); }
                }
                """);
        write("src/main/kotlin/com/acme/Greeter.kt", """
                package com.acme

                class Greeter(val name: String) : Base() {
                    fun greet(): String = hello(name)
                }
                open class Base
                fun hello(n: String): String = "Hallo " + n
                """);
        write("src/main/java/com/acme/App.java", """
                package com.acme;

                class App {
                    void run() {
                        new Greeter("x").greet();
                        GreeterKt.hello("y");
                    }
                }
                """);
        write("lib/Cart.php", "<?php\nnamespace Shop;\nclass Cart extends Model {\n  public function add($x) { return $this->sum(); }\n  public function sum() { return 0; }\n}\n");
        write("lib/cart.rb", "class Cart < Base\n  def add(x)\n    self.total\n  end\n\n  def total\n    0\n  end\nend\n");
        write("bin/deploy.sh", "build() { echo build; }\nbuild\n");
        write("README.md", "# Projekt\n");

        GraphBuilder b = new GraphBuilder(project, List.of(), true, 10_000);
        List<GraphBuilder.Source> sources = new ArrayList<>();
        for (Path p : b.scan()) {
            sources.add(b.read(p));
        }
        g = new CodeGraph(b.build(sources, "p"));
    }

    private void write(String rel, String content) throws Exception {
        Path f = project.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, content);
    }

    private Edge edge(String from, String to, Relation rel) {
        return g.outgoing(from).stream().filter(e -> e.to().equals(to) && e.rel() == rel).findFirst()
                .orElseThrow(() -> new AssertionError("Kante fehlt: " + from + " --" + rel + "--> " + to
                        + "\nvorhanden: " + g.outgoing(from)));
    }

    @Test
    void typeScriptClassesFunctionsImportsAndCalls() {
        assertThat(g.node("web.src.user_service.UserService").kind()).isEqualTo(Kind.CLASS);
        assertThat(g.node("web.src.user_service.UserService").doc()).isEqualTo("Lädt Benutzer.");
        assertThat(g.node("web.src.user_service.Loader").kind()).isEqualTo(Kind.INTERFACE);
        assertThat(g.node("web.src.user_service.UserService#load(string)").signature())
                .isEqualTo("load(id: string): User");
        assertThat(g.node("web.src.util#shout(string)").kind()).isEqualTo(Kind.METHOD);
        edge("web.src.user_service.UserService", "web.src.user_service.BaseService", Relation.EXTENDS);
        edge("web.src.user_service.UserService", "web.src.user_service.Loader", Relation.IMPLEMENTS);
        edge("file:web/src/user.service.ts", "file:web/src/util.ts", Relation.IMPORTS);
        assertThat(edge("web.src.user_service.UserService#load(string)",
                "web.src.user_service.UserService#check(string)", Relation.CALLS).conf()).isEqualTo(Confidence.EXTRACTED);
        assertThat(edge("web.src.user_service.UserService#load(string)", "web.src.util#formatName(string)",
                Relation.CALLS).conf()).isEqualTo(Confidence.INFERRED);
        edge("web.src.user_service.UserService#handler(Event)", "web.src.user_service.UserService#load(string)",
                Relation.CALLS);
        edge("web.src.util#shout(string)", "web.src.util#formatName(string)", Relation.CALLS);
        edge("file:web/src/user.service.ts", "web.src.user_service.UserService", Relation.CONTAINS);
    }

    @Test
    void pythonDocstringsSelfCallsConstructorsAndRelativeImports() {
        assertThat(g.node("app.models.Order").doc()).isEqualTo("Ein Auftrag.");
        assertThat(g.node("app.models.Order#<init>(amount)").kind()).isEqualTo(Kind.CONSTRUCTOR);
        edge("app.models.Order#save()", "app.models.Order#validate()", Relation.CALLS);
        edge("app.models.Order#save()", "app.db#connect()", Relation.CALLS);
        edge("app.models#create(amount)", "app.models.Order", Relation.INSTANTIATES);
        edge("app.models#create(amount)", "app.models.Order#<init>(amount)", Relation.CALLS);
        edge("app.models.Order", "app.db.Base", Relation.EXTENDS);
        edge("file:app/models.py", "file:app/db.py", Relation.IMPORTS);
    }

    @Test
    void goRustCAndCSharp() {
        // Go: Methode gehört dem Struct, Paket = Verzeichnis über Dateien hinweg
        assertThat(g.node("svc.Server#Start()").kind()).isEqualTo(Kind.METHOD);
        edge("svc.Server", "svc.Server#Start()", Relation.CONTAINS);
        assertThat(edge("svc.Server#Start()", "svc#helper(int)", Relation.CALLS).conf()).isEqualTo(Confidence.EXTRACTED);
        // Rust: impl Trait for Typ
        edge("core.src.lib.Circle", "core.src.lib.Shape", Relation.IMPLEMENTS);
        edge("core.src.lib.Circle#area()", "core.src.lib#square(f64)", Relation.CALLS);
        // C: static-Funktion derselben Datei, Include
        edge("native.math#quad(int)", "native.math#twice(int)", Relation.CALLS);
        edge("file:native/math.c", "file:native/math.h", Relation.IMPORTS);
        // C#: Namespace, implizites this, Property als Feld
        edge("Billing.Invoice#Print()", "Billing.Invoice#Format()", Relation.CALLS);
        edge("Billing.Invoice", "Billing.IPrintable", Relation.IMPLEMENTS);
        assertThat(g.node("Billing.Invoice#Total").kind()).isEqualTo(Kind.FIELD);
    }

    @Test
    void kotlinIsVisibleFromJavaThroughItsJvmNames() {
        assertThat(g.node("com.acme.GreeterKt").kind()).isEqualTo(Kind.CLASS);
        edge("com.acme.Greeter", "com.acme.Base", Relation.EXTENDS);
        edge("com.acme.Greeter#greet()", "com.acme.GreeterKt#hello(String)", Relation.CALLS);
        edge("com.acme.App#run()", "com.acme.Greeter", Relation.INSTANTIATES);
        edge("com.acme.App#run()", "com.acme.Greeter#greet()", Relation.CALLS);
        edge("com.acme.App#run()", "com.acme.GreeterKt#hello(String)", Relation.CALLS);
    }

    @Test
    void phpRubyAndBash() {
        edge("Shop.Cart#add(x)", "Shop.Cart#sum()", Relation.CALLS);
        edge("lib.cart.Cart#add(x)", "lib.cart.Cart#total()", Relation.CALLS);
        edge("file:bin/deploy.sh", "bin.deploy#build()", Relation.CALLS);
        assertThat(g.node("file:README.md").kind()).isEqualTo(Kind.FILE);
    }
}
