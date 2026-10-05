package systems.grebe.devtools.mcp.modules.scripts.assist;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.modules.scripts.assist.CodeLexer.Token;
import systems.grebe.devtools.mcp.modules.scripts.assist.CodeLexer.Type;
import systems.grebe.devtools.mcp.modules.scripts.assist.Completion.Kind;

/**
 * Autovervollständigung für Groovy-Skripte, abgestimmt auf die DSL von {@code DevToolsScript}:
 *
 * <ul>
 *   <li>je nach Block die passenden Aufrufe: oben {@code module}/{@code tool}, in {@code module { }} {@code description},
 *       {@code setting} …, in {@code tool('…') { }} {@code param}, {@code readOnly}, {@code execute} …; an
 *       Argumentstellen Feldtypen ({@code SECRET} …), Parametertypen und Optionen ({@code required:} …);</li>
 *   <li>in {@code execute { args, cfg -> }}: Variablen, {@code progress}, {@code log}, Schlüsselwörter und Klassen
 *       (mit Import); nach {@code args.} die Parameter des Tools, nach {@code cfg.} die Einstellungen des Moduls;</li>
 *   <li>nach einem Punkt die Member des Typs davor – Typen aus Deklarationen, {@code new}, Literalen, Casts und
 *       Rückgabetypen ganzer Aufrufketten, aufgelöst über das Symbolmodell von javac ({@link JavaModel}), dazu die
 *       GDK-Methoden ({@code each}, {@code collect} …) und Groovy-Eigenschaften ({@code name} für {@code getName()}).</li>
 * </ul>
 *
 * Typen werden nur aus dem Quelltext abgeleitet (wie es ein Leser täte), nicht durch Ausführen oder Übersetzen.
 */
final class GroovyAssist {

    /** Standard-Imports von Groovy – Klassen daraus brauchen keinen Import. */
    static final List<String> DEFAULT_PACKAGES = List.of("java.lang", "java.util", "java.io", "java.net",
            "groovy.lang", "groovy.util");
    private static final Set<String> DEFAULT_CLASSES = Set.of("java.math.BigInteger", "java.math.BigDecimal");
    private static final List<String> GDK_CLASSES = List.of("org.codehaus.groovy.runtime.DefaultGroovyMethods",
            "org.codehaus.groovy.runtime.StringGroovyMethods", "org.codehaus.groovy.runtime.IOGroovyMethods",
            "org.codehaus.groovy.runtime.ResourceGroovyMethods");
    private static final String SCRIPT_CLASS = "systems.grebe.devtools.mcp.modules.scripts.DevToolsScript";
    private static final int CLASS_LIMIT = 150;
    private static final Pattern DIRECTIVE = Pattern.compile("^\\s*//\\s*devtools:\\s*(\\w*)$");
    private static final Set<String> HINTS = Set.of("readOnly", "destructive", "idempotent", "openWorld");
    private static final Set<String> CONTINUES = Set.of(",", "+", "-", "*", "/", "%", "=", "+=", "-=", "*=", "/=",
            "&&", "||", "?", ":", "?:", ".", "?.", "*.", "<<", "==", "!=", "<", ">", "<=", ">=", "=~", "==~");
    private static final Set<String> PRIMITIVES = Set.of("boolean", "byte", "char", "short", "int", "long", "float",
            "double");

    // ------------------------------------------------------------------ feste Vorschläge

    private static final List<Completion> TOP_ITEMS = List.of(
            dsl("module", " { … }", "module {\n    description '|'\n}",
                    "Modul-Angaben: name, description (Pflicht), instructions und setting für Einstellungen."),
            dsl("tool", "('name') { … }", "tool('|') {\n    description ''\n    execute { args ->\n        \n    }\n}",
                    "Ein Tool. Der Name (Kleinbuchstaben, Ziffern, '_') bekommt das Modul-Präfix: open_issues → "
                            + "jira_open_issues."),
            keyword("import"), keyword("def"), keyword("class"), keyword("static"));

    private static final List<Completion> MODULE_ITEMS = List.of(
            dsl("name", " 'Anzeigename'", "name '|'", "Anzeigename des Moduls (Standard: der Skriptname)."),
            dsl("description", " '…'", "description '|'",
                    "Pflicht: was die Tools können – erscheint in der Modulliste und beim LLM."),
            dsl("instructions", " '…'", "instructions '|'", "Optional: wann das LLM die Tools nutzen soll."),
            dsl("setting", " 'key', 'Beschriftung', TYP", "setting '|', '', STRING",
                    "Einstellungsfeld im Modul-Formular der App: Schlüssel, Beschriftung, Typ (STRING, SECRET, INT, "
                            + "BOOLEAN, URL, DIRECTORY …). Optionen: required: true, defaultValue: '…', help: '…', "
                            + "options: [...] (für ENUM). Im Code als cfg.key."),
            dsl("enabledByDefault", " false", "enabledByDefault false",
                    "Ob das Modul beim ersten Laden aktiv ist (Standard true)."));

    private static final String EXECUTE_DOC = "Code des Tools – läuft bei jedem Aufruf. args: Parameter, cfg: "
            + "Einstellungen (beide Map<String, Object>). Das Ergebnis geht an das LLM: Text bleibt Text, alles "
            + "andere wird JSON. Fehler: eine Exception werfen.";

    private static final List<Completion> TOOL_ITEMS = List.of(
            dsl("description", " '…'", "description '|'", "Pflicht: wann das LLM das Tool verwenden soll."),
            dsl("param", " 'name', Typ, 'Beschreibung'", "param '|', String, ''",
                    "Parameter: Name, Typ (String, Integer, Long, Double, Boolean, List, Map), Beschreibung. "
                            + "Optionen: required: false, options: [...] (erlaubte Werte). Im Code als args.name."),
            dsl("readOnly", " true", "readOnly true",
                    "Verändert nichts – Clients dürfen das Tool ohne Rückfrage ausführen."),
            dsl("destructive", " true", "destructive true",
                    "Kann Daten löschen oder überschreiben (nur bei nicht lesenden Tools bedeutsam)."),
            dsl("idempotent", " true", "idempotent true",
                    "Mehrfaches Ausführen mit denselben Argumenten wirkt wie einmal."),
            dsl("openWorld", " false", "openWorld false",
                    "false: arbeitet nur mit abgeschlossenen, lokalen Daten (Standard: offen, z.B. Netz)."),
            dsl("execute", " { args, cfg -> … }", "execute { args, cfg ->\n    |\n}", EXECUTE_DOC),
            dsl("execute", " { args -> … }", "execute { args ->\n    |\n}", EXECUTE_DOC));

    private static final List<Completion> CODE_KEYWORDS = keywords("def", "if", "else", "for", "while", "return",
            "new", "try", "catch", "finally", "throw", "switch", "case", "default", "break", "continue", "in", "as",
            "instanceof", "true", "false", "null", "this", "final", "assert");

    private static final List<Completion> SCRIPT_MEMBERS = List.of(
            Completion.of(Kind.METHOD, "progress").withTail(" \"…\"").withDetail("void").withTemplate("progress \"|\"")
                    .withPriority(40).withDoc("Zwischenstand an den Client melden (MCP-Progress), z.B. progress "
                            + "\"Lade Seite 2 …\". Ohne Anfrage des Clients wirkungslos."),
            Completion.of(Kind.FIELD, "log").withDetail("Logger").withPriority(40)
                    .withDoc("Logger des Skripts: log.info \"…\" – landet im Log der App."));

    private static final List<Completion> BOOLEANS = keywords("true", "false");

    private static final List<Completion> PARAM_TYPES = List.of(
            paramType("String", "string"), paramType("Integer", "integer"), paramType("Long", "integer"),
            paramType("Double", "number"), paramType("Boolean", "boolean"), paramType("List", "array"),
            paramType("Map", "object"));

    private static final List<Completion> SETTING_OPTIONS = List.of(
            option("required", ": true", "required: true", "Pflichtfeld – ohne Wert startet das Modul nicht."),
            option("defaultValue", ": '…'", "defaultValue: '|'", "Vorgabewert (als Text)."),
            option("help", ": '…'", "help: '|'", "Hilfetext unter dem Feld."),
            option("options", ": […]", "options: [|]", "Erlaubte Werte – für ENUM Pflicht."));

    private static final List<Completion> PARAM_OPTIONS = List.of(
            option("required", ": false", "required: false", "Optionaler Parameter (Standard: Pflicht)."),
            option("options", ": […]", "options: [|]", "Erlaubte Werte."));

    private static final Map<FieldType, String> FIELD_TYPE_DOCS = Map.of(
            FieldType.STRING, "Einzeiliger Text.",
            FieldType.SECRET, "Geheimnis (Token, Passwort) – maskiert angezeigt und verschlüsselt gespeichert.",
            FieldType.INT, "Ganzzahl (im Code Long).",
            FieldType.BOOLEAN, "Ja/Nein (im Code Boolean).",
            FieldType.URL, "http(s)-URL.",
            FieldType.DIRECTORY, "Einzelnes Verzeichnis mit Auswahldialog.",
            FieldType.DIRECTORY_LIST, "Liste von Verzeichnissen (im Code List<String>).",
            FieldType.ENUM, "Auswahl aus options: [...].",
            FieldType.STRING_LIST, "Freie Liste von Texten (im Code List<String>).");

    private static final List<Completion> FIELD_TYPES = FIELD_TYPE_DOCS.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(e -> Completion.of(Kind.CONSTANT, e.getKey().name()).withDetail("FieldType").withDoc(e.getValue())
                    .withPriority(10 - e.getKey().ordinal()))
            .toList();

    private final JavaModel model;
    private final ClassIndex classes;
    /** GDK-Methoden und ihre Vorschläge je Empfängertyp – nur unter der Sperre des Symbolmodells benutzt. */
    private List<ExecutableElement> gdk;
    private final Map<String, List<Completion>> gdkByType = new HashMap<>();

    GroovyAssist(JavaModel model, ClassIndex classes) {
        this.model = model;
        this.classes = classes;
    }

    CompletionResult complete(String text, int caret) {
        CodeLexer lexer = CodeLexer.lex(text, true);
        if (lexer.inLiteral(caret)) {
            return directive(text, caret);
        }
        int from = identifierStart(text, caret);
        if (from < caret && Character.isDigit(text.charAt(from))) {
            return CompletionResult.none(caret);
        }
        Src src = new Src(lexer, text);
        int limit = src.countBefore(from);
        Scan scan = scan(src, limit);
        int dot = src.dotBefore(from, limit);
        if (dot >= 0) {
            return members(src, scan, dot, from);
        }
        String typed = text.substring(from, caret);
        if (src.isKeyword(src.prevSig(limit), "import")) {
            return importNames(typed, from);
        }
        return statement(src, scan, limit, from, typed);
    }

    /** Ohne {@code $}: in GStrings beginnt mit {@code $} eine Interpolation. */
    static int identifierStart(String text, int caret) {
        int from = caret;
        while (from > 0 && Character.isJavaIdentifierPart(text.charAt(from - 1)) && text.charAt(from - 1) != '$') {
            from--;
        }
        return from;
    }

    /** {@code // devtools: c|} → Übersetzungsmodi. */
    private static CompletionResult directive(String text, int caret) {
        int lineStart = text.lastIndexOf('\n', caret - 1) + 1;
        Matcher m = DIRECTIVE.matcher(text.substring(lineStart, caret));
        if (!m.matches()) {
            return CompletionResult.none(caret);
        }
        return new CompletionResult(caret - m.group(1).length(), List.of(
                Completion.of(Kind.KEYWORD, "compileStatic").withPriority(3).withDoc("Ganzes Skript beim Übersetzen "
                        + "wie Java prüfen und statisch übersetzen – Fehler fallen schon beim Speichern auf."),
                Completion.of(Kind.KEYWORD, "typeChecked").withPriority(2).withDoc("Typen prüfen, Ausführung bleibt "
                        + "dynamisch."),
                Completion.of(Kind.KEYWORD, "dynamic").withPriority(1).withDoc("Dynamisches Groovy (Standard).")),
                false, false);
    }

    // ------------------------------------------------------------------ Anweisungen

    private CompletionResult statement(Src src, Scan scan, int limit, int from, String typed) {
        Statement st = statement(src, limit);
        List<Completion> out = new ArrayList<>();
        boolean incomplete = false;
        switch (context(scan)) {
            case MODULE -> {
                if (st.start()) {
                    out.addAll(MODULE_ITEMS);
                } else if ("setting".equals(st.command())) {
                    if (st.namedValue()) {
                        out.addAll("required".equals(st.namedKey()) ? BOOLEANS : List.of());
                    } else if (st.args() >= 2) {
                        if (st.args() == 2) {
                            out.addAll(FIELD_TYPES);
                        }
                        out.addAll(SETTING_OPTIONS);
                    }
                } else if ("enabledByDefault".equals(st.command())) {
                    out.addAll(BOOLEANS);
                }
            }
            case TOOL -> {
                if (st.start()) {
                    out.addAll(TOOL_ITEMS);
                } else if ("param".equals(st.command())) {
                    if (st.namedValue()) {
                        out.addAll("required".equals(st.namedKey()) ? BOOLEANS : List.of());
                    } else if (st.args() >= 1) {
                        if (st.args() == 1) {
                            out.addAll(PARAM_TYPES);
                        }
                        out.addAll(PARAM_OPTIONS);
                    }
                } else if (HINTS.contains(st.command()) && st.args() == 0) {
                    out.addAll(BOOLEANS);
                }
            }
            case TOP -> {
                if (st.start()) {
                    out.addAll(TOP_ITEMS);
                }
                incomplete = code(scan, typed, out);
            }
            case CODE -> incomplete = code(scan, typed, out);
        }
        return new CompletionResult(from, out, incomplete, false);
    }

    /** Variablen, Skript-Member, Schlüsselwörter und Klassen; {@code true}, wenn die Klassenliste gekürzt ist. */
    private boolean code(Scan scan, String typed, List<Completion> out) {
        Set<String> seen = new HashSet<>();
        for (Frame f : scan.stack) {
            if (f.implicitIt && seen.add("it")) {
                out.add(Completion.of(Kind.PARAMETER, "it").withDetail("Object").withPriority(50)
                        .withDoc("Parameter der Closure ohne eigene Parameterliste."));
            }
            for (int i = f.locals.size() - 1; i >= 0; i--) {
                Local l = f.locals.get(i);
                if (seen.add(l.name())) {
                    out.add(local(f, l));
                }
            }
        }
        out.addAll(SCRIPT_MEMBERS);
        out.addAll(CODE_KEYWORDS);
        return classNames(scan, typed, out);
    }

    private static Completion local(Frame f, Local l) {
        int index = f.kind == FrameKind.EXECUTE ? f.params.indexOf(l.name()) : -1;
        if (index == 0) {
            return Completion.of(Kind.PARAMETER, l.name()).withDetail("Map<String, Object>").withPriority(60)
                    .withDoc("Parameter des Tool-Aufrufs – " + l.name() + ".name liefert einen Wert.");
        }
        if (index == 1) {
            return Completion.of(Kind.PARAMETER, l.name()).withDetail("Map<String, Object>").withPriority(60)
                    .withDoc("Einstellungen des Moduls – " + l.name() + ".key liefert einen Wert.");
        }
        boolean param = f.params.contains(l.name());
        return Completion.of(param ? Kind.PARAMETER : Kind.VARIABLE, l.name())
                .withDetail(l.type() == null ? null : l.type()).withPriority(50);
    }

    private boolean classNames(Scan scan, String typed, List<Completion> out) {
        if (typed.isEmpty()) {
            return false;
        }
        if (!classes.ready()) {
            classes.startLoading();
            return true;
        }
        ClassIndex.Hits hits = classes.search(typed, CLASS_LIMIT);
        for (ClassIndex.Entry e : hits.entries()) {
            String qualified = e.qualifiedName();
            Completion c = Completion.of(Kind.CLASS, e.simpleName()).withTail(" (" + e.packageName() + ")")
                    .withPriority(-10);
            boolean known = DEFAULT_PACKAGES.contains(e.packageName()) || DEFAULT_CLASSES.contains(qualified)
                    || qualified.equals(scan.imports.get(e.simpleName())) || scan.starImports.contains(e.packageName());
            out.add(known ? c : c.withImport(qualified));
        }
        return hits.truncated();
    }

    /** Nach {@code import}: Klassen voll qualifiziert und Pakete. */
    private CompletionResult importNames(String typed, int from) {
        List<Completion> out = new ArrayList<>();
        if (!classes.ready()) {
            classes.startLoading();
            return new CompletionResult(from, out, true, false);
        }
        ClassIndex.Hits hits = classes.search(typed, CLASS_LIMIT);
        for (ClassIndex.Entry e : hits.entries()) {
            out.add(Completion.of(Kind.CLASS, e.simpleName()).withTail(" (" + e.packageName() + ")")
                    .withInsert(e.qualifiedName()));
        }
        return new CompletionResult(from, out, hits.truncated(), false);
    }

    private enum Context { TOP, MODULE, TOOL, CODE }

    private static Context context(Scan scan) {
        for (Frame f : scan.stack) {
            if (f.kind == FrameKind.EXECUTE) {
                return Context.CODE;
            }
        }
        return switch (scan.stack.peek().kind) {
            case TOP -> Context.TOP;
            case MODULE -> Context.MODULE;
            case TOOL -> Context.TOOL;
            default -> Context.CODE;
        };
    }

    /**
     * Die Anweisung, in der die Schreibmarke steht.
     *
     * @param command    erstes Wort der Anweisung bzw. Name des Aufrufs, in dessen Klammern sie steht
     * @param args       Anzahl der Kommas davor (= Index des aktuellen Arguments)
     * @param start      noch nichts in der Anweisung
     * @param namedValue direkt nach {@code name:} (Wert eines benannten Arguments)
     */
    private record Statement(String command, int args, boolean start, boolean namedValue, String namedKey) {
    }

    private static Statement statement(Src src, int limit) {
        int last = src.prevSig(limit);
        boolean namedValue = src.isPunct(last, ":");
        String namedKey = namedValue && src.prevSig(last) >= 0 ? src.text(src.prevSig(last)) : null;
        int depth = 0;
        int commas = 0;
        int k = limit - 1;
        scan:
        for (; k >= 0; k--) {
            Token t = src.token(k);
            if (src.comment(k)) {
                continue;
            }
            if (t.type() == Type.NEWLINE) {
                if (depth > 0) {
                    continue;
                }
                int p = src.prevSig(k);
                if (p >= 0 && src.token(p).type() == Type.PUNCT && CONTINUES.contains(src.text(p))) {
                    continue;
                }
                break;
            }
            if (t.type() != Type.PUNCT) {
                continue;
            }
            switch (src.text(k)) {
                case ")", "]", "}" -> depth++;
                case "(", "[" -> {
                    if (depth == 0) {
                        int callee = src.prevSig(k);
                        String command = src.token(callee) != null && src.token(callee).type() == Type.IDENT
                                ? src.text(callee) : null;
                        return new Statement(command, commas, false, namedValue, namedKey);
                    }
                    depth--;
                }
                case "{" -> {
                    if (depth == 0) {
                        break scan;
                    }
                    depth--;
                }
                case ";", "->" -> {
                    if (depth == 0) {
                        break scan;
                    }
                }
                case "," -> {
                    if (depth == 0) {
                        commas++;
                    }
                }
                default -> {
                }
            }
        }
        int first = src.nextSig(k, limit);
        if (first < 0) {
            return new Statement(null, 0, true, false, null);
        }
        String command = src.token(first).type() == Type.IDENT ? src.text(first) : null;
        return new Statement(command, commas, false, namedValue, namedKey);
    }

    // ------------------------------------------------------------------ Blockstruktur

    private enum FrameKind { TOP, MODULE, TOOL, EXECUTE, CLOSURE, BLOCK }

    private record Local(String name, String type, int init) {
    }

    private static final class Frame {
        final FrameKind kind;
        final String toolName;
        final int open;
        final List<String> params = new ArrayList<>();
        final List<Local> locals = new ArrayList<>();
        boolean implicitIt;

        Frame(FrameKind kind, String toolName, int open) {
            this.kind = kind;
            this.toolName = toolName;
            this.open = open;
        }
    }

    /** Offene Blöcke an der Schreibmarke (innerster zuerst) und Imports des Skripts. */
    private static final class Scan {
        final Deque<Frame> stack = new ArrayDeque<>();
        final Map<String, String> imports = new LinkedHashMap<>();
        final List<String> starImports = new ArrayList<>();

        Frame find(FrameKind kind) {
            for (Frame f : stack) {
                if (f.kind == kind) {
                    return f;
                }
            }
            return null;
        }

        Local local(String name) {
            for (Frame f : stack) {
                for (int i = f.locals.size() - 1; i >= 0; i--) {
                    if (f.locals.get(i).name().equals(name)) {
                        return f.locals.get(i);
                    }
                }
            }
            return null;
        }
    }

    private static Scan scan(Src src, int limit) {
        Scan scan = new Scan();
        scan.stack.push(new Frame(FrameKind.TOP, null, -1));
        for (int k = 0; k < src.size(); k++) {
            if (src.isKeyword(k, "import")) {
                imports(src, k, scan);
            }
        }
        for (int k = 0; k < limit; k++) {
            if (src.isPunct(k, "{")) {
                Frame f = open(src, k, scan.stack.peek());
                closureParams(src, k, f);
                scan.stack.push(f);
            } else if (src.isPunct(k, "}")) {
                if (scan.stack.size() > 1) {
                    scan.stack.pop();
                }
            } else {
                declaration(src, k, limit, scan.stack.peek());
            }
        }
        return scan;
    }

    private static void imports(Src src, int k, Scan scan) {
        int j = src.nextSig(k, src.size());
        if (j < 0 || src.isKeyword(j, "static")) {
            return;
        }
        StringBuilder name = new StringBuilder();
        String alias = null;
        for (; j < src.size() && src.token(j).type() != Type.NEWLINE && !src.isPunct(j, ";"); j++) {
            if (src.isKeyword(j, "as")) {
                int a = src.nextSig(j, src.size());
                alias = a < 0 ? null : src.text(a);
                break;
            }
            if (!src.comment(j)) {
                name.append(src.text(j));
            }
        }
        String n = name.toString();
        if (n.endsWith(".*")) {
            scan.starImports.add(n.substring(0, n.length() - 2));
        } else if (n.contains(".")) {
            scan.imports.put(alias != null ? alias : n.substring(n.lastIndexOf('.') + 1), n);
        }
    }

    private static Frame open(Src src, int k, Frame parent) {
        int b = src.prevSig(k);
        if (b < 0) {
            return new Frame(FrameKind.BLOCK, null, k);
        }
        Token before = src.token(b);
        String text = src.text(b);
        if (before.type() == Type.IDENT) {
            if (text.equals("module") && parent.kind == FrameKind.TOP) {
                return new Frame(FrameKind.MODULE, null, k);
            }
            if ((text.equals("execute") || text.equals("run")) && parent.kind == FrameKind.TOOL) {
                return new Frame(FrameKind.EXECUTE, null, k);
            }
            int bb = src.prevSig(b);
            if (bb >= 0 && src.token(bb).type() == Type.KEYWORD
                    && Set.of("class", "interface", "enum", "trait", "extends", "implements").contains(src.text(bb))) {
                return new Frame(FrameKind.BLOCK, null, k);
            }
            return new Frame(FrameKind.CLOSURE, null, k);
        }
        if (src.isPunct(b, ")")) {
            int open = src.matchBack(b, "(", ")");
            int callee = open < 0 ? -1 : src.prevSig(open);
            if (callee >= 0 && src.isIdent(callee, "tool") && parent.kind == FrameKind.TOP) {
                return new Frame(FrameKind.TOOL, src.firstString(open, b), k);
            }
            if (callee >= 0 && src.token(callee).type() == Type.KEYWORD) {
                return new Frame(FrameKind.BLOCK, null, k); // if/for/while/catch/switch …
            }
            if (callee >= 0 && src.token(callee).type() == Type.IDENT) {
                int decl = src.prevSig(callee);
                if (decl >= 0 && (src.token(decl).type() == Type.IDENT || src.isPunct(decl, ">")
                        || src.token(decl).type() == Type.KEYWORD && !src.isKeyword(decl, "new")
                        && !src.isKeyword(decl, "return"))) {
                    return new Frame(FrameKind.BLOCK, null, k); // Methoden-Deklaration
                }
            }
            return new Frame(FrameKind.CLOSURE, null, k);
        }
        if (before.type() == Type.KEYWORD) {
            return new Frame(FrameKind.BLOCK, null, k);
        }
        if (before.type() == Type.PUNCT && Set.of("(", ",", "=", ":", "[", "<<", "?:", "->", "?").contains(text)) {
            return new Frame(FrameKind.CLOSURE, null, k);
        }
        return new Frame(FrameKind.BLOCK, null, k);
    }

    /** {@code { a, String b ->} – sonst bei Closures das implizite {@code it}. */
    private static void closureParams(Src src, int k, Frame f) {
        if (f.kind != FrameKind.CLOSURE && f.kind != FrameKind.EXECUTE) {
            return;
        }
        List<Integer> segment = new ArrayList<>();
        List<List<Integer>> params = new ArrayList<>();
        int depth = 0;
        for (int j = k + 1; j < src.size(); j++) {
            Token t = src.token(j);
            if (t.type() == Type.NEWLINE || src.comment(j)) {
                continue;
            }
            String text = src.text(j);
            if (t.type() == Type.PUNCT && text.equals("->") && depth == 0) {
                params.add(segment);
                for (List<Integer> p : params) {
                    if (!p.isEmpty() && src.token(p.getLast()).type() == Type.IDENT) {
                        String name = src.text(p.getLast());
                        String type = p.size() > 1 ? src.source(p.getFirst(), p.get(p.size() - 2)) : null;
                        f.params.add(name);
                        f.locals.add(new Local(name, type, -1));
                    }
                }
                return;
            }
            if (t.type() == Type.PUNCT && text.equals(",") && depth == 0) {
                params.add(segment);
                segment = new ArrayList<>();
                continue;
            }
            if (t.type() == Type.PUNCT && text.equals("<")) {
                depth++;
            } else if (t.type() == Type.PUNCT && text.equals(">")) {
                depth--;
            } else if (!(t.type() == Type.IDENT || t.type() == Type.KEYWORD && PRIMITIVES.contains(text)
                    || t.type() == Type.PUNCT && Set.of(".", "[", "]", "?").contains(text))) {
                break;
            }
            segment.add(j);
        }
        f.implicitIt = f.kind == FrameKind.CLOSURE;
    }

    /** {@code def x = …}, {@code String x = …}, {@code List<String> x}, {@code for (x in …)}. */
    private static void declaration(Src src, int k, int limit, Frame frame) {
        Token t = src.token(k);
        if (t.type() == Type.KEYWORD && Set.of("def", "var", "final").contains(src.text(k))) {
            int name = src.nextSig(k, limit);
            if (name >= 0 && src.token(name).type() == Type.IDENT && !src.isPunct(src.nextSig(name, src.size()), "(")) {
                int after = src.nextSig(name, src.size());
                frame.locals.add(new Local(src.text(name), null, src.isPunct(after, "=") ? after + 1 : -1));
            }
            return;
        }
        boolean primitive = t.type() == Type.KEYWORD && PRIMITIVES.contains(src.text(k));
        if (!(primitive || t.type() == Type.IDENT && Character.isUpperCase(src.text(k).charAt(0)))) {
            if (t.type() == Type.IDENT && src.isKeyword(src.nextSig(k, src.size()), "in")
                    && src.isPunct(src.prevSig(k), "(") && src.isKeyword(src.prevSig(src.prevSig(k)), "for")) {
                frame.locals.add(new Local(src.text(k), null, -1));
            }
            return;
        }
        int p = src.prevSig(k);
        if (p >= 0 && (src.isPunct(p, ".") || src.isPunct(p, "?.") || src.isKeyword(p, "new")
                || src.isKeyword(p, "as") || src.isKeyword(p, "instanceof") || src.token(p).type() == Type.IDENT)) {
            return;
        }
        int j = k + 1;
        while (j < src.size() && src.isPunct(j, ".") && j + 1 < src.size() && src.token(j + 1).type() == Type.IDENT) {
            j += 2; // qualifizierter Typ
        }
        if (src.isPunct(j, "<")) {
            int close = src.matchForward(j, "<", ">");
            if (close < 0) {
                return;
            }
            j = close + 1;
        }
        while (src.isPunct(j, "[") && src.isPunct(j + 1, "]")) {
            j += 2;
        }
        if (j >= limit || src.token(j).type() != Type.IDENT) {
            return;
        }
        int after = src.nextSig(j, src.size());
        boolean ends = after < 0 || src.token(j + 1) != null && src.token(j + 1).type() == Type.NEWLINE
                || src.isPunct(after, "=") || src.isPunct(after, ";") || src.isPunct(after, ",")
                || src.isPunct(after, ")") || src.isPunct(after, ":") || src.isKeyword(after, "in");
        if (ends) {
            frame.locals.add(new Local(src.text(j), src.source(k, j - 1), src.isPunct(after, "=") ? after + 1 : -1));
        }
    }

    // ------------------------------------------------------------------ Member nach dem Punkt

    /** Teil einer Aufrufkette links vom Punkt. */
    private sealed interface Part permits Name, Call, Index, New, Cast, Literal, This {
    }

    private record Name(String name) implements Part {
    }

    private record Call(String name) implements Part {
    }

    private record Index() implements Part {
    }

    private record New(String type) implements Part {
    }

    private record Cast(String type) implements Part {
    }

    /** Literal mit dem Typ, den Groovy dafür verwendet. */
    private record Literal(String type) implements Part {
    }

    private record This() implements Part {
    }

    private CompletionResult members(Src src, Scan scan, int dot, int from) {
        int end = src.prevSig(dot);
        List<Part> chain = end < 0 ? null : chain(src, end, 0);
        if (chain == null || chain.isEmpty()) {
            return CompletionResult.none(from);
        }
        List<Completion> items = model.symbols((elements, types) -> {
            Resolver r = new Resolver(src, scan, elements, types);
            Receiver receiver = r.receiver(chain, 0);
            return receiver == null ? List.<Completion>of() : r.list(receiver);
        });
        if (items == null) { // ohne JDK: wenigstens args./cfg.
            Resolver r = new Resolver(src, scan, null, null);
            items = r.pseudoKeysOnly(chain);
        }
        return new CompletionResult(from, items, false, false);
    }

    /**
     * Kette rückwärts ab {@code end} lesen ({@code a.b(x).c[0]} …) – Teile in Lesereihenfolge oder {@code null}.
     *
     * @param lower erstes Token, das noch dazugehören darf
     */
    private static List<Part> chain(Src src, int end, int lower) {
        List<Part> parts = new ArrayList<>();
        int k = end;
        while (k >= lower) {
            Token t = src.token(k);
            String text = src.text(k);
            if (src.isPunct(k, ")")) {
                int open = src.matchBack(k, "(", ")");
                if (open < lower) {
                    return null;
                }
                int callee = open - 1;
                if (src.isPunct(callee, ">")) { // new ArrayList<String>()
                    int lt = src.matchBack(callee, "<", ">");
                    if (lt - 1 >= lower && src.token(lt - 1).type() == Type.IDENT
                            && src.isKeyword(src.prevSig(lt - 1), "new")) {
                        parts.add(new New(src.source(lt - 1, callee)));
                        break;
                    }
                    return null;
                }
                if (callee >= lower && src.token(callee).type() == Type.IDENT) {
                    int q = callee;
                    StringBuilder qualified = new StringBuilder(src.text(callee));
                    while (q - 2 >= lower && src.isPunct(q - 1, ".") && src.token(q - 2).type() == Type.IDENT) {
                        q -= 2;
                        qualified.insert(0, src.text(q) + ".");
                    }
                    if (q - 1 >= lower && src.isKeyword(src.prevSig(q), "new")) {
                        parts.add(new New(qualified.toString()));
                        break;
                    }
                    parts.add(new Call(src.text(callee)));
                    k = callee - 1;
                } else {
                    Part inner = paren(src, open + 1, k - 1);
                    if (inner == null) {
                        return null;
                    }
                    parts.add(inner);
                    break;
                }
            } else if (src.isPunct(k, "]")) {
                int open = src.matchBack(k, "[", "]");
                if (open < lower) {
                    return null;
                }
                Token before = open - 1 >= lower ? src.token(open - 1) : null;
                if (before != null && before.end() == src.token(open).start() && (before.type() == Type.IDENT
                        || src.isPunct(open - 1, ")") || src.isPunct(open - 1, "]"))) {
                    parts.add(new Index());
                    k = open - 1;
                    continue;
                }
                boolean map = k == open + 2 && src.isPunct(open + 1, ":") || src.hasTopLevel(open + 1, k, ":");
                parts.add(new Literal(map ? "java.util.LinkedHashMap" : "java.util.ArrayList"));
                break;
            } else if (t.type() == Type.IDENT) {
                parts.add(new Name(text));
                k--;
            } else if (t.type() == Type.STRING || t.type() == Type.INTERPOLATION && text.equals("}")) {
                parts.add(new Literal("java.lang.String"));
                break;
            } else if (t.type() == Type.NUMBER) {
                parts.add(new Literal(numberType(text)));
                break;
            } else if (src.isKeyword(k, "this")) {
                parts.add(new This());
                break;
            } else if (t.type() == Type.KEYWORD && (text.equals("true") || text.equals("false"))) {
                parts.add(new Literal("java.lang.Boolean"));
                break;
            } else {
                return null;
            }
            // weiter nach links nur über einen Punkt
            if (k >= lower && (src.isPunct(k, ".") || src.isPunct(k, "?.") || src.isPunct(k, "*."))) {
                k--;
                while (k >= lower && src.token(k).type() == Type.NEWLINE) {
                    k--;
                }
                continue;
            }
            break;
        }
        Collections.reverse(parts);
        return parts;
    }

    /** {@code (x as Typ)}, {@code ((Typ) x)} oder ein geklammerter Ausdruck. */
    private static Part paren(Src src, int from, int to) {
        if (from > to) {
            return null;
        }
        for (int k = to; k > from; k--) {
            if (src.isKeyword(k, "as") && k < to) {
                return new Cast(src.source(k + 1, to));
            }
        }
        if (src.isPunct(from, "(")) {
            int close = src.matchForward(from, "(", ")");
            if (close > from + 1 && close < to) {
                return new Cast(src.source(from + 1, close - 1));
            }
        }
        List<Part> inner = chain(src, to, from);
        return inner != null && inner.size() == 1 ? inner.getFirst() : null;
    }

    private static String numberType(String literal) {
        String l = literal.toLowerCase(java.util.Locale.ROOT);
        if (l.endsWith("l")) {
            return "java.lang.Long";
        }
        if (l.endsWith("g")) {
            return l.contains(".") ? "java.math.BigDecimal" : "java.math.BigInteger";
        }
        if (l.endsWith("d")) {
            return "java.lang.Double";
        }
        if (l.endsWith("f")) {
            return "java.lang.Float";
        }
        return l.contains(".") || l.contains("e") && !l.startsWith("0x") ? "java.math.BigDecimal" : "java.lang.Integer";
    }

    private enum Pseudo { NONE, ARGS, CFG }

    /** Was links vom Punkt steht: ein Wert (Instanz), eine Klasse (statisch), ein Paket oder args/cfg. */
    private record Receiver(TypeMirror type, boolean statics, String packageName, Pseudo pseudo) {
    }

    /** Parameter eines Tools bzw. Einstellung des Moduls: Name, Typ im Code, Beschreibung. */
    private record Key(String name, String type, String javaType, String description) {
    }

    /** Typauflösung über das Symbolmodell – lebt nur innerhalb von {@link JavaModel#symbols}. */
    private final class Resolver {
        private final Src src;
        private final Scan scan;
        private final Elements el;
        private final Types ty;

        Resolver(Src src, Scan scan, Elements el, Types ty) {
            this.src = src;
            this.scan = scan;
            this.el = el;
            this.ty = ty;
        }

        Receiver receiver(List<Part> parts, int depth) {
            Receiver r = primary(parts.getFirst(), depth);
            for (int i = 1; i < parts.size() && r != null; i++) {
                r = select(r, parts.get(i));
            }
            return r;
        }

        private Receiver primary(Part part, int depth) {
            return switch (part) {
                case Name(String name) -> name(name, depth);
                case Call c -> instance(object());
                case New(String type) -> instance(type(type));
                case Cast(String type) -> instance(type(type));
                case Literal(String type) -> instance(type(type));
                case This t -> instance(type(SCRIPT_CLASS));
                case Index i -> null;
            };
        }

        private Receiver name(String name, int depth) {
            Frame exec = scan.find(FrameKind.EXECUTE);
            Local local = scan.local(name);
            if (exec != null && !exec.params.isEmpty() && exec.params.getFirst().equals(name)) {
                return new Receiver(stringObjectMap(), false, null, Pseudo.ARGS);
            }
            if (exec != null && exec.params.size() > 1 && exec.params.get(1).equals(name)) {
                return new Receiver(stringObjectMap(), false, null, Pseudo.CFG);
            }
            if (local != null) {
                if (local.type() != null) {
                    return instance(type(local.type()));
                }
                if (local.init() >= 0 && depth < 4) {
                    int end = src.statementEnd(local.init());
                    List<Part> init = end > local.init() ? chain(src, end - 1, local.init()) : null;
                    if (init != null && !init.isEmpty()) {
                        Receiver r = receiver(init, depth + 1);
                        if (r != null) {
                            return r;
                        }
                    }
                    if (src.isPunct(local.init(), "{")) {
                        return instance(type("groovy.lang.Closure"));
                    }
                }
                return instance(object());
            }
            if (name.equals("log")) {
                return instance(type("org.slf4j.Logger"));
            }
            if (name.equals("it")) {
                return instance(object());
            }
            TypeElement type = classByName(name);
            if (type != null) {
                return new Receiver(ty.erasure(type.asType()), true, null, Pseudo.NONE);
            }
            if (Character.isLowerCase(name.charAt(0)) && packageExists(name)) {
                return new Receiver(null, false, name, Pseudo.NONE);
            }
            return instance(object());
        }

        private Receiver select(Receiver r, Part part) {
            if (r.packageName() != null) {
                if (part instanceof Name(String name)) {
                    String q = r.packageName() + "." + name;
                    TypeElement te = el.getTypeElement(q);
                    if (te != null) {
                        return new Receiver(ty.erasure(te.asType()), true, null, Pseudo.NONE);
                    }
                    return packageExists(q) ? new Receiver(null, false, q, Pseudo.NONE) : null;
                }
                return null;
            }
            if (r.pseudo() != Pseudo.NONE && part instanceof Name(String name)) {
                for (Key k : keys(r.pseudo())) {
                    if (k.name().equals(name)) {
                        return instance(type(k.javaType()));
                    }
                }
                return instance(object());
            }
            TypeMirror t = r.type();
            if (t == null) {
                return null;
            }
            if (r.statics()) {
                TypeElement te = (TypeElement) ty.asElement(t);
                for (Element e : el.getAllMembers(te)) {
                    if (!e.getModifiers().contains(Modifier.STATIC) && !e.getKind().isInterface()
                            && !e.getKind().isClass()) {
                        continue;
                    }
                    String n = e.getSimpleName().toString();
                    if (part instanceof Name(String name) && n.equals(name)) {
                        if (e instanceof TypeElement nested) {
                            return new Receiver(ty.erasure(nested.asType()), true, null, Pseudo.NONE);
                        }
                        if (e instanceof VariableElement) {
                            return instance(e.asType());
                        }
                    }
                    if (part instanceof Call(String name) && n.equals(name) && e instanceof ExecutableElement m) {
                        return instance(m.getReturnType());
                    }
                }
                return null;
            }
            return switch (part) {
                case Name(String name) -> property(t, name);
                case Call(String name) -> call(t, name);
                case Index i -> index(t);
                default -> null;
            };
        }

        private Receiver property(TypeMirror t, String name) {
            if (isAssignable(t, "java.util.Map")) {
                return instance(typeArgument(t, "java.util.Map", 1));
            }
            for (Element e : members(t)) {
                if (e instanceof ExecutableElement m && name.equals(Members.property(m))) {
                    return instance(returnType(t, m));
                }
                if (e.getKind() == ElementKind.FIELD && e.getSimpleName().contentEquals(name)) {
                    return instance(memberType(t, e));
                }
            }
            return instance(object());
        }

        private Receiver call(TypeMirror t, String name) {
            for (Element e : members(t)) {
                if (e instanceof ExecutableElement m && m.getKind() == ElementKind.METHOD
                        && m.getSimpleName().contentEquals(name)) {
                    return instance(returnType(t, m));
                }
            }
            ExecutableElement best = null;
            for (ExecutableElement m : gdkMethods()) {
                if (m.getSimpleName().contentEquals(name) && selfMatches(t, m)) {
                    // die speziellste Überladung (Self-Typ nicht Object) bevorzugen
                    if (best == null || isObject(best.getParameters().getFirst().asType())) {
                        best = m;
                    }
                }
            }
            if (best == null) {
                return instance(object());
            }
            TypeMirror ret = best.getReturnType();
            TypeMirror self = best.getParameters().getFirst().asType();
            if (ty.isSameType(ty.erasure(ret), ty.erasure(self))) {
                return instance(t); // findAll, sort, tap … liefern den Empfängertyp
            }
            return instance(ty.erasure(ret));
        }

        private Receiver index(TypeMirror t) {
            if (t.getKind() == TypeKind.ARRAY) {
                return instance(((ArrayType) t).getComponentType());
            }
            if (isAssignable(t, "java.util.Map")) {
                return instance(typeArgument(t, "java.util.Map", 1));
            }
            if (isAssignable(t, "java.util.List")) {
                return instance(typeArgument(t, "java.util.List", 0));
            }
            if (isAssignable(t, "java.lang.CharSequence")) {
                return instance(type("java.lang.String"));
            }
            return instance(object());
        }

        // -------------------------------------------------------------- Liste

        List<Completion> list(Receiver r) {
            if (r.packageName() != null) {
                return packageMembers(r.packageName());
            }
            if (r.pseudo() != Pseudo.NONE) {
                List<Completion> out = new ArrayList<>();
                for (Key k : keys(r.pseudo())) {
                    out.add(Completion.of(Kind.PROPERTY, k.name()).withDetail(k.type()).withPriority(30)
                            .withDoc(k.description().isEmpty() ? null : k.description()));
                }
                out.addAll(instanceMembers(r.type()));
                return out;
            }
            return r.statics() ? staticMembers((TypeElement) ty.asElement(r.type())) : instanceMembers(r.type());
        }

        /** Ohne javac: nur die Schlüssel von args/cfg. */
        List<Completion> pseudoKeysOnly(List<Part> chain) {
            if (chain.size() != 1 || !(chain.getFirst() instanceof Name(String name))) {
                return List.of();
            }
            Frame exec = scan.find(FrameKind.EXECUTE);
            Pseudo p = exec == null ? Pseudo.NONE : exec.params.indexOf(name) == 0 ? Pseudo.ARGS
                    : exec.params.indexOf(name) == 1 ? Pseudo.CFG : Pseudo.NONE;
            return p == Pseudo.NONE ? List.of() : keys(p).stream().map(k -> Completion.of(Kind.PROPERTY, k.name())
                    .withDetail(k.type()).withDoc(k.description())).toList();
        }

        private List<Completion> instanceMembers(TypeMirror type) {
            List<Completion> out = new ArrayList<>();
            TypeMirror t = type;
            if (t.getKind() == TypeKind.ARRAY) {
                out.add(Completion.of(Kind.FIELD, "length").withDetail("int").withPriority(5));
                t = object();
            }
            DeclaredType declared = t instanceof DeclaredType d ? d : null;
            Set<String> seen = new HashSet<>();
            for (Element e : members(t)) {
                Set<Modifier> mods = e.getModifiers();
                if (!mods.contains(Modifier.PUBLIC) || mods.contains(Modifier.STATIC)) {
                    continue;
                }
                if (e instanceof ExecutableElement m && m.getKind() == ElementKind.METHOD) {
                    ExecutableType asMember = asMember(declared, m);
                    Completion c = Members.method(m, asMember, 0, true);
                    if (Members.fromObject(m)) {
                        c = c.withPriority(-3);
                    }
                    if (seen.add(c.label() + c.tail())) {
                        out.add(c);
                    }
                    String property = Members.property(m);
                    if (property != null && seen.add("." + property)) {
                        TypeMirror ret = asMember != null ? asMember.getReturnType() : m.getReturnType();
                        out.add(Completion.of(Kind.PROPERTY, property).withDetail(Members.type(ret)).withPriority(1));
                    }
                } else if (e.getKind() == ElementKind.FIELD) {
                    out.add(Members.field((VariableElement) e, memberType(t, e)));
                }
            }
            for (Completion c : gdk(type)) {
                if (seen.add(c.label() + c.tail())) {
                    out.add(c);
                }
            }
            return out;
        }

        private List<Completion> staticMembers(TypeElement te) {
            List<Completion> out = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (Element e : el.getAllMembers(te)) {
                Set<Modifier> mods = e.getModifiers();
                if (!mods.contains(Modifier.PUBLIC)) {
                    continue;
                }
                if (e instanceof TypeElement nested) {
                    out.add(Members.type(nested).withTail(null));
                } else if (!mods.contains(Modifier.STATIC)) {
                    continue;
                } else if (e instanceof ExecutableElement m && m.getKind() == ElementKind.METHOD) {
                    Completion c = Members.method(m, null, 0, true);
                    if (seen.add(c.label() + c.tail())) {
                        out.add(c);
                    }
                } else if (e instanceof VariableElement v) {
                    out.add(Members.field(v, v.asType()).withPriority(1));
                }
            }
            out.add(Completion.of(Kind.KEYWORD, "class").withDetail("Class"));
            return out;
        }

        private List<Completion> packageMembers(String pkg) {
            List<Completion> out = new ArrayList<>();
            classes.startLoading();
            for (ClassIndex.Entry e : classes.entries()) {
                if (e.packageName().equals(pkg)) {
                    out.add(Completion.of(Kind.CLASS, e.simpleName()));
                }
            }
            Set<String> sub = new HashSet<>();
            for (String p : classes.packages()) {
                if (p.startsWith(pkg + ".")) {
                    String rest = p.substring(pkg.length() + 1);
                    int dot = rest.indexOf('.');
                    sub.add(dot < 0 ? rest : rest.substring(0, dot));
                }
            }
            sub.forEach(s -> out.add(Completion.of(Kind.PACKAGE, s).withPriority(1)));
            return out;
        }

        /** GDK-Methoden, deren Self-Parameter den Empfänger annimmt – ohne diesen Parameter angezeigt. */
        private List<Completion> gdk(TypeMirror t) {
            TypeMirror erased = ty.erasure(t);
            return gdkByType.computeIfAbsent(erased.toString(), key -> {
                List<Completion> out = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                for (ExecutableElement m : gdkMethods()) {
                    if (selfMatches(erased, m)) {
                        boolean onObject = ty.erasure(m.getParameters().getFirst().asType()).toString()
                                .equals("java.lang.Object");
                        Completion c = Members.method(m, null, 1, true).withPriority(onObject ? -4 : -2);
                        if (seen.add(c.label() + c.tail())) {
                            out.add(c);
                        }
                    }
                }
                return List.copyOf(out);
            });
        }

        private List<ExecutableElement> gdkMethods() {
            if (gdk == null) {
                List<ExecutableElement> out = new ArrayList<>();
                for (String name : GDK_CLASSES) {
                    TypeElement te = el.getTypeElement(name);
                    if (te == null) {
                        continue;
                    }
                    for (Element e : te.getEnclosedElements()) {
                        if (e instanceof ExecutableElement m && m.getKind() == ElementKind.METHOD
                                && m.getModifiers().contains(Modifier.PUBLIC)
                                && m.getModifiers().contains(Modifier.STATIC) && !m.getParameters().isEmpty()
                                && !el.isDeprecated(m)) {
                            out.add(m);
                        }
                    }
                }
                gdk = List.copyOf(out);
            }
            return gdk;
        }

        private boolean selfMatches(TypeMirror receiver, ExecutableElement m) {
            TypeMirror self = ty.erasure(m.getParameters().getFirst().asType());
            if (self.getKind() == TypeKind.ARRAY && receiver.getKind() != TypeKind.ARRAY
                    || self.getKind().isPrimitive()) {
                return false;
            }
            return ty.isAssignable(ty.erasure(receiver), self);
        }

        // -------------------------------------------------------------- Schlüssel von args/cfg

        private List<Key> keys(Pseudo p) {
            if (p == Pseudo.ARGS) {
                Frame tool = scan.find(FrameKind.TOOL);
                return tool == null ? List.of() : toolParams(src, tool.open);
            }
            return settings(src);
        }

        // -------------------------------------------------------------- Typen

        private List<? extends Element> members(TypeMirror t) {
            Element e = ty.asElement(t);
            return e instanceof TypeElement te ? el.getAllMembers(te) : List.of();
        }

        private Receiver instance(TypeMirror t) {
            TypeMirror n = normalize(t);
            return n == null ? null : new Receiver(n, false, null, Pseudo.NONE);
        }

        /** Für weitere Member: Typvariablen und Wildcards auflösen, primitive Typen in die Hülle (Groovy boxt). */
        private TypeMirror normalize(TypeMirror t) {
            if (t == null) {
                return null;
            }
            return switch (t.getKind()) {
                case DECLARED, ARRAY -> t;
                case TYPEVAR -> normalize(((TypeVariable) t).getUpperBound());
                case WILDCARD -> {
                    TypeMirror bound = ((WildcardType) t).getExtendsBound();
                    yield bound == null ? object() : normalize(bound);
                }
                case INTERSECTION, UNION -> object();
                case VOID, NONE, NULL, ERROR, EXECUTABLE, PACKAGE, MODULE, OTHER -> null;
                default -> t.getKind().isPrimitive() ? ty.boxedClass((PrimitiveType) t).asType() : null;
            };
        }

        private ExecutableType asMember(DeclaredType declared, ExecutableElement m) {
            if (declared == null) {
                return null;
            }
            try {
                return (ExecutableType) ty.asMemberOf(declared, m);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        private TypeMirror returnType(TypeMirror receiver, ExecutableElement m) {
            ExecutableType asMember = asMember(receiver instanceof DeclaredType d ? d : null, m);
            return asMember != null ? asMember.getReturnType() : m.getReturnType();
        }

        private TypeMirror memberType(TypeMirror receiver, Element e) {
            if (receiver instanceof DeclaredType d) {
                try {
                    return ty.asMemberOf(d, e);
                } catch (IllegalArgumentException ex) {
                    // unten
                }
            }
            return e.asType();
        }

        private boolean isAssignable(TypeMirror t, String className) {
            TypeElement te = el.getTypeElement(className);
            return te != null && t != null && ty.isAssignable(ty.erasure(t), ty.erasure(te.asType()));
        }

        private boolean isObject(TypeMirror t) {
            return t instanceof DeclaredType d && ((TypeElement) d.asElement()).getQualifiedName()
                    .contentEquals("java.lang.Object");
        }

        /** Typargument {@code index} von {@code className} am Typ {@code t} (z.B. Wert-Typ einer Map). */
        private TypeMirror typeArgument(TypeMirror t, String className, int index) {
            TypeElement target = el.getTypeElement(className);
            if (target == null || !(t instanceof DeclaredType)) {
                return object();
            }
            for (TypeMirror s = t; s instanceof DeclaredType d; ) {
                if (((TypeElement) d.asElement()).getQualifiedName().contentEquals(className)) {
                    return d.getTypeArguments().size() > index ? d.getTypeArguments().get(index) : object();
                }
                TypeMirror next = null;
                for (TypeMirror sup : ty.directSupertypes(s)) {
                    if (ty.isAssignable(ty.erasure(sup), ty.erasure(target.asType()))) {
                        next = sup;
                        break;
                    }
                }
                s = next;
            }
            return object();
        }

        private TypeMirror object() {
            return el.getTypeElement("java.lang.Object").asType();
        }

        private DeclaredType stringObjectMap() {
            return ty.getDeclaredType(el.getTypeElement("java.util.Map"),
                    el.getTypeElement("java.lang.String").asType(), object());
        }

        /** Typ aus Quelltext: {@code List<String>}, {@code int[]}, {@code java.time.Instant}, {@code def}. */
        TypeMirror type(String text) {
            String t = text.strip();
            if (t.startsWith("?")) {
                String bound = t.substring(1).strip();
                if (bound.startsWith("extends ")) {
                    return ty.getWildcardType(type(bound.substring(8)), null);
                }
                if (bound.startsWith("super ")) {
                    return ty.getWildcardType(null, type(bound.substring(6)));
                }
                return ty.getWildcardType(null, null);
            }
            int dims = 0;
            while (t.endsWith("[]")) {
                dims++;
                t = t.substring(0, t.length() - 2).strip();
            }
            List<String> args = List.of();
            int lt = t.indexOf('<');
            if (lt > 0 && t.endsWith(">")) {
                args = splitTopLevel(t.substring(lt + 1, t.length() - 1));
                t = t.substring(0, lt).strip();
            }
            TypeMirror result;
            if (PRIMITIVES.contains(t)) {
                result = ty.getPrimitiveType(TypeKind.valueOf(t.toUpperCase(java.util.Locale.ROOT)));
            } else if (t.equals("def") || t.equals("var") || t.isEmpty()) {
                result = object();
            } else {
                TypeElement te = classByName(t);
                if (te == null) {
                    result = object();
                } else if (!args.isEmpty() && args.size() == te.getTypeParameters().size()) {
                    TypeMirror[] a = args.stream().map(this::type)
                            .map(m -> m == null || m.getKind().isPrimitive() ? object() : m).toArray(TypeMirror[]::new);
                    result = ty.getDeclaredType(te, a);
                } else {
                    result = ty.erasure(te.asType());
                }
            }
            for (int i = 0; i < dims; i++) {
                result = ty.getArrayType(result);
            }
            return result;
        }

        /** Einfacher oder qualifizierter Klassenname über Imports, Standard-Imports und den Klassenindex. */
        TypeElement classByName(String name) {
            if (name.contains(".")) {
                TypeElement te = el.getTypeElement(name);
                if (te != null) {
                    return te;
                }
                int dot = name.indexOf('.');
                TypeElement outer = classByName(name.substring(0, dot));
                return outer == null ? null : nested(outer, name.substring(dot + 1));
            }
            String imported = scan.imports.get(name);
            if (imported != null) {
                return el.getTypeElement(imported);
            }
            for (String pkg : scan.starImports) {
                TypeElement te = el.getTypeElement(pkg + "." + name);
                if (te != null) {
                    return te;
                }
            }
            for (String pkg : DEFAULT_PACKAGES) {
                TypeElement te = el.getTypeElement(pkg + "." + name);
                if (te != null) {
                    return te;
                }
            }
            if (name.equals("BigInteger") || name.equals("BigDecimal")) {
                return el.getTypeElement("java.math." + name);
            }
            if (!Character.isUpperCase(name.charAt(0))) {
                return null;
            }
            List<ClassIndex.Entry> hits = classes.bySimpleName(name);
            return hits.size() == 1 ? el.getTypeElement(hits.getFirst().qualifiedName()) : null;
        }

        private TypeElement nested(TypeElement outer, String path) {
            TypeElement current = outer;
            for (String part : path.split("\\.")) {
                TypeElement next = null;
                for (Element e : current.getEnclosedElements()) {
                    if (e instanceof TypeElement te && te.getSimpleName().contentEquals(part)) {
                        next = te;
                    }
                }
                if (next == null) {
                    return null;
                }
                current = next;
            }
            return current;
        }

        private boolean packageExists(String name) {
            classes.startLoading();
            return classes.packages().contains(name) || el.getPackageElement(name) != null;
        }
    }

    private static List<String> splitTopLevel(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
            } else if (c == ',' && depth == 0) {
                out.add(s.substring(start, i).strip());
                start = i + 1;
            }
        }
        if (!s.isBlank()) {
            out.add(s.substring(start).strip());
        }
        return out;
    }

    // ------------------------------------------------------------------ Parameter und Einstellungen aus dem Quelltext

    /** {@code param 'name', Typ, 'Beschreibung'} im Block des Tools ab dem Token {@code open}. */
    private static List<Key> toolParams(Src src, int open) {
        List<Key> out = new ArrayList<>();
        if (open < 0) {
            return out;
        }
        int close = src.matchForward(open, "{", "}");
        int end = close < 0 ? src.size() : close;
        int depth = 0;
        for (int k = open + 1; k < end; k++) {
            if (src.isPunct(k, "{")) {
                depth++;
            } else if (src.isPunct(k, "}")) {
                depth--;
            } else if (depth == 0 && src.isIdent(k, "param") && src.statementStart(k)) {
                Key key = dslArgs(src, k, true);
                if (key != null) {
                    out.add(key);
                }
            }
        }
        return out;
    }

    /** {@code setting 'key', 'Beschriftung', TYP} in {@code module { }}. */
    private static List<Key> settings(Src src) {
        List<Key> out = new ArrayList<>();
        int depth = 0;
        int module = -1;
        for (int k = 0; k < src.size(); k++) {
            if (src.isPunct(k, "{")) {
                depth++;
                if (module < 0 && depth == 1 && src.isIdent(src.prevSig(k), "module")) {
                    module = depth;
                }
            } else if (src.isPunct(k, "}")) {
                if (depth == module) {
                    module = -1;
                }
                depth--;
            } else if (module > 0 && depth == module && src.isIdent(k, "setting") && src.statementStart(k)) {
                Key key = dslArgs(src, k, false);
                if (key != null) {
                    out.add(key);
                }
            }
        }
        return out;
    }

    /** Argumente bis zum Zeilenende: Zeichenketten (Name, Beschriftung/Beschreibung) und der Typ. */
    private static Key dslArgs(Src src, int k, boolean param) {
        List<String> strings = new ArrayList<>();
        String type = null;
        int end = src.statementEnd(k + 1);
        for (int j = k + 1; j < end; j++) {
            Token t = src.token(j);
            if (src.isPunct(src.nextSig(j, end), ":")) {
                j = src.nextSig(j, end) + 1; // benanntes Argument samt Wert überspringen
                continue;
            }
            if (t.type() == Type.STRING) {
                strings.add(unquote(src.text(j)));
            } else if (t.type() == Type.IDENT && type == null) {
                type = src.text(j);
            }
        }
        if (strings.isEmpty()) {
            return null;
        }
        String name = strings.getFirst();
        String second = strings.size() > 1 ? strings.get(1) : "";
        if (param) {
            String t = type == null ? "String" : type;
            return new Key(name, t, paramJavaType(t), second);
        }
        String t = type == null ? "STRING" : type;
        return new Key(name, t, settingJavaType(t), second);
    }

    private static String paramJavaType(String type) {
        return switch (type) {
            case "Integer", "Long", "int", "long", "BigInteger" -> "java.lang.Long";
            case "Double", "Float", "BigDecimal", "Number", "double", "float" -> "java.lang.Number";
            case "Boolean", "boolean" -> "java.lang.Boolean";
            case "List", "Collection" -> "java.util.List";
            case "Map" -> "java.util.Map";
            default -> "java.lang.String";
        };
    }

    private static String settingJavaType(String type) {
        return switch (type) {
            case "INT", "Integer", "Long", "int", "long" -> "java.lang.Long";
            case "BOOLEAN", "Boolean", "boolean" -> "java.lang.Boolean";
            case "DIRECTORY_LIST", "STRING_LIST", "RECORD_LIST", "List" -> "java.util.List";
            default -> "java.lang.String";
        };
    }

    private static String unquote(String s) {
        String t = s;
        while (!t.isEmpty() && (t.charAt(0) == '\'' || t.charAt(0) == '"')) {
            t = t.substring(1);
        }
        while (!t.isEmpty() && (t.charAt(t.length() - 1) == '\'' || t.charAt(t.length() - 1) == '"')) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }

    // ------------------------------------------------------------------ feste Vorschläge bauen

    private static Completion dsl(String label, String tail, String template, String doc) {
        return Completion.of(Kind.METHOD, label).withTail(tail).withDetail("DSL").withDoc(doc).withPriority(30)
                .withTemplate(template);
    }

    private static Completion keyword(String word) {
        return Completion.of(Kind.KEYWORD, word).withPriority(5);
    }

    private static List<Completion> keywords(String... words) {
        return java.util.Arrays.stream(words).map(GroovyAssist::keyword).toList();
    }

    private static Completion paramType(String name, String json) {
        return Completion.of(Kind.CLASS, name).withDetail(json).withPriority(10);
    }

    private static Completion option(String key, String tail, String template, String doc) {
        return Completion.of(Kind.PROPERTY, key).withTail(tail).withDoc(doc).withTemplate(template);
    }

    // ------------------------------------------------------------------ Token-Zugriff

    /** Token mit Hilfen für die Rückwärts- und Vorwärtssuche; Kommentare zählen nirgends mit. */
    static final class Src {
        private final CodeLexer lexer;
        private final List<Token> tokens;
        private final String text;

        Src(CodeLexer lexer, String text) {
            this.lexer = lexer;
            this.tokens = lexer.tokens();
            this.text = text;
        }

        int size() {
            return tokens.size();
        }

        Token token(int k) {
            return k >= 0 && k < tokens.size() ? tokens.get(k) : null;
        }

        String text(int k) {
            return lexer.text(tokens.get(k));
        }

        /** Quelltext von Token {@code from} bis einschließlich {@code to}. */
        String source(int from, int to) {
            return text.substring(tokens.get(from).start(), tokens.get(to).end()).strip();
        }

        boolean comment(int k) {
            Type t = tokens.get(k).type();
            return t == Type.COMMENT || t == Type.DOC_COMMENT || t == Type.DIRECTIVE;
        }

        boolean isPunct(int k, String p) {
            Token t = token(k);
            return t != null && t.type() == Type.PUNCT && text(k).equals(p);
        }

        boolean isIdent(int k, String name) {
            Token t = token(k);
            return t != null && t.type() == Type.IDENT && text(k).equals(name);
        }

        boolean isKeyword(int k, String word) {
            Token t = token(k);
            return t != null && t.type() == Type.KEYWORD && text(k).equals(word);
        }

        /** Anzahl der Token, die vor {@code pos} beginnen. */
        int countBefore(int pos) {
            int lo = 0;
            int hi = tokens.size();
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (tokens.get(mid).start() < pos) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            return lo;
        }

        /** Vorheriges Token ohne Zeilenumbrüche und Kommentare, sonst -1. */
        int prevSig(int k) {
            for (int j = k - 1; j >= 0; j--) {
                if (tokens.get(j).type() != Type.NEWLINE && !comment(j)) {
                    return j;
                }
            }
            return -1;
        }

        /** Nächstes Token nach {@code k} vor {@code limit} ohne Zeilenumbrüche und Kommentare, sonst -1. */
        int nextSig(int k, int limit) {
            for (int j = k + 1; j < limit && j < tokens.size(); j++) {
                if (tokens.get(j).type() != Type.NEWLINE && !comment(j)) {
                    return j;
                }
            }
            return -1;
        }

        /** Punkt direkt vor dem angefangenen Wort ({@code foo.|}, {@code foo?.|}) – sonst -1. */
        int dotBefore(int from, int limit) {
            int k = limit - 1;
            while (k >= 0 && comment(k)) {
                k--;
            }
            if (k < 0) {
                return -1;
            }
            Token t = tokens.get(k);
            String p = t.type() == Type.PUNCT ? text(k) : "";
            if (!(p.equals(".") || p.equals("?.") || p.equals("*.")) || !text.substring(t.end(), from).isBlank()) {
                return -1;
            }
            return k;
        }

        int matchBack(int close, String open, String closing) {
            int depth = 0;
            for (int j = close; j >= 0; j--) {
                if (isPunct(j, closing)) {
                    depth++;
                } else if (isPunct(j, open) && --depth == 0) {
                    return j;
                }
            }
            return -1;
        }

        int matchForward(int open, String opening, String close) {
            int depth = 0;
            for (int j = open; j < tokens.size(); j++) {
                if (isPunct(j, opening)) {
                    depth++;
                } else if (isPunct(j, close) && --depth == 0) {
                    return j;
                }
            }
            return -1;
        }

        boolean hasTopLevel(int from, int to, String p) {
            int depth = 0;
            for (int j = from; j < to; j++) {
                if (isPunct(j, "(") || isPunct(j, "[") || isPunct(j, "{")) {
                    depth++;
                } else if (isPunct(j, ")") || isPunct(j, "]") || isPunct(j, "}")) {
                    depth--;
                } else if (depth == 0 && isPunct(j, p)) {
                    return true;
                }
            }
            return false;
        }

        String firstString(int from, int to) {
            for (int j = from; j <= to; j++) {
                if (tokens.get(j).type() == Type.STRING) {
                    return unquote(text(j));
                }
            }
            return null;
        }

        /** Ob Token {@code k} eine Anweisung beginnt (nach Zeilenumbruch, {@code {}, {@code ;} oder Textanfang). */
        boolean statementStart(int k) {
            for (int j = k - 1; j >= 0; j--) {
                if (comment(j)) {
                    continue;
                }
                Token t = tokens.get(j);
                return t.type() == Type.NEWLINE || isPunct(j, "{") || isPunct(j, ";") || isPunct(j, "->");
            }
            return true;
        }

        /** Erstes Token nach der Anweisung ab {@code from} (Zeilenende bzw. {@code ;} außerhalb von Klammern). */
        int statementEnd(int from) {
            int depth = 0;
            for (int j = from; j < tokens.size(); j++) {
                if (isPunct(j, "(") || isPunct(j, "[") || isPunct(j, "{")) {
                    depth++;
                } else if (isPunct(j, ")") || isPunct(j, "]") || isPunct(j, "}")) {
                    if (depth == 0) {
                        return j;
                    }
                    depth--;
                } else if (depth == 0 && (tokens.get(j).type() == Type.NEWLINE || isPunct(j, ";"))) {
                    int p = prevSig(j);
                    if (tokens.get(j).type() == Type.NEWLINE && p >= 0 && tokens.get(p).type() == Type.PUNCT
                            && CONTINUES.contains(text(p))) {
                        continue;
                    }
                    return j;
                }
            }
            return tokens.size();
        }
    }
}
