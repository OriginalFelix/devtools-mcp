package systems.grebe.devtools.mcp.modules.graph;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import javax.tools.ToolProvider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Confidence;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Edge;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Kind;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Relation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Dynamische Graphen: Projekt mit Klassenpfad (JDK + JAR) und Bibliotheken aus dem Bytecode, nur im Speicher. */
class DynamicGraphTest {

    @TempDir
    Path project;
    @TempDir
    Path work;

    private Path jar;

    @BeforeEach
    void setUp() throws Exception {
        DynamicGraphs.clear();
        jar = compileLibrary();
        Files.writeString(project.resolve("build.gradle"), "");
        Files.createDirectories(project.resolve("lib"));
        Files.copy(jar, project.resolve("lib/handlers-1.0.jar"));
        write("com/app/MyHandler.java", """
                package com.app;

                import org.lib.*;
                import java.util.concurrent.*;

                class MyHandler extends AbstractHandler implements Callable<String> {
                    @Override
                    protected void doHandle(String msg) {
                        log(msg);
                    }

                    void log(String m) {
                    }

                    public String call() {
                        return "";
                    }
                }
                """);
        write("com/app/Main.java", """
                package com.app;

                import org.lib.Dispatcher;

                class Main {
                    void run() {
                        Dispatcher.create().add(new MyHandler()).fire("x");
                    }
                }
                """);
    }

    @AfterEach
    void tearDown() {
        DynamicGraphs.clear();
    }

    private void write(String rel, String content) throws Exception {
        Path f = project.resolve("src/main/java").resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, content);
    }

    /** Kleine Bibliothek: Interface, abstrakte Basis mit Template-Methode, Dispatcher mit Lambda. */
    private Path compileLibrary() throws Exception {
        Path src = work.resolve("src/org/lib");
        Files.createDirectories(src);
        Files.writeString(src.resolve("Handler.java"), "package org.lib;\npublic interface Handler { void handle(String msg); }\n");
        Files.writeString(src.resolve("AbstractHandler.java"), """
                package org.lib;
                public abstract class AbstractHandler implements Handler {
                    public void handle(String msg) {
                        before();
                        doHandle(msg);
                    }
                    protected void before() {
                    }
                    protected abstract void doHandle(String msg);
                }
                """);
        Files.writeString(src.resolve("Dispatcher.java"), """
                package org.lib;
                import java.util.ArrayList;
                import java.util.List;
                public class Dispatcher {
                    private final List<Handler> handlers = new ArrayList<>();
                    public static Dispatcher create() {
                        return new Dispatcher();
                    }
                    public Dispatcher add(Handler h) {
                        handlers.add(h);
                        return this;
                    }
                    public void fire(String m) {
                        handlers.forEach(h -> h.handle(m));
                    }
                }
                """);
        Path classes = work.resolve("classes");
        Files.createDirectories(classes);
        List<String> args = new java.util.ArrayList<>(List.of("-g", "-d", classes.toString()));
        try (Stream<Path> files = Files.list(src)) {
            files.forEach(f -> args.add(f.toString()));
        }
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new))).isZero();
        Path out = work.resolve("handlers-1.0.jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(out));
             Stream<Path> all = Files.walk(classes)) {
            for (Path p : all.filter(Files::isRegularFile).toList()) {
                jos.putNextEntry(new JarEntry(classes.relativize(p).toString().replace('\\', '/')));
                Files.copy(p, jos);
                jos.closeEntry();
            }
        }
        return out;
    }

    private GraphTools tools() {
        Map<String, String> values = new HashMap<>();
        values.put(GraphModule.PROJECTS, project.toString());
        values.put(GraphModule.STORAGE, GraphModule.STORAGE_FILE);
        values.put(GraphModule.LIBRARY_PATHS, work.resolve("kein-cache").toString());
        return new GraphTools(new GraphService(ModuleConfig.of(new GraphModule().configSchema(), values)));
    }

    private static Edge edge(CodeGraph g, String from, String to, Relation rel) {
        return g.outgoing(from).stream().filter(e -> e.to().equals(to) && e.rel() == rel).findFirst()
                .orElseThrow(() -> new AssertionError("Kante fehlt: " + from + " --" + rel + "--> " + to
                        + "\nvorhanden: " + g.outgoing(from)));
    }

    private static CodeGraph graph(String id) {
        return ((MemoryGraphReader) DynamicGraphs.get(id).reader()).graph();
    }

    private static String id(String output) {
        return output.substring(output.indexOf("dyn:"), output.indexOf(' ', output.indexOf("dyn:")));
    }

    @Test
    void storedGraphResolvesJdkNamesButKeepsLibrariesOut() {
        GraphTools t = tools();
        t.build(null, false);
        CodeGraph g = GraphStore.load(project);
        // Stern-Import aus dem JDK jetzt aufgelöst (vorher nur der einfache Name, INFERRED)
        assertThat(edge(g, "com.app.MyHandler", "java.util.concurrent.Callable", Relation.IMPLEMENTS).conf())
                .isEqualTo(Confidence.EXTRACTED);
        // ohne Klassenpfad: Bibliothek unbekannt, keine Aufrufe hinein
        assertThat(g.outgoing("com.app.Main#run()")).noneMatch(e -> e.to().startsWith("org.lib."));
    }

    @Test
    void projectWithLibrariesResolvesCallsOverridesAndDispatch() {
        GraphTools t = tools();
        String out = t.dynamic(null, List.of("org.lib.*"), null, null, null);
        assertThat(out).contains("project='dyn:");
        // nur Bibliothek – dann mit Projekt
        out = t.dynamic(project.toString(), List.of("org.lib"), null, 5, null);
        assertThat(out).contains("Dynamischer Graph dyn:", "Projekt", "handlers-1.0.jar");
        CodeGraph g = graph(id(out));

        String run = "com.app.Main#run()";
        assertThat(edge(g, run, "org.lib.Dispatcher#create()", Relation.CALLS).conf()).isEqualTo(Confidence.EXTRACTED);
        // Kette über Rückgabetypen der Bibliothek
        edge(g, run, "org.lib.Dispatcher#add(Handler)", Relation.CALLS);
        edge(g, run, "org.lib.Dispatcher#fire(String)", Relation.CALLS);
        edge(g, run, "com.app.MyHandler", Relation.INSTANTIATES);
        // Vererbung und Überschreibung in die Bibliothek
        assertThat(edge(g, "com.app.MyHandler", "org.lib.AbstractHandler", Relation.EXTENDS).conf())
                .isEqualTo(Confidence.EXTRACTED);
        edge(g, "com.app.MyHandler#doHandle(String)", "org.lib.AbstractHandler#doHandle(String)", Relation.OVERRIDES);
        assertThat(g.node("org.lib.AbstractHandler").kind()).isEqualTo(Kind.CLASS);
        assertThat(g.node("org.lib.Handler").kind()).isEqualTo(Kind.INTERFACE);
        assertThat(g.node("org.lib.AbstractHandler").doc()).contains("handlers-1.0.jar");
        // Bytecode der Bibliothek: Template-Methode, Lambda zählt zu fire
        assertThat(edge(g, "org.lib.AbstractHandler#handle(String)", "org.lib.AbstractHandler#doHandle(String)",
                Relation.CALLS).conf()).isEqualTo(Confidence.EXTRACTED);
        edge(g, "org.lib.Dispatcher#fire(String)", "org.lib.Handler#handle(String)", Relation.CALLS);
        edge(g, "org.lib.AbstractHandler#handle(String)", "org.lib.Handler#handle(String)", Relation.OVERRIDES);
        // Aufruf über die Bibliothek bis in die eigene Implementierung
        assertThat(edge(g, "org.lib.AbstractHandler#handle(String)", "com.app.MyHandler#doHandle(String)",
                Relation.CALLS).conf()).isEqualTo(Confidence.INFERRED);
        // JDK bleibt draußen
        assertThat(g.nodes()).noneMatch(n -> n.id().startsWith("java.util.ArrayList"));

        // die übrigen Tools arbeiten mit der ID statt des Projekts
        String id = id(out);
        assertThat(t.path(id, "Main#run", "MyHandler#doHandle", true, List.of("calls"), null, null))
                .contains("Dispatcher#fire(String)", "MyHandler#doHandle(String)");
        assertThat(t.find(id, "Dispatcher", null, null, null)).contains("org.lib.Dispatcher");
        assertThatThrownBy(() -> t.read(id, List.of("org.lib.Dispatcher"), null, null, null, null))
                .hasMessageContaining("decompile_class");
        assertThat(t.read(id, List.of("MyHandler#log"), null, null, null, null)).contains("void log(String m)");
    }

    @Test
    void libraryOnlyGraphFromJarAndLifecycle() {
        GraphTools t = tools();
        String out = t.dynamic(null, List.of(jar.toString()), null, null, null);
        String id = id(out);
        CodeGraph g = graph(id);
        assertThat(g.node("org.lib.Dispatcher#add(Handler)").kind()).isEqualTo(Kind.METHOD);
        edge(g, "org.lib.Dispatcher#create()", "org.lib.Dispatcher", Relation.INSTANTIATES);
        edge(g, "org.lib.AbstractHandler", "org.lib.Handler", Relation.IMPLEMENTS);
        assertThat(t.dynamic(null, null, null, null, null)).contains(id, jar.toString());
        assertThat(t.dynamic(null, null, null, null, id)).contains("entfernt");
        assertThatThrownBy(() -> t.find(id, "Dispatcher", null, null, null)).hasMessageContaining("existiert nicht");
    }
}
