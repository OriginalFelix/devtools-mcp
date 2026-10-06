package systems.grebe.devtools.mcp.modules.scripts.assist;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Vorschläge an einer Stelle: ersetzt wird ab {@code from} bis zur Schreibmarke (mit Tab bis zum Wortende). Was der
 * Benutzer seit {@code from} getippt hat, filtert und sortiert die Liste ({@link #rank}) – ohne neue Anfrage.
 *
 * @param from       Beginn des ersetzten Textes (Anfang des angefangenen Wortes)
 * @param items      alle Vorschläge für diese Stelle, noch ungefiltert
 * @param incomplete Liste gekürzt (z.B. Klassennamen) – bei weiterer Eingabe neu anfragen statt nur zu filtern
 * @param multiWord  das Eingegebene darf Leerzeichen enthalten (Gherkin-Schritte)
 */
public record CompletionResult(int from, List<Completion> items, boolean incomplete, boolean multiWord) {

    /** Ein Vorschlag mit seinem Abgleich gegen die Eingabe (für die fette Darstellung der Treffer). */
    public record Ranked(Completion item, CamelMatcher.Match match) {
    }

    private static final Comparator<Ranked> ORDER = Comparator
            .comparingInt((Ranked r) -> r.match().quality())
            .thenComparing(Comparator.comparingInt((Ranked r) -> r.item().priority()).reversed())
            .thenComparing(Comparator.comparingInt((Ranked r) -> r.match().score()).reversed())
            .thenComparingInt(r -> r.item().label().length())
            .thenComparing(r -> r.item().label(), String.CASE_INSENSITIVE_ORDER);

    public static CompletionResult none(int caret) {
        return new CompletionResult(caret, List.of(), false, false);
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    /**
     * Die zur Eingabe passenden Vorschläge, beste zuerst: Präfix vor CamelHumps vor Treffern mitten im Namen, dann
     * Vorrang, dann Feinabstufung, kürzere Namen zuerst.
     */
    public List<Ranked> rank(String typed) {
        return rank(items, typed);
    }

    static List<Ranked> rank(List<Completion> items, String typed) {
        List<Ranked> out = new ArrayList<>();
        for (Completion c : items) {
            CamelMatcher.Match m = CamelMatcher.match(typed, c.label());
            if (m != null) {
                out.add(new Ranked(c, m));
            }
        }
        out.sort(ORDER);
        return out;
    }
}
