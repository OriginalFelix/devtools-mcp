package systems.grebe.devtools.mcp.modules.scripts.assist;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Zerlegt Groovy- und Java-Quelltext in Token – für Hervorhebung und Autovervollständigung. Robust gegen halbfertigen
 * Code, wie er beim Tippen entsteht: offene Zeichenketten enden am Zeilenende (Text-Blöcke und dreifache
 * Anführungszeichen am Textende), offene Kommentare am Textende. Eingebetteter Code in Groovy-GStrings
 * ({@code "…${code}…"}, {@code "…$name.path…"}) wird wieder als Code zerlegt. Die Token liegen in Textreihenfolge.
 */
final class CodeLexer {

    enum Type {
        IDENT, KEYWORD, NUMBER, STRING, ESCAPE, INTERPOLATION, ANNOTATION, PUNCT, NEWLINE, COMMENT, DOC_COMMENT,
        DIRECTIVE
    }

    record Token(Type type, int start, int end) {
    }

    /** Zeichenkette oder Kommentar als Ganzes – zum Erkennen, ob eine Position darin liegt. */
    private record Literal(int start, int end, boolean closed) {
    }

    static final Set<String> JAVA_KEYWORDS = Set.of("abstract", "assert", "boolean", "break", "byte", "case",
            "catch", "char", "class", "const", "continue", "default", "do", "double", "else", "enum", "extends",
            "final", "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private", "protected", "public", "return", "short",
            "static", "strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try",
            "void", "volatile", "while", "var", "record", "yield", "sealed", "permits", "true", "false", "null");

    static final Set<String> GROOVY_KEYWORDS;

    static {
        Set<String> g = new java.util.HashSet<>(JAVA_KEYWORDS);
        g.addAll(Set.of("def", "in", "as", "trait"));
        g.removeAll(Set.of("record", "yield", "sealed", "permits", "goto", "const"));
        GROOVY_KEYWORDS = Set.copyOf(g);
    }

    private static final Pattern DIRECTIVE = Pattern.compile("//\\s*devtools:.*");
    private static final String[] OPERATORS = {"==~", "...", "..<", "..", "->", "?.", "*.", "::", "?:", "==", "!=",
        "<=", ">=", "&&", "||", "++", "--", "+=", "-=", "*=", "/=", "=~", "**", "<<"};

    private final String s;
    private final int n;
    private final boolean groovy;
    private final List<Token> tokens = new ArrayList<>();
    private final List<Literal> literals = new ArrayList<>();
    /** Code in GStrings als {@code [von, bis]} – zählt nicht als Zeichenkette. */
    private final List<int[]> islands = new ArrayList<>();
    private int i;

    private CodeLexer(String source, boolean groovy) {
        this.s = source;
        this.n = source.length();
        this.groovy = groovy;
    }

    static CodeLexer lex(String source, boolean groovy) {
        CodeLexer lexer = new CodeLexer(source, groovy);
        lexer.code(false);
        return lexer;
    }

    List<Token> tokens() {
        return tokens;
    }

    String text(Token t) {
        return s.substring(t.start(), t.end());
    }

    /** Ob die Position in einer Zeichenkette oder einem Kommentar liegt (nicht in eingebettetem GString-Code). */
    boolean inLiteral(int pos) {
        for (Literal l : literals) {
            if (pos > l.start() && (pos < l.end() || pos == l.end() && !l.closed())) {
                for (int[] island : islands) {
                    if (pos >= island[0] && pos <= island[1]) {
                        return false;
                    }
                }
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ Code

    /** Code bis zum Textende – in einer GString-Interpolation bis zur passenden schließenden Klammer. */
    private void code(boolean interpolation) {
        int depth = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\n') {
                add(Type.NEWLINE, i, ++i);
            } else if (c == ' ' || c == '\t' || c == '\r' || c == '\f') {
                i++;
            } else if (c == '/' && peek(1) == '/') {
                lineComment();
            } else if (c == '/' && peek(1) == '*') {
                blockComment();
            } else if (c == '"' || c == '\'') {
                string(c);
            } else if (groovy && c == '/' && regexAllowed()) {
                slashy();
            } else if (c == '@' && Character.isJavaIdentifierStart(peek(1))) {
                annotation();
            } else if (isDigit(c)) {
                number();
            } else if (Character.isJavaIdentifierStart(c)) {
                int start = i;
                i = identifierEnd(i, true);
                String word = s.substring(start, i);
                add((groovy ? GROOVY_KEYWORDS : JAVA_KEYWORDS).contains(word) ? Type.KEYWORD : Type.IDENT, start, i);
            } else {
                if (interpolation) {
                    if (c == '{') {
                        depth++;
                    } else if (c == '}') {
                        if (depth == 0) {
                            return;
                        }
                        depth--;
                    }
                }
                int len = operatorLength();
                add(Type.PUNCT, i, i + len);
                i += len;
            }
        }
    }

    private int operatorLength() {
        for (String op : OPERATORS) {
            if (s.startsWith(op, i)) {
                return op.length();
            }
        }
        return 1;
    }

    private int identifierEnd(int from, boolean dollar) {
        int j = from + 1;
        while (j < n && Character.isJavaIdentifierPart(s.charAt(j)) && (dollar || s.charAt(j) != '$')) {
            j++;
        }
        return j;
    }

    private void lineComment() {
        int start = i;
        int end = s.indexOf('\n', i);
        i = end < 0 ? n : end;
        boolean directive = groovy && DIRECTIVE.matcher(s.substring(start, i)).matches();
        add(directive ? Type.DIRECTIVE : Type.COMMENT, start, i);
        literals.add(new Literal(start, i, false));
    }

    private void blockComment() {
        int start = i;
        int end = s.indexOf("*/", i + 2);
        boolean closed = end >= 0;
        i = closed ? end + 2 : n;
        boolean doc = s.startsWith("/**", start) && !s.startsWith("/**/", start);
        add(doc ? Type.DOC_COMMENT : Type.COMMENT, start, i);
        literals.add(new Literal(start, i, closed));
    }

    private void annotation() {
        int start = i;
        i = identifierEnd(i + 1, true);
        while (i + 1 < n && s.charAt(i) == '.' && Character.isJavaIdentifierStart(s.charAt(i + 1))) {
            i = identifierEnd(i + 1, true);
        }
        add(Type.ANNOTATION, start, i);
    }

    private void number() {
        int start = i;
        if (s.charAt(i) == '0' && (peek(1) == 'x' || peek(1) == 'X' || peek(1) == 'b' || peek(1) == 'B')) {
            i += 2;
            while (i < n && (Character.digit(s.charAt(i), 16) >= 0 || s.charAt(i) == '_')) {
                i++;
            }
        } else {
            digits();
            if (i + 1 < n && s.charAt(i) == '.' && isDigit(s.charAt(i + 1))) {
                i++;
                digits();
            }
            if (i < n && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                int save = i++;
                if (i < n && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
                    i++;
                }
                if (i < n && isDigit(s.charAt(i))) {
                    digits();
                } else {
                    i = save;
                }
            }
        }
        if (i < n && "lLfFdDgGiI".indexOf(s.charAt(i)) >= 0
                && !(i + 1 < n && Character.isJavaIdentifierPart(s.charAt(i + 1)))) {
            i++;
        }
        add(Type.NUMBER, start, i);
    }

    private void digits() {
        while (i < n && (isDigit(s.charAt(i)) || s.charAt(i) == '_')) {
            i++;
        }
    }

    /** Slashy-String {@code /regex/} statt Division: nur dort, wo kein Operand davor steht. */
    private boolean regexAllowed() {
        Token t = null;
        for (int k = tokens.size() - 1; k >= 0; k--) {
            Type type = tokens.get(k).type();
            if (type != Type.COMMENT && type != Type.DOC_COMMENT && type != Type.DIRECTIVE) {
                t = tokens.get(k);
                break;
            }
        }
        if (t == null || t.type() == Type.NEWLINE) {
            return true;
        }
        String text = text(t);
        return switch (t.type()) {
            case KEYWORD -> !Set.of("this", "super", "true", "false", "null").contains(text);
            case PUNCT -> !Set.of(")", "]", "}", "++", "--").contains(text);
            default -> false;
        };
    }

    // ------------------------------------------------------------------ Zeichenketten

    private void string(char quote) {
        int start = i;
        boolean triple = i + 2 < n && s.charAt(i + 1) == quote && s.charAt(i + 2) == quote;
        i += triple ? 3 : 1;
        literal(start, quote, triple, groovy && quote == '"');
    }

    private void slashy() {
        int start = i++;
        literal(start, '/', false, true);
    }

    /** Inhalt bis zum Ende der Zeichenkette; Segmente, Escapes und GString-Code als eigene Token. */
    private void literal(int start, char quote, boolean triple, boolean interpolate) {
        int segment = start;
        boolean closed = false;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\n' && !triple) {
                break;
            }
            if (c == '\\' && i + 1 < n) {
                addIf(Type.STRING, segment, i);
                int len = s.charAt(i + 1) == 'u' ? Math.min(6, n - i) : 2;
                add(Type.ESCAPE, i, i + len);
                i += len;
                segment = i;
                continue;
            }
            if (c == quote && (!triple || i + 2 < n && s.charAt(i + 1) == quote && s.charAt(i + 2) == quote)) {
                i += triple ? 3 : 1;
                closed = true;
                break;
            }
            if (interpolate && c == '$' && i + 1 < n) {
                char d = s.charAt(i + 1);
                if (d == '{') {
                    addIf(Type.STRING, segment, i);
                    add(Type.INTERPOLATION, i, i + 2);
                    i += 2;
                    int from = i;
                    code(true);
                    islands.add(new int[] {from, i});
                    if (i < n && s.charAt(i) == '}') {
                        add(Type.INTERPOLATION, i, i + 1);
                        i++;
                    }
                    segment = i;
                    continue;
                }
                if (Character.isJavaIdentifierStart(d) && d != '$') {
                    addIf(Type.STRING, segment, i);
                    add(Type.INTERPOLATION, i, i + 1);
                    int from = ++i;
                    path();
                    islands.add(new int[] {from, i});
                    segment = i;
                    continue;
                }
            }
            i++;
        }
        addIf(Type.STRING, segment, i);
        literals.add(new Literal(start, i, closed));
    }

    /** {@code $name.path} in einem GString. */
    private void path() {
        int start = i;
        i = identifierEnd(i, false);
        add(Type.IDENT, start, i);
        while (i + 1 < n && s.charAt(i) == '.' && Character.isJavaIdentifierStart(s.charAt(i + 1))
                && s.charAt(i + 1) != '$') {
            add(Type.PUNCT, i, i + 1);
            start = ++i;
            i = identifierEnd(i, false);
            add(Type.IDENT, start, i);
        }
    }

    // ------------------------------------------------------------------ Hilfen

    private void add(Type type, int start, int end) {
        tokens.add(new Token(type, start, end));
    }

    private void addIf(Type type, int start, int end) {
        if (end > start) {
            add(type, start, end);
        }
    }

    private char peek(int offset) {
        return i + offset < n ? s.charAt(i + offset) : '\0';
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
