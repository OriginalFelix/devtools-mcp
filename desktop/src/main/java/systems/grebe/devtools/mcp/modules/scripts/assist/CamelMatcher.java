package systems.grebe.devtools.mcp.modules.scripts.assist;

import java.util.Arrays;

/**
 * Abgleich der Eingabe mit Namen wie in IntelliJ („CamelHumps“ und Treffer an Wortanfängen mitten im Namen):
 * {@code gSN} findet {@code getScriptName}, {@code hcl} {@code HttpClient}, {@code str} auch {@code toString}.
 * Groß-/Kleinschreibung zählt nur für die Reihenfolge; ein Großbuchstabe in der Eingabe verlangt aber einen
 * Wortanfang. Ein Leerzeichen in der Eingabe verlangt einen neuen Wortanfang („Tool auf“ findet „ich rufe das Tool …
 * auf“). Liefert die getroffenen Bereiche für die fette Darstellung in der Liste.
 */
public final class CamelMatcher {

    /** Güte: der Name beginnt genau so (auch in Groß-/Kleinschreibung). */
    public static final int EXACT_PREFIX = 0;
    /** Güte: der Name beginnt so, Groß-/Kleinschreibung weicht ab. */
    public static final int PREFIX = 1;
    /** Güte: Treffer ab dem ersten Zeichen, aber mit Sprüngen über Wortanfänge ({@code gSN}). */
    public static final int START = 2;
    /** Güte: Treffer beginnt an einem späteren Wortanfang ({@code str} in {@code toString}). */
    public static final int MIDDLE = 3;

    /**
     * Ergebnis eines Abgleichs.
     *
     * @param quality {@link #EXACT_PREFIX} … {@link #MIDDLE} – kleiner ist besser
     * @param score   Feinabstufung innerhalb einer Güte (größer ist besser)
     * @param ranges  getroffene Bereiche paarweise {@code [von, bis)}
     */
    public record Match(int quality, int score, int[] ranges) {
    }

    private static final Match EMPTY = new Match(EXACT_PREFIX, 0, new int[0]);
    private static final int NONE = Integer.MIN_VALUE / 4;

    private CamelMatcher() {
    }

    /** Abgleich oder {@code null}, wenn der Name nicht passt; eine leere Eingabe passt immer. */
    public static Match match(String pattern, String name) {
        int m = pattern.length();
        if (m == 0) {
            return EMPTY;
        }
        if (name.regionMatches(true, 0, pattern, 0, m)) {
            boolean exact = name.startsWith(pattern);
            return new Match(exact ? EXACT_PREFIX : PREFIX, m == name.length() ? 1 : 0, new int[] {0, m});
        }
        if (!subsequence(pattern, name)) {
            return null;
        }
        return new Search(pattern, name).run();
    }

    /** Schneller Vorfilter: alle Zeichen der Eingabe (ohne Leerzeichen) kommen in dieser Reihenfolge vor. */
    private static boolean subsequence(String pattern, String name) {
        int j = 0;
        int n = name.length();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == ' ') {
                continue;
            }
            while (j < n && !same(name.charAt(j), c)) {
                j++;
            }
            if (j == n) {
                return false;
            }
            j++;
        }
        return true;
    }

    static boolean same(char a, char b) {
        return a == b || Character.toLowerCase(a) == Character.toLowerCase(b);
    }

    /** Wortanfang: erstes Zeichen, nach einem Trenner, Großbuchstabe nach Kleinbuchstabe, Ziffer nach Buchstabe. */
    static boolean wordStart(String s, int i) {
        if (i == 0) {
            return true;
        }
        char c = s.charAt(i);
        char prev = s.charAt(i - 1);
        if (!Character.isLetterOrDigit(c)) {
            return false;
        }
        if (!Character.isLetterOrDigit(prev)) {
            return true;
        }
        if (Character.isUpperCase(c)) {
            // camelCase oder HTTPClient → das C
            return Character.isLowerCase(prev) || i + 1 < s.length() && Character.isLowerCase(s.charAt(i + 1));
        }
        return Character.isDigit(c) && !Character.isDigit(prev);
    }

    /**
     * Beste Zuordnung per Tiefensuche mit Merktabelle: Zustand (Position in der Eingabe, Position im Namen, ob das
     * vorige Zeichen direkt davor getroffen wurde). Ein Zeichen trifft entweder direkt anschließend oder springt zu
     * einem späteren Wortanfang.
     */
    private static final class Search {
        private final String p;
        private final String s;
        private final int m;
        private final int n;
        private final int[] memo;
        private final int[] choice;

        Search(String pattern, String name) {
            p = pattern;
            s = name;
            m = pattern.length();
            n = name.length();
            memo = new int[m * (n + 1) * 2];
            choice = new int[memo.length];
            Arrays.fill(memo, Integer.MAX_VALUE);
        }

        Match run() {
            int best = best(0, 0, 0);
            if (best <= NONE / 2) {
                return null;
            }
            int[] hit = new int[n];
            int count = 0;
            int pi = 0;
            int ni = 0;
            int consecutive = 0;
            while (pi < m) {
                int j = choice[index(pi, ni, consecutive)];
                if (j < 0) { // Leerzeichen der Eingabe
                    pi++;
                    consecutive = 0;
                    continue;
                }
                hit[count++] = j;
                pi++;
                ni = j + 1;
                consecutive = 1;
            }
            int[] ranges = new int[count * 2];
            int r = 0;
            for (int k = 0; k < count; k++) {
                if (r > 0 && ranges[r - 1] == hit[k]) {
                    ranges[r - 1]++;
                } else {
                    ranges[r++] = hit[k];
                    ranges[r++] = hit[k] + 1;
                }
            }
            return new Match(count > 0 && hit[0] == 0 ? START : MIDDLE, best - n, Arrays.copyOf(ranges, r));
        }

        private int index(int pi, int ni, int consecutive) {
            return (pi * (n + 1) + ni) * 2 + consecutive;
        }

        private int best(int pi, int ni, int consecutive) {
            if (pi == m) {
                return 0;
            }
            int key = index(pi, ni, consecutive);
            if (memo[key] != Integer.MAX_VALUE) {
                return memo[key];
            }
            char c = p.charAt(pi);
            int result = NONE;
            int chosen = -1;
            if (c == ' ') {
                result = best(pi + 1, ni, 0);
                chosen = -1;
            } else {
                boolean separator = !Character.isLetterOrDigit(c);
                // direkt anschließend – ein Großbuchstabe der Eingabe nur auf einen Großbuchstaben oder Wortanfang
                if (consecutive == 1 && ni < n && same(s.charAt(ni), c)
                        && (!Character.isUpperCase(c) || Character.isUpperCase(s.charAt(ni)) || wordStart(s, ni))) {
                    int rest = best(pi + 1, ni + 1, 1);
                    if (rest > NONE) {
                        int v = 25 + (s.charAt(ni) == c ? 1 : 0) + rest;
                        if (v > result) {
                            result = v;
                            chosen = ni;
                        }
                    }
                }
                // Sprung zu einem späteren Wortanfang (Trenner wie '_' dürfen überall treffen)
                for (int j = consecutive == 1 ? ni + 1 : ni; j < n; j++) {
                    if (!same(s.charAt(j), c) || !separator && !wordStart(s, j)) {
                        continue;
                    }
                    int rest = best(pi + 1, j + 1, 1);
                    if (rest <= NONE) {
                        continue;
                    }
                    int v = 16 + (s.charAt(j) == c ? 1 : 0) - Math.min(j - ni, 8) + (pi == 0 && j == 0 ? 30 : 0)
                            + rest;
                    if (v > result) {
                        result = v;
                        chosen = j;
                    }
                }
            }
            memo[key] = result;
            choice[key] = chosen;
            return result;
        }
    }
}
