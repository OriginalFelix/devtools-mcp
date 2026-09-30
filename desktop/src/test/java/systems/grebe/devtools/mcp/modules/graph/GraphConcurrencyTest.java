package systems.grebe.devtools.mcp.modules.graph;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Der {@link JavaResolver} wird im {@link GraphBuilder} von mehreren Worker-Threads gleichzeitig benutzt; seine Caches
 * müssen das aushalten. Früher: {@code HashMap} → {@code ClassCastException HashMap$Node → TreeNode} bzw. stille
 * Datenfehler, nur bei großen Projekten (Treeify ab 8 Kollisionen je Bucket).
 */
class GraphConcurrencyTest {

    @TempDir
    Path project;

    @Test
    void resolverCachesSurviveConcurrentReferencePass() throws Exception {
        // Viele Typen, jede Datei referenziert viele andere und hat eine Vererbungskette -> viele Cache-Schreibzugriffe.
        int types = 400;
        List<JavaExtractor.FileDecl> decls = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < types; i++) {
            StringBuilder sb = new StringBuilder("package p.q;\n");
            sb.append("class T").append(i);
            if (i > 0) {
                sb.append(" extends T").append(i - 1);
            }
            sb.append(" {\n");
            for (int k = 1; k <= 25; k++) {
                int o = (i * 7 + k * 13) % types;
                sb.append("  T").append(o).append(" f").append(k).append(";\n");
                sb.append("  T").append(o).append(" m").append(k).append("(T").append((o + 1) % types)
                        .append(" a) { return new T").append(o).append("(); }\n");
            }
            sb.append("  Unknown").append(i).append(" x;\n}\n");
            String text = sb.toString();
            String path = "src/main/java/p/q/T" + i + ".java";
            texts.add(text);
            decls.add(JavaExtractor.declarations(path, text));
        }
        Set<String> expected = edges(decls, texts, new JavaResolver(decls), 1);

        for (int round = 0; round < 20; round++) {
            Set<String> parallel = edges(decls, texts, new JavaResolver(decls), 8);
            assertThat(parallel).as("Runde " + round).isEqualTo(expected);
        }
        assertThat(expected).anyMatch(e -> e.contains("EXTENDS"));
    }

    @Test
    void builderWithManyThreadsMatchesSingleThreaded() throws Exception {
        Files.writeString(project.resolve("build.gradle"), "");
        Path src = Files.createDirectories(project.resolve("src/main/java/p"));
        for (int i = 0; i < 300; i++) {
            Files.writeString(src.resolve("C" + i + ".java"), "package p;\ninterface I" + i + " { }\n"
                    + "class C" + i + (i > 0 ? " extends C" + (i - 1) : "") + " implements I" + i + " {\n"
                    + "  C" + ((i + 3) % 300) + " a; C" + ((i + 5) % 300) + " b() { return new C" + ((i + 5) % 300)
                    + "(); }\n}\n");
        }
        Set<String> one = build(1);
        for (int round = 0; round < 5; round++) {
            assertThat(build(8)).as("Runde " + round).isEqualTo(one);
        }
    }

    private Set<String> build(int threads) throws Exception {
        GraphBuilder b = new GraphBuilder(project, List.of(), true, 10_000).threads(threads);
        List<GraphBuilder.Source> sources = new ArrayList<>();
        for (Path p : b.scan()) {
            sources.add(b.read(p));
        }
        Set<String> out = new TreeSet<>();
        for (CodeGraph.Edge e : b.build(sources, "p").edges()) {
            out.add(e.from() + " " + e.rel() + " " + e.to() + " " + e.conf());
        }
        return out;
    }

    /** Referenzdurchlauf wie im GraphBuilder, alle Threads starten gleichzeitig. */
    private static Set<String> edges(List<JavaExtractor.FileDecl> decls, List<String> texts, JavaResolver r,
                                     int threads) throws Exception {
        Set<String> out = new TreeSet<>();
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<List<JavaExtractor.RawEdge>>> fs = new ArrayList<>();
            for (int i = 0; i < decls.size(); i++) {
                int idx = i;
                fs.add(pool.submit(() -> {
                    go.await();
                    return JavaExtractor.references(decls.get(idx), texts.get(idx), r);
                }));
            }
            go.countDown();
            for (Future<List<JavaExtractor.RawEdge>> f : fs) {
                for (JavaExtractor.RawEdge e : f.get()) {
                    out.add(e.toString());
                }
            }
        }
        return out;
    }
}
