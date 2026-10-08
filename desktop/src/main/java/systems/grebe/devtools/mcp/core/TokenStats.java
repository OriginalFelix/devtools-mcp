package systems.grebe.devtools.mcp.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Zählt je Tool, wie viel Text die Ergebnisse hatten (roh vom Tool) und wie viel davon an das LLM ging. Grundlage für
 * „welche Tools fressen die meisten Tokens“ – in der App und über {@code context_stats}.
 *
 * <p>Tokens sind geschätzt (Zeichen / 4 – für deutschen Text und Code eher etwas zu niedrig); für den Vergleich der
 * Tools untereinander reicht das.
 */
public final class TokenStats {

    /** Summen eines Tools. */
    public record Entry(String tool, long calls, long rawChars, long sentChars, long savedChars) {

        public long sentTokens() {
            return estimate(sentChars);
        }

        public long savedTokens() {
            return estimate(savedChars);
        }

        public long rawTokens() {
            return estimate(rawChars);
        }

        Entry plus(int raw, int sent) {
            return new Entry(tool, calls + 1, rawChars + raw, sentChars + sent, savedChars + Math.max(0, raw - sent));
        }
    }

    private final Map<String, Entry> entries = new HashMap<>();

    /** Geschätzte Tokens für {@code chars} Zeichen. */
    public static int estimate(long chars) {
        return (int) Math.min(Integer.MAX_VALUE, (chars + 3) / 4);
    }

    public synchronized void add(String tool, int rawChars, int sentChars) {
        entries.merge(tool, new Entry(tool, 1, rawChars, sentChars, Math.max(0, rawChars - sentChars)),
                (a, b) -> a.plus(rawChars, sentChars));
    }

    /** Alle Tools, die meisten gesendeten Zeichen zuerst. */
    public synchronized List<Entry> snapshot() {
        List<Entry> out = new ArrayList<>(entries.values());
        out.sort(Comparator.comparingLong(Entry::sentChars).reversed().thenComparing(Entry::tool));
        return out;
    }

    /** Summe über alle Tools. */
    public synchronized Entry total() {
        Entry t = new Entry("gesamt", 0, 0, 0, 0);
        for (Entry e : entries.values()) {
            t = new Entry(t.tool(), t.calls() + e.calls(), t.rawChars() + e.rawChars(), t.sentChars() + e.sentChars(),
                    t.savedChars() + e.savedChars());
        }
        return t;
    }

    public synchronized void clear() {
        entries.clear();
    }
}
