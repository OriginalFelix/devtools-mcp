package systems.grebe.devtools.mcp.modules.web;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import systems.grebe.devtools.mcp.config.Home;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Echtes Modell über den Worker-Prozess. Lädt beim ersten Lauf ~750 MB nach {@code ~/.devtools-mcp/models} und läuft
 * deshalb nur mit {@code WEB_LLM_TEST=1}.
 */
class LocalLlmTest {

    static LocalLlm.Options options(boolean optimized) {
        return new LocalLlm.Options(WebModule.DEFAULT_MODEL, Home.defaultHome().resolve("models"), 0, 2048,
                Duration.ofMinutes(1), optimized);
    }

    @Test
    void summarizesWithinBudget() {
        assumeTrue(System.getenv("WEB_LLM_TEST") != null, "WEB_LLM_TEST nicht gesetzt");
        LocalLlm.Options options = options(true);
        try (LocalLlm llm = new LocalLlm()) {
            llm.warmUp(options, null);
            LocalLlm.Completion c = llm.complete(options, new LocalLlm.Request(
                    "You summarize text in one short English sentence.", "",
                    List.of(new LocalLlm.Segment("Jlama is an LLM inference engine written in Java.", 2),
                            new LocalLlm.Segment("It uses the Vector API for SIMD.", 1)),
                    "Summarize the page.", 64, 0f), Instant.now().plusSeconds(8), null);

            assertThat(c.text()).isNotBlank();
            assertThat(c.segments()).isEqualTo(2);
            assertThat(c.millis()).isLessThan(8_500);
            assertThat(llm.running()).isTrue();

            // langer Auszug, knappes Budget: Worker lässt schwache Stücke weg und hält die Frist
            List<LocalLlm.Segment> many = new ArrayList<>();
            for (int i = 0; i < 80; i++) {
                many.add(new LocalLlm.Segment("Fact " + i + ": the JVM verifies class files before it runs them.", i));
            }
            long start = System.currentTimeMillis();
            LocalLlm.Completion tight = llm.complete(options, new LocalLlm.Request("Be brief.", "", many,
                    "Summarize the page.", 200), Instant.now().plusSeconds(5), null);
            assertThat(tight.segments()).isLessThan(80);
            assertThat(System.currentTimeMillis() - start).isLessThan(7_000);
        }
    }

    /** Parallele Aufrufe warten in der Warteschlange; der KV-Cache hinterlässt keine Dateien im Temp-Verzeichnis. */
    @Test
    void queuesParallelRequestsWithoutLeavingKvFiles() throws Exception {
        assumeTrue(System.getenv("WEB_LLM_TEST") != null, "WEB_LLM_TEST nicht gesetzt");
        long pagesBefore = kvPageFiles();
        LocalLlm.Options options = options(true);
        try (LocalLlm llm = new LocalLlm()) {
            List<String> progress = new java.util.concurrent.CopyOnWriteArrayList<>();
            List<java.util.concurrent.Future<LocalLlm.Completion>> results = new ArrayList<>();
            try (var pool = java.util.concurrent.Executors.newFixedThreadPool(3)) {
                for (int i = 0; i < 3; i++) {
                    int n = i;
                    results.add(pool.submit(() -> llm.complete(options, new LocalLlm.Request("Answer briefly.", "",
                            List.of(new LocalLlm.Segment("Request number " + n + " asks for a short greeting.", 1)),
                            "Say hello.", 16, 0f), null, progress::add)));
                }
                for (var r : results) {
                    assertThat(r.get().text()).isNotBlank();
                }
            }
            assertThat(progress).anyMatch(p -> p.startsWith("Warte auf das lokale LLM") || p.startsWith("Lokales LLM startet"));
        }
        assertThat(kvPageFiles()).isEqualTo(pagesBefore);
    }

    /** KV-Seiten, die Jlama als Dateien anlegt ({@code <uuid>-L0C0.page}) – im Temp-Verzeichnis. */
    private static long kvPageFiles() throws java.io.IOException {
        try (var files = java.nio.file.Files.find(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")), 2,
                (p, a) -> p.getFileName().toString().endsWith(".page"))) {
            return files.count();
        }
    }

    /** {@link FastLlamaModel} rechnet dasselbe wie Jlama: bei Temperatur 0 identische Antwort. */
    @Test
    void optimizedModelAnswersExactlyLikeJlama() {
        assumeTrue(System.getenv("WEB_LLM_TEST") != null, "WEB_LLM_TEST nicht gesetzt");
        List<LocalLlm.Segment> page = new ArrayList<>();
        for (String s : new String[] {
                "The Java virtual machine executes bytecode and verifies classes before running them.",
                "HotSpot compiles frequently used methods to native code with its JIT compilers C1 and C2.",
                "Garbage collectors such as G1, ZGC and Shenandoah reclaim memory of unreachable objects.",
                "Class data sharing reduces startup time by mapping preprocessed classes into memory."}) {
            for (int i = 0; i < 6; i++) {
                page.add(new LocalLlm.Segment(s, 1));
            }
        }
        LocalLlm.Request request = new LocalLlm.Request("You summarize web pages briefly.", "Title: JVM\n\n", page,
                "Summarize the page in two sentences.", 48, 0f);
        try (LocalLlm llm = new LocalLlm()) {
            LocalLlm.Completion original = llm.complete(options(false), request, null, null);
            LocalLlm.Completion optimized = llm.complete(options(true), request, null, null);

            assertThat(optimized.promptTokens()).isEqualTo(original.promptTokens()).isGreaterThan(300);
            assertThat(optimized.text()).isNotBlank().isEqualTo(original.text());
        }
    }
}
