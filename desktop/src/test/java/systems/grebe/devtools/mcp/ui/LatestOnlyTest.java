package systems.grebe.devtools.mcp.ui;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Späte Ergebnisse überholter Abfragen überschreiben die aktuelle Auswahl nicht. */
class LatestOnlyTest {

    private final List<Runnable> work = new ArrayList<>();
    private final List<String> shown = new ArrayList<>();
    private final LatestOnly latest = new LatestOnly(work::add, Runnable::run);

    private void submit(String result) {
        latest.submit(() -> result, shown::add, e -> shown.add("Fehler: " + e.getMessage()));
    }

    @Test
    void aLateResultOfASupersededRequestIsDropped() {
        submit("A");
        submit("B");
        work.get(1).run(); // B antwortet zuerst
        work.get(0).run(); // A kommt zu spät und darf B nicht überschreiben
        assertThat(shown).containsExactly("B");
    }

    @Test
    void theLatestRequestIsShownEvenIfItIsTheOnlyOne() {
        submit("A");
        work.getFirst().run();
        assertThat(shown).containsExactly("A");
    }

    @Test
    void errorsOfSupersededRequestsAreDroppedToo() {
        latest.<String>submit(() -> {
            throw new IllegalStateException("alt kaputt");
        }, shown::add, e -> shown.add("Fehler: " + e.getMessage()));
        submit("B");
        work.get(0).run();
        work.get(1).run();
        assertThat(shown).containsExactly("B");
    }

    @Test
    void errorOfTheCurrentRequestIsReported() {
        latest.<String>submit(() -> {
            throw new IllegalStateException("kaputt");
        }, shown::add, e -> shown.add("Fehler: " + e.getMessage()));
        work.getFirst().run();
        assertThat(shown).containsExactly("Fehler: kaputt");
    }

    @Test
    void cancelDropsTheRunningRequest() {
        submit("A");
        latest.cancel(); // Auswahl aufgehoben
        work.getFirst().run();
        assertThat(shown).isEmpty();
    }
}
