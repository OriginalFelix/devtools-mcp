package systems.grebe.devtools.mcp.modules.web;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Erkennt Wiederholungsschleifen, in die kleine Modelle geraten (derselbe Satz immer wieder bis zum Token-Limit), und
 * schneidet die Antwort vor der ersten Wiederholung ab.
 */
final class Repetition {

    /** Zeilen ab dieser Länge kommen in einer Zusammenfassung nicht zweimal wörtlich vor. */
    private static final int MIN_LINE = 12;
    /** Ein so langes Stück Text, das sich wörtlich wiederholt, gilt als Schleife. */
    private static final int WINDOW = 80;

    private Repetition() {
    }

    /** Ob der bisher erzeugte Text in eine Schleife geraten ist. */
    static boolean loops(CharSequence text) {
        int n = text.length();
        if (n == 0) {
            return false;
        }
        if (text.charAt(n - 1) == '\n') {
            String s = text.toString().stripTrailing();
            int start = s.lastIndexOf('\n') + 1;
            String last = s.substring(start).strip();
            if (last.length() >= MIN_LINE) {
                for (String line : s.substring(0, start).split("\n")) {
                    if (line.strip().equals(last)) {
                        return true;
                    }
                }
            }
        }
        if (n >= 2 * WINDOW) {
            String s = text.toString();
            String window = s.substring(n - WINDOW);
            return s.lastIndexOf(window, n - WINDOW - 1) >= 0;
        }
        return false;
    }

    /** Text bis vor die erste wörtliche Wiederholung (Zeile oder längeres Stück), an einer Satz-/Zeilengrenze. */
    static String cut(String text) {
        StringBuilder kept = new StringBuilder();
        Set<String> seen = new HashSet<>();
        for (String line : text.split("\n", -1)) {
            String key = line.strip();
            if (key.length() >= MIN_LINE && !seen.add(key)) {
                break;
            }
            kept.append(line).append('\n');
        }
        String s = kept.toString().stripTrailing();
        Map<String, Integer> windows = new HashMap<>();
        for (int i = 0; i + WINDOW <= s.length(); i++) {
            String w = s.substring(i, i + WINDOW);
            if (windows.putIfAbsent(w, i) != null) {
                int boundary = Math.max(s.lastIndexOf('\n', i), s.lastIndexOf(". ", i) + 1);
                s = s.substring(0, boundary > 0 ? boundary : i).stripTrailing();
                break;
            }
        }
        return s;
    }
}
