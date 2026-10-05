package systems.grebe.devtools.mcp.modules.web;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Vorauswahl der Sätze und Bereinigung typischer Fehlausgaben eines 1B-Modells (Beispiele aus echten Läufen). */
class PageSummarizerTest {

    static final String LOOP = """
            The JEP is a proposal for a new API feature.

            The proposed API feature is designed to be used in conjunction with the existing vector API, which is a new type of vector.

            The proposed API feature is designed to be used in conjunction with the existing vector API, which is a new type of vector.

            The proposed API feature is designed to be used in conjunction with the existing""";

    static final String DOC = """
            # Vector API

            The Vector API expresses vector computations that compile to optimal vector instructions at runtime.
            Menu Home About Contact
            ## History

            The Vector API was first proposed in JEP 338 and integrated into JDK 16 as an incubating API.
            Further rounds of incubation followed in every release up to JDK 24.

            ## Changes

            Float16 operations are now auto-vectorized on supporting x64 CPUs in JDK 25.
            VectorShuffle now supports access to and from MemorySegment.
            ```
            var v = FloatVector.fromArray(SPECIES, a, 0);
            ```
            """;

    @Test
    void detectsAndCutsLoops() {
        assertThat(Repetition.loops(LOOP.substring(0, LOOP.indexOf("The proposed")))).isFalse();
        assertThat(Repetition.loops(LOOP.substring(0, LOOP.lastIndexOf("vector.") + "vector.\n".length()))).isTrue();
        assertThat(Repetition.cut(LOOP)).isEqualTo("""
                The JEP is a proposal for a new API feature.

                The proposed API feature is designed to be used in conjunction with the existing vector API, which is a new type of vector.""");
    }

    @Test
    void cutsLoopsWithoutLineBreaks() {
        String s = "Intro. " + "Java runs bytecode on many platforms and the JVM verifies it. ".repeat(4);
        assertThat(Repetition.loops(s)).isTrue();
        assertThat(Repetition.cut(s)).isEqualTo("Intro. Java runs bytecode on many platforms and the JVM verifies it.");
    }

    @Test
    void extractsSentencesWithHeadingsSkippingCodeAndFragments() {
        List<Extractor.Sentence> all = Extractor.score(DOC, null);
        assertThat(all).extracting(Extractor.Sentence::text).containsExactly(
                "The Vector API expresses vector computations that compile to optimal vector instructions at runtime.",
                "The Vector API was first proposed in JEP 338 and integrated into JDK 16 as an incubating API.",
                "Further rounds of incubation followed in every release up to JDK 24.",
                "Float16 operations are now auto-vectorized on supporting x64 CPUs in JDK 25.",
                "VectorShuffle now supports access to and from MemorySegment.");
        assertThat(all.get(1).heading()).isEqualTo("History");
    }

    @Test
    void focusPicksMatchingSentences() {
        List<Extractor.Sentence> scored = Extractor.score(DOC, "Which JDK version auto-vectorizes Float16?");
        assertThat(scored.stream().max((a, b) -> Double.compare(a.score(), b.score())).orElseThrow().text())
                .startsWith("Float16 operations");
        assertThat(Extractor.select(scored, 200)).extracting(Extractor.Sentence::text)
                .anyMatch(t -> t.startsWith("Float16 operations"))
                .noneMatch(t -> t.startsWith("VectorShuffle"));
        assertThat(Extractor.select(Extractor.score(DOC, "Kubernetes"), 1000)).isEmpty();
    }

    @Test
    void selectionRespectsBudgetAndKeepsTextOrder() {
        List<Extractor.Sentence> chosen = Extractor.select(Extractor.score(DOC, null), 250);
        assertThat(chosen.stream().mapToInt(s -> s.text().length() + 1).sum()).isLessThanOrEqualTo(251);
        assertThat(chosen).isSortedAccordingTo((a, b) -> Integer.compare(a.index(), b.index()));
    }

    @Test
    void cleansAnswer() {
        assertThat(PageSummarizer.clean("Here is a summary of the page:\n\nThe page describes JEP 508."))
                .isEqualTo("The page describes JEP 508.");
        assertThat(PageSummarizer.clean("<page>\nThe page describes JEP 508.\n</page>")).isEqualTo("The page describes JEP 508.");
        assertThat(PageSummarizer.clean("Here the JVM verifies bytecode.")).isEqualTo("Here the JVM verifies bytecode.");
        assertThat(PageSummarizer.clean("Summary:\nThe JEP proposes a vector API.")).isEqualTo("The JEP proposes a vector API.");
        assertThat(PageSummarizer.clean("""
                You can set the Java version using the `java` block.
                The block supports more options.

                Answer: You can set the Java version using the `java` block."""))
                .isEqualTo("You can set the Java version using the `java` block.\nThe block supports more options.");
        assertThat(PageSummarizer.clean("Answer: JDK 25.")).isEqualTo("JDK 25.");
    }

    @Test
    void completesSentencesWhenTimeRunsOut() {
        assertThat(LlmWorker.completeSentences("The JVM runs bytecode. It verifies cla")).isEqualTo("The JVM runs bytecode.");
        assertThat(LlmWorker.completeSentences("The JVM runs bytecode.")).isEqualTo("The JVM runs bytecode.");
        assertThat(LlmWorker.completeSentences("Short frag")).isEqualTo("Short frag …");
    }
}
