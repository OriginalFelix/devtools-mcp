package systems.grebe.devtools.mcp.modules.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

class ContextTextTest {

    private final ResultStore store = new ResultStore();

    private ResultStore.Stored stored(int lines) {
        return store.put("container_logs", "{}", "s1", IntStream.rangeClosed(1, lines)
                .mapToObj(i -> i % 100 == 0 ? "ERROR bei Schritt " + i : "ok " + i).collect(Collectors.joining("\n")));
    }

    @Test
    void grepShowsMatchesWithLineNumbersAndContext() {
        String out = ContextText.grep(stored(1_000), "error", 1, 10_000);
        assertThat(out).startsWith("[r1 · container_logs · 10 Treffer in 1000 Zeilen]")
                .contains("99- ok 99\n100: ERROR bei Schritt 100\n101- ok 101\n--\n");
    }

    @Test
    void invalidRegexIsSearchedLiterally() {
        ResultStore.Stored s = store.put("t", "{}", null, "a\nfoo(bar\nb");
        assertThat(ContextText.grep(s, "foo(", 0, 1_000)).contains("2: foo(bar");
    }

    @Test
    void grepWithoutHitsSaysSo() {
        assertThat(ContextText.grep(stored(10), "nichts", 0, 1_000)).contains("Keine Zeile passt");
    }

    @Test
    void grepStopsAtBudgetAndTellsWhereToContinue() {
        String out = ContextText.grep(stored(10_000), "error", 0, 200);
        assertThat(out).contains("weitere Treffer");
        assertThat(out.length()).isLessThan(400);
    }

    @Test
    void sliceReturnsRangeAndContinuation() {
        ResultStore.Stored s = stored(1_000);
        assertThat(ContextText.slice(s, 10, 12, 10_000))
                .isEqualTo("[r1 · container_logs · Zeilen 10–12 von 1000]\nok 10\nok 11\nok 12");
        String limited = ContextText.slice(s, 1, 0, 50);
        assertThat(limited).endsWith("… [weiter mit from_line=" + (limited.lines().count() - 1) + "]");
    }

    @Test
    void sliceBeyondEndExplains() {
        assertThat(ContextText.slice(stored(5), 9, 0, 100)).contains("nur 5 Zeilen");
    }

    @Test
    void truncateCutsVeryLongSingleLineByCharacters() {
        String minified = "x".repeat(50_000);
        String out = ContextText.truncate(minified, 2_000, "r9");
        assertThat(out.length()).isLessThanOrEqualTo(2_000);
        assertThat(out).contains("Zeichen ").contains("r9");
    }

    @Test
    void storeAcceptsHandleWithoutPrefix() {
        ResultStore.Stored s = stored(3);
        assertThat(store.get(" 1 ")).contains(s);
        assertThat(store.get("r2")).isEmpty();
    }
}
