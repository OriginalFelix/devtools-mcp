package systems.grebe.devtools.mcp.modules.scripts.assist;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import io.cucumber.gherkin.GherkinDialect;
import io.cucumber.gherkin.GherkinDialects;
import systems.grebe.devtools.mcp.modules.scripts.ScriptViews;

/**
 * Syntaxhervorhebung für den Skript-Editor: liefert Bereiche mit CSS-Klassen, die Farben stehen in {@code app.css}
 * (angelehnt an das Schema „IntelliJ Light“). Bereiche ohne Klasse bleiben in der Grundfarbe.
 *
 * <ul>
 *   <li>Groovy/Java: Schlüsselwörter, Zeichenketten mit Escapes und GString-Code, Zahlen, Kommentare, Annotationen,
 *       Konstanten, Methoden-Deklarationen; in Groovy zusätzlich die DSL ({@code module}, {@code tool},
 *       {@code param} …), {@code args.x}/{@code cfg.x} und die Anweisung {@code // devtools: …}.</li>
 *   <li>Gherkin: Schlüsselwörter der Sprache aus {@code # language:} (Standard Deutsch), Tags, Tabellen, DocStrings,
 *       Zeichenketten, Platzhalter {@code <name>} und Variablen {@code ${name}}.</li>
 * </ul>
 */
public final class SyntaxHighlighter {

    /** Bereich {@code [start, end)} mit einer CSS-Klasse. */
    public record Span(int start, int end, String style) {
    }

    /** Aufrufe der Groovy-DSL, wenn sie eine Anweisung beginnen ({@code description 'x'}). */
    static final Set<String> DSL = Set.of("module", "tool", "name", "description", "instructions", "setting",
            "enabledByDefault", "param", "readOnly", "destructive", "idempotent", "openWorld", "execute", "run",
            "progress");
    private static final Set<String> TYPE_KEYWORDS = Set.of("def", "var", "void", "boolean", "byte", "char", "short",
            "int", "long", "float", "double");
    private static final Pattern CONSTANT = Pattern.compile("[A-Z][A-Z0-9_]*[A-Z0-9]");

    private SyntaxHighlighter() {
    }

    public static List<Span> spans(ScriptViews.Language language, String text) {
        if (language == ScriptViews.Language.GHERKIN) {
            return gherkin(text);
        }
        return code(text, language != ScriptViews.Language.JAVA);
    }

    /** Ob die Position in einer Zeichenkette oder einem Kommentar liegt (Gherkin: zwischen Anführungszeichen). */
    public static boolean inLiteral(ScriptViews.Language language, String text, int pos) {
        if (language == ScriptViews.Language.GHERKIN) {
            int lineStart = text.lastIndexOf('\n', pos - 1) + 1;
            return text.substring(lineStart, pos).chars().filter(c -> c == '"').count() % 2 == 1;
        }
        return CodeLexer.lex(text, language != ScriptViews.Language.JAVA).inLiteral(pos);
    }

    /**
     * Die zur Klammer an {@code pos} passende Klammer – Klammern in Zeichenketten und Kommentaren zählen nicht.
     *
     * @return Position der Gegenklammer oder -1 (keine Klammer an {@code pos}, keine Gegenklammer oder Gherkin)
     */
    public static int matchingBracket(ScriptViews.Language language, String text, int pos) {
        if (language == ScriptViews.Language.GHERKIN || pos < 0 || pos >= text.length()
                || "(){}[]".indexOf(text.charAt(pos)) < 0) {
            return -1;
        }
        CodeLexer lexer = CodeLexer.lex(text, language != ScriptViews.Language.JAVA);
        List<CodeLexer.Token> brackets = lexer.tokens().stream()
                .filter(t -> t.type() == CodeLexer.Type.PUNCT && t.end() - t.start() == 1
                        && "(){}[]".indexOf(text.charAt(t.start())) >= 0)
                .toList();
        int index = -1;
        for (int k = 0; k < brackets.size(); k++) {
            if (brackets.get(k).start() == pos) {
                index = k;
            }
        }
        if (index < 0) {
            return -1;
        }
        char c = text.charAt(pos);
        int open = "({[".indexOf(c);
        char partner = open >= 0 ? ")}]".charAt(open) : "({[".charAt(")}]".indexOf(c));
        int step = open >= 0 ? 1 : -1;
        int depth = 0;
        for (int k = index; k >= 0 && k < brackets.size(); k += step) {
            char b = text.charAt(brackets.get(k).start());
            if (b == c) {
                depth++;
            } else if (b == partner && --depth == 0) {
                return brackets.get(k).start();
            }
        }
        return -1;
    }

    /**
     * Ob die nächste Zeile eine Ebene tiefer beginnt: nach {@code {}, {@code (}, {@code [} und {@code ->}, in Gherkin
     * nach einer Überschrift ({@code Szenario: …}) oder einem Schritt mit Doppelpunkt (Tabelle folgt).
     *
     * @param line die Zeile bis zur Schreibmarke
     */
    public static boolean opensBlock(ScriptViews.Language language, String text, String line) {
        String t = line.strip();
        if (language == ScriptViews.Language.GHERKIN) {
            return t.endsWith(":") || headerKeywords(dialect(text)).stream().anyMatch(h -> t.startsWith(h + ":"));
        }
        return t.endsWith("{") || t.endsWith("(") || t.endsWith("[") || t.endsWith("->");
    }

    // ------------------------------------------------------------------ Groovy/Java

    static List<Span> code(String text, boolean groovy) {
        CodeLexer lexer = CodeLexer.lex(text, groovy);
        List<CodeLexer.Token> tokens = lexer.tokens();
        List<Span> out = new ArrayList<>(tokens.size());
        CodeLexer.Token prev = null;
        CodeLexer.Token prev2 = null;
        boolean statementStart = true;
        for (int k = 0; k < tokens.size(); k++) {
            CodeLexer.Token t = tokens.get(k);
            String style = switch (t.type()) {
                case KEYWORD -> "kw";
                case NUMBER -> "num";
                case STRING -> "str";
                case ESCAPE -> "esc";
                case INTERPOLATION -> "interp";
                case ANNOTATION -> "ann";
                case COMMENT -> "cmt";
                case DOC_COMMENT -> "doc";
                case DIRECTIVE -> "directive";
                case IDENT -> identifier(lexer, tokens, k, prev, prev2, statementStart, groovy);
                default -> null;
            };
            if (style != null) {
                out.add(new Span(t.start(), t.end(), style));
            }
            switch (t.type()) {
                case COMMENT, DOC_COMMENT, DIRECTIVE -> {
                    // ändert den Zusammenhang nicht
                }
                case NEWLINE -> statementStart = true;
                default -> {
                    String p = t.type() == CodeLexer.Type.PUNCT ? lexer.text(t) : "";
                    statementStart = p.equals("{") || p.equals(";") || p.equals("->") || p.equals("}");
                    prev2 = prev;
                    prev = t;
                }
            }
        }
        return out;
    }

    private static String identifier(CodeLexer lexer, List<CodeLexer.Token> tokens, int k, CodeLexer.Token prev,
                                     CodeLexer.Token prev2, boolean statementStart, boolean groovy) {
        String word = lexer.text(tokens.get(k));
        CodeLexer.Token next = next(tokens, k);
        String nx = next == null ? "" : lexer.text(next);
        String pv = prev == null ? "" : lexer.text(prev);
        if (groovy) {
            if (statementStart && DSL.contains(word) && !nx.equals("=") && !nx.equals(".")) {
                return "dsl";
            }
            if (word.equals("log") && nx.equals(".") && !pv.equals(".")) {
                return "dsl";
            }
            if (pv.equals(".") && prev2 != null && prev2.type() == CodeLexer.Type.IDENT
                    && Set.of("args", "cfg").contains(lexer.text(prev2))) {
                return "field";
            }
        }
        if (nx.equals("(") && prev != null && declarationType(prev, pv, groovy)) {
            return "decl";
        }
        if (CONSTANT.matcher(word).matches() && !nx.equals("(")) {
            return "const";
        }
        return null;
    }

    /** Steht vor dem Namen ein Typ, ist {@code name(} eine Methoden-Deklaration und kein Aufruf. */
    private static boolean declarationType(CodeLexer.Token prev, String text, boolean groovy) {
        return switch (prev.type()) {
            case KEYWORD -> TYPE_KEYWORDS.contains(text);
            case IDENT -> !groovy || Character.isUpperCase(text.charAt(0));
            case PUNCT -> text.equals(">") || text.equals("]");
            default -> false;
        };
    }

    private static CodeLexer.Token next(List<CodeLexer.Token> tokens, int k) {
        for (int j = k + 1; j < tokens.size(); j++) {
            CodeLexer.Type type = tokens.get(j).type();
            if (type != CodeLexer.Type.COMMENT && type != CodeLexer.Type.DOC_COMMENT) {
                return tokens.get(j);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ Gherkin

    private static final Pattern LANGUAGE = Pattern.compile("#\\s*language\\s*:\\s*([\\w-]*)\\s*");
    private static final Pattern INLINE = Pattern.compile("<[A-Za-z][A-Za-z0-9_]*>|\\$\\{[A-Za-z][A-Za-z0-9_]*}");

    /** Sprache aus der Kopfzeile {@code # language: …} (vor der ersten anderen Zeile), sonst Deutsch. */
    static GherkinDialect dialect(String text) {
        for (String line : (Iterable<String>) text.lines()::iterator) {
            String l = line.strip();
            if (l.isEmpty()) {
                continue;
            }
            Matcher m = LANGUAGE.matcher(l);
            if (m.matches()) {
                return GherkinDialects.getDialect(m.group(1)).orElse(german());
            }
            if (!l.startsWith("#")) {
                break;
            }
        }
        return german();
    }

    static GherkinDialect german() {
        return GherkinDialects.getDialect("de").orElseThrow();
    }

    /** Überschriften wie {@code Funktionalität}, {@code Szenario} – längste zuerst. */
    static List<String> headerKeywords(GherkinDialect d) {
        return Stream.of(d.getFeatureKeywords(), d.getRuleKeywords(), d.getBackgroundKeywords(),
                        d.getScenarioKeywords(), d.getScenarioOutlineKeywords(), d.getExamplesKeywords())
                .flatMap(List::stream).distinct().sorted(Comparator.comparingInt(String::length).reversed()).toList();
    }

    /** Schritt-Schlüsselwörter mit Leerzeichen am Ende ({@code "Wenn "}), längste zuerst. */
    static List<String> stepKeywords(GherkinDialect d) {
        return d.getStepKeywords().stream().distinct()
                .sorted(Comparator.comparingInt(String::length).reversed()).toList();
    }

    static List<Span> gherkin(String text) {
        GherkinDialect dialect = dialect(text);
        List<String> headers = headerKeywords(dialect);
        List<String> steps = stepKeywords(dialect);
        List<Span> out = new ArrayList<>();
        String docDelimiter = null;
        int pos = 0;
        int n = text.length();
        while (pos <= n) {
            int eol = text.indexOf('\n', pos);
            if (eol < 0) {
                eol = n;
            }
            int end = eol;
            while (end > pos && Character.isWhitespace(text.charAt(end - 1))) {
                end--;
            }
            int s = pos;
            while (s < end && (text.charAt(s) == ' ' || text.charAt(s) == '\t')) {
                s++;
            }
            if (s < end) {
                if (docDelimiter != null) {
                    if (text.startsWith(docDelimiter, s)) {
                        out.add(new Span(s, end, "docstr"));
                        docDelimiter = null;
                    } else {
                        inline(text, s, end, "docstr", out);
                    }
                } else if (text.startsWith("\"\"\"", s) || text.startsWith("```", s)) {
                    docDelimiter = text.substring(s, s + 3);
                    out.add(new Span(s, end, "docstr"));
                } else if (text.charAt(s) == '#') {
                    boolean language = LANGUAGE.matcher(text.substring(s, end)).matches();
                    out.add(new Span(s, end, language ? "directive" : "cmt"));
                } else if (text.charAt(s) == '@') {
                    tags(text, s, end, out);
                } else if (text.charAt(s) == '|') {
                    table(text, s, end, out);
                } else {
                    line(text, s, end, headers, steps, out);
                }
            }
            pos = eol + 1;
        }
        return out;
    }

    private static void line(String text, int s, int end, List<String> headers, List<String> steps, List<Span> out) {
        for (String h : headers) {
            if (text.startsWith(h, s) && s + h.length() < end && text.charAt(s + h.length()) == ':') {
                int colon = s + h.length() + 1;
                out.add(new Span(s, colon, "g-kw"));
                int title = colon;
                while (title < end && text.charAt(title) == ' ') {
                    title++;
                }
                if (title < end) {
                    out.add(new Span(title, end, "g-title"));
                }
                return;
            }
        }
        for (String k : steps) {
            if (k.strip().equals("*") ? text.startsWith("* ", s) : text.startsWith(k, s)) {
                int keywordEnd = s + k.stripTrailing().length();
                out.add(new Span(s, keywordEnd, "g-step"));
                inline(text, keywordEnd, end, null, out);
                return;
            }
        }
        inline(text, s, end, null, out); // Beschreibung, z.B. „<repo>: Repository-Name“
    }

    private static void tags(String text, int s, int end, List<Span> out) {
        int j = s;
        while (j < end) {
            char c = text.charAt(j);
            if (c == '#') {
                out.add(new Span(j, end, "cmt"));
                return;
            }
            if (c == '@') {
                int start = j;
                while (j < end && !Character.isWhitespace(text.charAt(j))) {
                    j++;
                }
                out.add(new Span(start, j, "tag"));
            } else {
                j++;
            }
        }
    }

    private static void table(String text, int s, int end, List<Span> out) {
        int cell = s;
        for (int j = s; j < end; j++) {
            if (text.charAt(j) == '|' && (j == s || text.charAt(j - 1) != '\\')) {
                inline(text, cell, j, null, out);
                out.add(new Span(j, j + 1, "pipe"));
                cell = j + 1;
            }
        }
        inline(text, cell, end, null, out);
    }

    /**
     * Schritt-Text, Tabellenzelle oder DocString-Zeile: Zeichenketten, Platzhalter, Variablen und Zahlen. Bereiche
     * dürfen sich nicht überlappen – eine Zeichenkette wird an Platzhaltern aufgeteilt.
     */
    private static void inline(String text, int from, int to, String base, List<Span> out) {
        boolean quoted = false;
        int segment = from;
        int j = from;
        while (j < to) {
            char c = text.charAt(j);
            if (c == '"' && base == null) {
                if (!quoted) {
                    segment(out, segment, j, null);
                    segment = j;
                    quoted = true;
                    j++;
                } else {
                    segment(out, segment, j + 1, "str");
                    segment = ++j;
                    quoted = false;
                }
                continue;
            }
            if (c == '<' || c == '$') {
                Matcher m = INLINE.matcher(text).region(j, to);
                if (m.lookingAt()) {
                    segment(out, segment, j, quoted ? "str" : base);
                    out.add(new Span(j, m.end(), c == '<' ? "ph" : "var"));
                    segment = j = m.end();
                    continue;
                }
            }
            if (!quoted && base == null && Character.isDigit(c) && (j == from || !Character.isLetterOrDigit(
                    text.charAt(j - 1)))) {
                int e = j;
                while (e < to && Character.isDigit(text.charAt(e))) {
                    e++;
                }
                if (e == to || !Character.isLetter(text.charAt(e))) {
                    segment(out, segment, j, null);
                    out.add(new Span(j, e, "num"));
                    segment = j = e;
                    continue;
                }
            }
            j++;
        }
        segment(out, segment, to, quoted ? "str" : base);
    }

    private static void segment(List<Span> out, int start, int end, String style) {
        if (style != null && end > start) {
            out.add(new Span(start, end, style));
        }
    }
}
