package systems.grebe.devtools.mcp.ui.code;

import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import systems.grebe.devtools.mcp.modules.scripts.ScriptViews.Language;
import systems.grebe.devtools.mcp.modules.scripts.assist.Completion;
import systems.grebe.devtools.mcp.modules.scripts.assist.SyntaxHighlighter;

/**
 * Die Textänderungen des Skript-Editors als reine Funktionen – ohne JavaFX und damit ohne Fenster testbar. Jede
 * Funktion bekommt Text und Auswahl und liefert eine {@link Edit} (oder {@code null}: Standardverhalten des Editors).
 */
final class Edits {

    /**
     * Ersetzt {@code [from, to)} durch {@code text}; danach Auswahl {@code [anchor, caret]} im neuen Text (gleich =
     * nur Schreibmarke).
     */
    record Edit(int from, int to, String text, int anchor, int caret) {

        static Edit at(int from, int to, String text, int caret) {
            return new Edit(from, to, text, caret, caret);
        }

        String applyTo(String source) {
            return source.substring(0, from) + text + source.substring(to);
        }
    }

    private Edits() {
    }

    static String unit(Language language) {
        return language == Language.GHERKIN ? "  " : "    ";
    }

    private static boolean code(Language language) {
        return language != Language.GHERKIN;
    }

    // ------------------------------------------------------------------ Tippen

    /**
     * Ein getipptes Zeichen: schließende Klammer/Anführungszeichen überschreiben, Klammern und Anführungszeichen
     * paarweise einfügen, {@code }} auf einer Zeile nur aus Leerzeichen eine Ebene ausrücken. Mit Auswahl nichts.
     */
    static Edit typed(Language language, String text, int caret, char c) {
        char next = caret < text.length() ? text.charAt(caret) : '\n';
        char prev = caret > 0 ? text.charAt(caret - 1) : '\n';
        boolean code = code(language);
        boolean quote = c == '"' || c == '\'' && code;
        if (next == c && (code && ")]}".indexOf(c) >= 0 || quote && SyntaxHighlighter.inLiteral(language, text, caret))) {
            return Edit.at(caret, caret, "", caret + 1);
        }
        boolean free = Character.isWhitespace(next) || ")]},;:".indexOf(next) >= 0;
        if (code && "([{".indexOf(c) >= 0 && (free || c == '{' && prev == '$')) {
            return Edit.at(caret, caret, c + String.valueOf(")]}".charAt("([{".indexOf(c))), caret + 1);
        }
        if (quote && free && !Character.isLetterOrDigit(prev) && prev != c
                && !SyntaxHighlighter.inLiteral(language, text, caret)) {
            return Edit.at(caret, caret, String.valueOf(c) + c, caret + 1);
        }
        if (c == '}' && code) {
            String before = text.substring(text.lastIndexOf('\n', caret - 1) + 1, caret);
            if (!before.isEmpty() && before.isBlank()) {
                int from = caret - Math.min(unit(language).length(), before.length());
                return Edit.at(from, caret, "}", from + 1);
            }
        }
        return null;
    }

    /** Rücktaste zwischen einem leeren Paar ({@code ()}, {@code ""} …) löscht beide Zeichen, sonst {@code null}. */
    static Edit deletePair(Language language, String text, int caret) {
        if (caret == 0 || caret >= text.length()) {
            return null;
        }
        String pair = text.substring(caret - 1, caret + 1);
        if (pair.equals("\"\"") || code(language) && (pair.equals("()") || pair.equals("[]") || pair.equals("{}")
                || pair.equals("''"))) {
            return Edit.at(caret - 1, caret + 1, "", caret - 1);
        }
        return null;
    }

    // ------------------------------------------------------------------ Zeilen

    /** Enter: Einrückung übernehmen, nach {@code {}/{@code ->}/Gherkin-Überschrift eine Ebene tiefer, {@code {|}} aufklappen. */
    static Edit newline(Language language, String text, int start, int end) {
        int lineStart = text.lastIndexOf('\n', start - 1) + 1;
        String line = text.substring(lineStart, start);
        String indent = line.substring(0, line.length() - line.stripLeading().length());
        String before = line.strip();
        char last = before.isEmpty() ? 0 : before.charAt(before.length() - 1);
        char next = end < text.length() ? text.charAt(end) : 0;
        boolean open = SyntaxHighlighter.opensBlock(language, text, line);
        String unit = unit(language);
        if (open && (last == '{' && next == '}' || last == '(' && next == ')' || last == '[' && next == ']')) {
            return Edit.at(start, end, "\n" + indent + unit + "\n" + indent, start + 1 + indent.length() + unit.length());
        }
        String insert = "\n" + indent + (open ? unit : "");
        return Edit.at(start, end, insert, start + insert.length());
    }

    /** Tab: mehrzeilige Auswahl einrücken, sonst Leerzeichen bis zur nächsten Tabstopp-Spalte. */
    static Edit tab(Language language, String text, int start, int end) {
        if (end > start && text.substring(start, end).contains("\n")) {
            return shift(language, text, start, end, true);
        }
        int column = start - (text.lastIndexOf('\n', start - 1) + 1);
        int width = unit(language).length();
        String spaces = " ".repeat(width - column % width);
        return Edit.at(start, end, spaces, start + spaces.length());
    }

    /** Ausgewählte Zeilen (oder die aktuelle) ein- bzw. ausrücken; die Auswahl umfasst danach die ganzen Zeilen. */
    static Edit shift(Language language, String text, int start, int end, boolean in) {
        int[] range = lines(text, start, end);
        String[] lines = text.substring(range[0], range[1]).split("\n", -1);
        String unit = unit(language);
        StringBuilder out = new StringBuilder();
        int firstDelta = 0;
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            String shifted;
            if (in) {
                shifted = l.isBlank() ? l : unit + l;
            } else {
                int remove = 0;
                while (remove < unit.length() && remove < l.length() && l.charAt(remove) == ' ') {
                    remove++;
                }
                shifted = l.substring(remove == 0 && l.startsWith("\t") ? 1 : remove);
            }
            if (i == 0) {
                firstDelta = shifted.length() - l.length();
            }
            out.append(shifted).append(i < lines.length - 1 ? "\n" : "");
        }
        if (end > start) {
            return new Edit(range[0], range[1], out.toString(), range[0], range[0] + out.length());
        }
        return Edit.at(range[0], range[1], out.toString(), Math.max(range[0], start + firstDelta));
    }

    /** Zeilenkommentar an/aus; ohne Auswahl geht die Schreibmarke danach eine Zeile weiter (wie IntelliJ). */
    static Edit toggleComment(Language language, String text, int start, int end) {
        String prefix = code(language) ? "//" : "#";
        int[] range = lines(text, start, end);
        String[] lines = text.substring(range[0], range[1]).split("\n", -1);
        boolean commented = Arrays.stream(lines).anyMatch(l -> !l.isBlank())
                && Arrays.stream(lines).filter(l -> !l.isBlank()).allMatch(l -> l.stripLeading().startsWith(prefix));
        int column = Arrays.stream(lines).filter(l -> !l.isBlank())
                .mapToInt(l -> l.length() - l.stripLeading().length()).min().orElse(0);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            if (l.isBlank() && lines.length > 1) {
                out.append(l);
            } else if (commented) {
                int at = l.indexOf(prefix);
                int after = at + prefix.length();
                if (after < l.length() && l.charAt(after) == ' ') {
                    after++;
                }
                out.append(l, 0, at).append(l.substring(after));
            } else {
                int at = Math.min(column, l.length());
                out.append(l, 0, at).append(prefix).append(' ').append(l.substring(at));
            }
            out.append(i < lines.length - 1 ? "\n" : "");
        }
        String result = out.toString();
        if (end > start) {
            return new Edit(range[0], range[1], result, range[0], range[0] + result.length());
        }
        int newLength = text.length() - (range[1] - range[0]) + result.length();
        return Edit.at(range[0], range[1], result, Math.min(range[0] + result.length() + 1, newLength));
    }

    /** Strg+D: Auswahl bzw. Zeile verdoppeln. */
    static Edit duplicate(String text, int start, int end) {
        if (end > start) {
            String s = text.substring(start, end);
            return new Edit(end, end, s, end, end + s.length());
        }
        int lineStart = text.lastIndexOf('\n', start - 1) + 1;
        int lineEnd = text.indexOf('\n', start);
        lineEnd = lineEnd < 0 ? text.length() : lineEnd;
        String line = text.substring(lineStart, lineEnd);
        return Edit.at(lineEnd, lineEnd, "\n" + line, start + line.length() + 1);
    }

    /** {@code [Anfang der ersten, Ende der letzten Zeile)} der Auswahl; endet sie am Zeilenanfang, zählt die Zeile nicht. */
    private static int[] lines(String text, int start, int end) {
        int first = text.lastIndexOf('\n', start - 1) + 1;
        int endPos = end > start && text.charAt(end - 1) == '\n' ? end - 1 : end;
        int last = text.indexOf('\n', endPos);
        return new int[] {first, last < 0 ? text.length() : last};
    }

    // ------------------------------------------------------------------ Vervollständigung

    /**
     * Vorschlag übernehmen: ersetzt {@code [from, caret)} – mit {@code replaceWord} (Tab) auch den Rest des Wortes –,
     * setzt Schreibmarke bzw. Markierung, rückt Folgezeilen ein und fügt keine zweite Klammer ein, wenn schon eine
     * folgt. Den Import ergänzt {@link #addImport}.
     */
    static Edit accept(String text, int from, int caret, boolean replaceWord, Completion c) {
        int to = caret;
        if (replaceWord) {
            while (to < text.length() && Character.isJavaIdentifierPart(text.charAt(to))) {
                to++;
            }
        }
        String insert = c.insert();
        int caretAt = c.caretOffset();
        if (to < text.length() && text.charAt(to) == '(' && insert.endsWith("()")) {
            insert = insert.substring(0, insert.length() - 2);
            caretAt = Math.min(caretAt, insert.length());
        }
        if (insert.contains("\n")) {
            int lineStart = text.lastIndexOf('\n', from - 1) + 1;
            int i = lineStart;
            while (i < from && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) {
                i++;
            }
            String indent = text.substring(lineStart, i);
            String before = insert.substring(0, caretAt).replace("\n", "\n" + indent);
            insert = before + insert.substring(caretAt).replace("\n", "\n" + indent);
            caretAt = before.length();
        }
        int at = from + caretAt;
        return new Edit(from, to, insert, at, at + c.select());
    }

    private static final Pattern IMPORT_LINE = Pattern.compile("(?m)^[ \\t]*import\\s.*$");
    private static final Pattern JAVA_HEAD = Pattern.compile(
            "\\A(?:\\s*(?://[^\\n]*|/\\*[\\s\\S]*?\\*/))*\\s*(?:package\\s[^;]*;)?");
    private static final Pattern GROOVY_HEAD = Pattern.compile("\\A(?:[ \\t]*//[^\\n]*\\n)*");

    /**
     * {@code import …} (Java mit Semikolon) nach dem letzten Import, sonst nach {@code package} bzw. nach den
     * Kommentarzeilen am Anfang ({@code // devtools: …}); {@code null}, wenn schon (auch per {@code .*}) importiert.
     */
    static Edit addImport(Language language, String text, String qualifiedName) {
        boolean java = language == Language.JAVA;
        String pkg = qualifiedName.substring(0, qualifiedName.lastIndexOf('.'));
        Pattern existing = Pattern.compile("(?m)^\\s*import\\s+(?:" + Pattern.quote(qualifiedName) + "|"
                + Pattern.quote(pkg) + "\\.\\*)\\s*;?\\s*$");
        if (existing.matcher(text).find()) {
            return null;
        }
        String statement = "import " + qualifiedName + (java ? ";" : "");
        Matcher imports = IMPORT_LINE.matcher(text);
        int pos = -1;
        while (imports.find()) {
            pos = imports.end();
        }
        String insert;
        if (pos >= 0) {
            insert = "\n" + statement;
        } else {
            Matcher head = (java ? JAVA_HEAD : GROOVY_HEAD).matcher(text);
            pos = head.find() ? head.end() : 0;
            if (pos > 0 && text.charAt(pos - 1) != '\n') {
                insert = "\n\n" + statement; // direkt hinter „package …;“
            } else {
                boolean blankAfter = pos >= text.length() || text.charAt(pos) == '\n' || text.startsWith("\r\n", pos);
                insert = statement + "\n" + (blankAfter ? "" : "\n");
            }
        }
        return Edit.at(pos, pos, insert, pos + insert.length());
    }
}
