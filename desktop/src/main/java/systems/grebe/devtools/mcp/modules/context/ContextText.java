package systems.grebe.devtools.mcp.modules.context;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Kürzen, Ausschnitte und Suche in abgelegten Ergebnissen – reine Textfunktionen. */
final class ContextText {

    /** Platz, den der Hinweis in der Mitte eines gekürzten Ergebnisses ungefähr braucht. */
    static final int MARKER_RESERVE = 320;
    /** Anteil des Budgets für den Anfang; der Rest geht an das Ende (Fehler stehen oft dort). */
    static final double HEAD_SHARE = 0.6;
    static final int MAX_MATCHES = 400;

    private ContextText() {
    }

    static List<String> lines(String text) {
        return text == null || text.isEmpty() ? List.of() : List.of(text.split("\n", -1));
    }

    /**
     * Anfang und Ende von {@code text}, dazwischen ein Hinweis mit dem Handle. Geschnitten wird an Zeilengrenzen, nur
     * bei sehr langen Zeilen (minifiziertes JSON …) mitten in der Zeile.
     */
    static String truncate(String text, int maxChars, String handle) {
        if (text.length() <= maxChars) {
            return text;
        }
        int budget = Math.max(200, maxChars - MARKER_RESERVE);
        int head = (int) (budget * HEAD_SHARE);
        int tail = budget - head;
        int headEnd = text.lastIndexOf('\n', head);
        if (headEnd < head / 2) {
            headEnd = head; // keine Zeilengrenze in Reichweite: mitten in der Zeile schneiden
        }
        int tailStart = text.indexOf('\n', text.length() - tail);
        if (tailStart < 0 || tailStart <= headEnd || text.length() - tailStart < tail / 2) {
            tailStart = Math.max(headEnd, text.length() - tail);
        } else {
            tailStart++; // hinter dem Zeilenumbruch beginnen
        }
        int total = countLines(text, text.length());
        int firstOmitted = countLines(text, headEnd) + 1;
        int lastOmitted = countLines(text, tailStart);
        String where = firstOmitted <= lastOmitted
                ? "Zeilen " + firstOmitted + "–" + lastOmitted + " von " + total
                : "Zeichen " + headEnd + "–" + tailStart + " von " + text.length();
        String marker = "\n… [DevTools: " + where + " ausgelassen (" + (tailStart - headEnd) + " Zeichen). Vollständig "
                + "unter Handle " + handle + ": context_slice(handle=\"" + handle + "\", grep=\"…\" oder "
                + "from_line/to_line), context_digest(handle=\"" + handle + "\") fasst zusammen.]\n";
        return text.substring(0, headEnd) + marker + text.substring(tailStart);
    }

    /** Anzahl der Zeilen im Text bis {@code end} (exklusiv), mindestens 1. */
    static int countLines(String text, int end) {
        int n = 1;
        for (int i = 0; i < end && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }

    /** Zeilen {@code from}–{@code to} (1-basiert, inklusive), höchstens {@code maxChars} Zeichen. */
    static String slice(ResultStore.Stored s, int from, int to, int maxChars) {
        List<String> lines = lines(s.text());
        int n = lines.size();
        int a = Math.max(1, from);
        int b = to <= 0 ? n : Math.min(n, to);
        if (a > n) {
            return "Handle " + s.handle() + " hat nur " + n + " Zeilen.";
        }
        StringBuilder body = new StringBuilder();
        int last = a - 1;
        for (int i = a; i <= b; i++) {
            String line = lines.get(i - 1);
            if (body.length() + line.length() + 1 > maxChars) {
                if (i == a) { // schon die erste Zeile ist zu lang
                    body.append(line, 0, Math.max(0, maxChars)).append(" … [Zeile ").append(i).append(" gekürzt, ")
                            .append(line.length()).append(" Zeichen]\n");
                    last = i;
                }
                break;
            }
            body.append(line).append('\n');
            last = i;
        }
        StringBuilder sb = new StringBuilder("[").append(s.handle()).append(" · ").append(s.tool()).append(" · Zeilen ")
                .append(a).append('–').append(last).append(" von ").append(n).append("]\n").append(body);
        if (last < b) {
            sb.append("… [weiter mit from_line=").append(last + 1).append("]");
        }
        return sb.toString().stripTrailing();
    }

    /** Zeilen, auf die {@code query} passt (Regex, ohne Groß/klein; ungültig = wörtlich), mit Umgebung. */
    static String grep(ResultStore.Stored s, String query, int context, int maxChars) {
        Pattern p;
        try {
            p = Pattern.compile(query, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        } catch (PatternSyntaxException e) {
            p = Pattern.compile(Pattern.quote(query), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        }
        List<String> lines = lines(s.text());
        List<Integer> hits = new ArrayList<>();
        for (int i = 0; i < lines.size() && hits.size() < MAX_MATCHES; i++) {
            if (p.matcher(lines.get(i)).find()) {
                hits.add(i);
            }
        }
        if (hits.isEmpty()) {
            return "[" + s.handle() + " · " + s.tool() + "] Keine Zeile passt auf „" + query + "“ (" + lines.size()
                    + " Zeilen durchsucht).";
        }
        java.util.Set<Integer> hitSet = new java.util.HashSet<>(hits);
        int ctx = Math.max(0, Math.min(20, context));
        StringBuilder body = new StringBuilder();
        int printedUpTo = -1;
        int shown = 0;
        boolean cut = false;
        for (int hit : hits) {
            int from = Math.max(printedUpTo + 1, hit - ctx);
            int to = Math.min(lines.size() - 1, hit + ctx);
            StringBuilder block = new StringBuilder();
            if (printedUpTo >= 0 && from > printedUpTo + 1) {
                block.append("--\n");
            }
            for (int i = from; i <= to; i++) {
                block.append(i + 1).append(hitSet.contains(i) ? ": " : "- ").append(clip(lines.get(i)))
                        .append('\n');
            }
            if (body.length() + block.length() > maxChars) {
                cut = true;
                break;
            }
            body.append(block);
            printedUpTo = to;
            shown++;
        }
        StringBuilder sb = new StringBuilder("[").append(s.handle()).append(" · ").append(s.tool()).append(" · ")
                .append(hits.size()).append(hits.size() >= MAX_MATCHES ? "+" : "").append(" Treffer in ")
                .append(lines.size()).append(" Zeilen]\n").append(body);
        if (cut) {
            sb.append("… [").append(hits.size() - shown).append(" weitere Treffer – Suche eingrenzen oder from_line=")
                    .append(printedUpTo + 2).append("]");
        }
        return sb.toString().stripTrailing();
    }

    /** Sehr lange Zeilen in Trefferlisten kürzen (minifiziertes JSON würde sonst das ganze Budget belegen). */
    private static String clip(String line) {
        return line.length() <= 500 ? line : line.substring(0, 500) + " … [" + line.length() + " Zeichen]";
    }
}
