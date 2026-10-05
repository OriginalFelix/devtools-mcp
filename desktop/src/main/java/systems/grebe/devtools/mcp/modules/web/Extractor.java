package systems.grebe.devtools.mcp.modules.web;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extraktive Vorauswahl ohne LLM: zerlegt den Seitentext in Sätze und bewertet sie – mit Fragestellung nach BM25 auf
 * deren Begriffe, sonst nach Zentralität (Sätze aus häufigen Begriffen der Seite), jeweils mit leichtem Vorteil für
 * frühe Sätze und solche direkt unter einer Überschrift. Das lokale Modell bekommt nur die besten Sätze, weil seine
 * Laufzeit mit der Länge der Eingabe wächst (Prefill ist der teure Teil).
 */
final class Extractor {

    /** Satz der Seite: Position im Text, Abschnittsüberschrift, Bewertung. */
    record Sentence(int index, String text, String heading, double score) {
    }

    /** Wort inkl. Punkt, + und # im Inneren (Foo.bar, C++, C#, 2.0); Bindestriche trennen (Java-Version). */
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}_.+#]*");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+(?=[\\p{Lu}\\d\"„(])");
    private static final int MAX_SENTENCE = 400;
    private static final int MIN_SENTENCE = 25;
    private static final double K1 = 1.2;
    private static final double B = 0.75;
    private static final Set<String> STOP = Set.of(
            "the", "and", "for", "are", "but", "not", "you", "all", "any", "can", "had", "her", "was", "one", "our",
            "out", "has", "have", "this", "that", "with", "from", "they", "will", "would", "there", "their", "what",
            "about", "which", "when", "make", "like", "into", "than", "then", "them", "these", "some", "also", "its",
            "use", "used", "using", "how", "may", "more", "most", "other", "such", "only", "over", "each", "does",
            "der", "die", "das", "und", "ist", "nicht", "ein", "eine", "einer", "eines", "einem", "einen", "mit",
            "von", "für", "auf", "den", "dem", "des", "sich", "auch", "als", "wie", "bei", "aus", "oder", "wird",
            "werden", "sind", "noch", "nur", "nach", "zum", "zur", "über", "kann", "können", "dass", "wenn", "sie",
            "wir", "ich", "welche", "welcher", "welches", "gibt", "hat", "haben", "man", "dies", "diese");

    private Extractor() {
    }

    /** Alle bewertbaren Sätze der Seite, in Textreihenfolge. */
    static List<Sentence> score(String text, String focus) {
        List<String[]> units = split(text);
        List<List<String>> terms = new ArrayList<>();
        Map<String, Integer> df = new HashMap<>();
        Map<String, Integer> tf = new HashMap<>();
        long totalLength = 0;
        for (String[] u : units) {
            List<String> t = terms(u[0]);
            terms.add(t);
            totalLength += t.size();
            t.forEach(w -> tf.merge(w, 1, Integer::sum));
            t.stream().distinct().forEach(w -> df.merge(w, 1, Integer::sum));
        }
        int n = units.size();
        double avgLength = n == 0 ? 1 : Math.max(1, (double) totalLength / n);
        List<String> query = focus == null || focus.isBlank() ? List.of() : terms(focus).stream().distinct().toList();
        List<Sentence> out = new ArrayList<>();
        String previousHeading = null;
        for (int i = 0; i < n; i++) {
            String[] u = units.get(i);
            List<String> t = terms.get(i);
            double position = 1.0 / (1 + i / 15.0);
            boolean leading = u[1] != null && !u[1].equals(previousHeading);
            previousHeading = u[1];
            double score;
            if (!query.isEmpty()) {
                score = bm25(query, t, df, n, avgLength) + bm25(query, terms(u[1] == null ? "" : u[1]), df, n, avgLength) * 0.3;
                score = score <= 0 ? 0 : score + 0.2 * position;
            } else {
                double centrality = 0;
                for (String w : t.stream().distinct().toList()) {
                    int f = tf.getOrDefault(w, 0);
                    if (f > 1) {
                        centrality += Math.log(f);
                    }
                }
                score = centrality / Math.sqrt(Math.max(4, t.size())) + 1.5 * position + (leading ? 0.8 : 0);
            }
            out.add(new Sentence(i, u[0], u[1], score));
        }
        return out;
    }

    /**
     * Die besten Sätze bis {@code maxChars}, in Textreihenfolge. Mit Fragestellung zählen nur Sätze, die einen ihrer
     * Begriffe enthalten; findet sich keiner, die allgemein besten.
     */
    static List<Sentence> select(List<Sentence> scored, int maxChars) {
        List<Sentence> ranked = scored.stream().filter(s -> s.score() > 0)
                .sorted(Comparator.comparingDouble(Sentence::score).reversed()).toList();
        List<Sentence> chosen = new ArrayList<>();
        int used = 0;
        for (Sentence s : ranked) {
            if (used + s.text().length() > maxChars) {
                continue;
            }
            chosen.add(s);
            used += s.text().length() + 1;
        }
        chosen.sort(Comparator.comparingInt(Sentence::index));
        return chosen;
    }

    /** Sätze mit ihrer Abschnittsüberschrift; Überschriften selbst, Codezeilen und Bruchstücke zählen nicht. */
    private static List<String[]> split(String text) {
        List<String[]> out = new ArrayList<>();
        String heading = null;
        boolean code = false;
        for (String raw : text.split("\n")) {
            String line = raw.strip();
            if (line.startsWith("```")) {
                code = !code;
                continue;
            }
            if (code || line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#")) {
                heading = line.replaceFirst("^#+\\s*", "");
                continue;
            }
            line = line.replaceFirst("^[-*]\\s+", "");
            for (String s : SENTENCE_END.split(line)) {
                String sentence = s.strip();
                if (sentence.length() > MAX_SENTENCE) {
                    sentence = sentence.substring(0, MAX_SENTENCE) + " …";
                }
                // Tabellenzeilen und Einleitungen („… include:“) taugen weder als Auszug noch als Kernaussage
                boolean table = sentence.startsWith("|") || sentence.endsWith("|") || sentence.contains(" | ");
                if (sentence.length() >= MIN_SENTENCE && words(sentence) >= 4 && !table && !sentence.endsWith(":")) {
                    out.add(new String[] {sentence, heading});
                }
            }
        }
        return out;
    }

    private static int words(String s) {
        int n = 0;
        Matcher m = WORD.matcher(s);
        while (m.find()) {
            n++;
        }
        return n;
    }

    static List<String> terms(String s) {
        List<String> out = new ArrayList<>();
        Matcher m = WORD.matcher(s.toLowerCase(Locale.ROOT));
        while (m.find()) {
            String w = m.group().replaceAll("[.\\-]+$", "");
            if (w.length() >= 3 && !STOP.contains(w)) {
                out.add(stem(w));
            }
        }
        return out;
    }

    /** Grobe Grundform, damit „versions“/„version“ bzw. „Versionen“/„Version“ zusammenfallen. */
    private static String stem(String w) {
        for (String suffix : new String[] {"en", "es", "s", "e"}) {
            if (w.length() > suffix.length() + 3 && w.endsWith(suffix) && Character.isLetter(w.charAt(w.length() - 1))) {
                return w.substring(0, w.length() - suffix.length());
            }
        }
        return w;
    }

    private static double bm25(List<String> query, List<String> doc, Map<String, Integer> df, int n, double avgLength) {
        if (doc.isEmpty()) {
            return 0;
        }
        Map<String, Integer> tf = new HashMap<>();
        doc.forEach(w -> tf.merge(w, 1, Integer::sum));
        double score = 0;
        for (String q : query) {
            Integer f = tf.get(q);
            if (f == null) {
                continue;
            }
            int d = df.getOrDefault(q, 0);
            double idf = Math.log(1 + (n - d + 0.5) / (d + 0.5));
            score += idf * f * (K1 + 1) / (f + K1 * (1 - B + B * doc.size() / avgLength));
        }
        return score;
    }
}
