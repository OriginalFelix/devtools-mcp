package systems.grebe.devtools.mcp.modules.graph;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Confidence;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Kind;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Relation;

/**
 * Liest Java-Quelltext mit tree-sitter (deterministisch, ohne LLM) in zwei Durchläufen:
 * <ol>
 *   <li>{@link #declarations}: Paket, Imports, Typen, Methoden, Konstruktoren, Felder, Javadoc.</li>
 *   <li>{@link #references}: Vererbung, Annotationen, Feldtypen, Überschreibungen und der Aufrufgraph –
 *       aufgelöst über den {@link JavaResolver} mit den Deklarationen aller Dateien.</li>
 * </ol>
 * Gearbeitet wird auf einer Java-Kopie des Syntaxbaums ({@link SyntaxNode}); der native Baum ist danach bereits frei.
 */
final class JavaExtractor {

    private static final Pattern WS = Pattern.compile("\\s+");
    private static final Pattern ANNOTATION_IN_TYPE = Pattern.compile("@[\\w.]+(\\([^)]*\\))?\\s*");

    record Import(String name, boolean isStatic, boolean wildcard, int line) {
    }

    record MemberDecl(String id, String owner, Kind kind, String name, List<String> paramTypes, boolean varargs,
                      String type, int line, int endLine, String modifiers, List<String> annotations,
                      List<String> typeParams, String doc, String signature) {

        boolean accepts(int arity) {
            return varargs ? arity >= paramTypes.size() - 1 : arity == paramTypes.size();
        }

        boolean isStatic() {
            return modifiers != null && modifiers.contains("static");
        }
    }

    record TypeDecl(String fqn, String simpleName, Kind kind, String outer, String file, int line, int endLine,
                    String modifiers, List<String> annotations, List<String> typeParams, String superclass,
                    List<String> interfaces, List<MemberDecl> members, String doc) {
    }

    record FileDecl(String path, String pkg, List<Import> imports, List<TypeDecl> types, int lines, boolean errors) {
    }

    /** Kante vor dem Zusammenfassen (gleiche Quelle/Ziel/Relation werden im Builder gezählt). */
    record RawEdge(String from, String to, Relation rel, Confidence conf, double score, int line) {
    }

    /** Auflösungskontext: Datei, umschließender Typ und sichtbare Typparameter. */
    record Ctx(FileDecl file, String type, Set<String> typeParams) {
    }

    /** Ergebnis der Typermittlung eines Ausdrucks. */
    record TypeRef(String fqn, boolean inferred, boolean staticRef) {
    }

    /** Ergebnis der Auflösung eines Aufrufs. */
    record CallTargets(List<MemberDecl> targets, Confidence conf, double score) {
        static final CallTargets NONE = new CallTargets(List.of(), Confidence.EXTRACTED, 0);
    }

    // ------------------------------------------------------------------ Durchlauf 1: Deklarationen

    static FileDecl declarations(String path, String source) {
        return new DeclarationReader(path, parse(source)).read();
    }

    // ------------------------------------------------------------------ Durchlauf 2: Referenzen

    static List<RawEdge> references(FileDecl decl, String source, JavaResolver resolver) {
        return new ReferenceReader(decl, parse(source), resolver).read();
    }

    // ------------------------------------------------------------------ Parsen

    /** Geparste Datei: kopierter Syntaxbaum und Quelltext-Bytes (Offsets sind UTF-8-Byte-Positionen). */
    record Parsed(SyntaxNode root, boolean hasError, byte[] bytes) {
        String text(SyntaxNode n) {
            if (n == null) {
                return "";
            }
            return new String(bytes, n.startByte, n.endByte - n.startByte, StandardCharsets.UTF_8);
        }
    }

    static Parsed parse(String source) {
        SyntaxNode.Parsed tree = SyntaxNode.parse(source);
        return new Parsed(tree.root(), tree.hasError(), source.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ Hilfen für beide Durchläufe

    static boolean has(SyntaxNode n) {
        return n != null;
    }

    static SyntaxNode field(SyntaxNode n, String name) {
        return n.field(name);
    }

    static List<SyntaxNode> namedChildren(SyntaxNode n) {
        return n.namedChildren();
    }

    static SyntaxNode namedChild(SyntaxNode n, int i) {
        return n.namedChildren().get(i);
    }

    static SyntaxNode firstChildOfType(SyntaxNode n, String type) {
        for (SyntaxNode c : namedChildren(n)) {
            if (c.getType().equals(type)) {
                return c;
            }
        }
        return null;
    }

    static int line(SyntaxNode n) {
        return n.startRow + 1;
    }

    static int endLine(SyntaxNode n) {
        return n.endRow + 1;
    }

    /** Entfernt Generics, Annotationen und Leerzeichen: {@code Map<String, List<X>>[]} → {@code Map[]}. */
    static String erase(String type) {
        if (type == null) {
            return null;
        }
        String t = ANNOTATION_IN_TYPE.matcher(type).replaceAll("");
        StringBuilder sb = new StringBuilder(t.length());
        int depth = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth = Math.max(0, depth - 1);
            } else if (depth == 0 && !Character.isWhitespace(c)) {
                sb.append(c);
            }
        }
        return sb.toString().replace("...", "[]");
    }

    /** Einfacher Name eines (gelöschten) Typs: {@code java.util.List[]} → {@code List[]}. */
    static String simpleType(String erased) {
        int i = erased.lastIndexOf('.');
        return i < 0 ? erased : erased.substring(i + 1);
    }

    /** Erster Satz eines Javadoc-Kommentars, bereinigt und gekürzt. */
    static String javadoc(Parsed p, SyntaxNode decl) {
        SyntaxNode prev = decl.prevNamedSibling();
        if (!has(prev) || !prev.getType().equals("block_comment")) {
            return null;
        }
        String raw = p.text(prev);
        if (!raw.startsWith("/**")) {
            return null;
        }
        String body = raw.substring(3, raw.length() - 2);
        StringBuilder sb = new StringBuilder();
        for (String l : body.split("\\R")) {
            String s = l.strip();
            if (s.startsWith("*")) {
                s = s.substring(1).strip();
            }
            if (s.startsWith("@")) {
                break; // Tags (@param, @return …) nicht übernehmen
            }
            sb.append(s).append(' ');
        }
        String text = sb.toString()
                .replaceAll("\\{@(?:link|linkplain|code|literal)\\s+([^}]*)}", "$1")
                .replaceAll("<[^>]+>", "");
        text = WS.matcher(text).replaceAll(" ").strip();
        int dot = text.indexOf(". ");
        if (dot > 0) {
            text = text.substring(0, dot + 1);
        }
        if (text.length() > 200) {
            text = text.substring(0, 197) + "…";
        }
        return text.isEmpty() ? null : text;
    }

    // ================================================================== Durchlauf 1

    private static final class DeclarationReader {
        private final String path;
        private final Parsed p;
        private final List<TypeDecl> types = new ArrayList<>();
        private final List<Import> imports = new ArrayList<>();
        private String pkg = "";

        DeclarationReader(String path, Parsed p) {
            this.path = path;
            this.p = p;
        }

        FileDecl read() {
            SyntaxNode root = p.root();
            for (SyntaxNode c : namedChildren(root)) {
                switch (c.getType()) {
                    case "package_declaration" -> {
                        for (SyntaxNode n : namedChildren(c)) {
                            if (n.getType().equals("scoped_identifier") || n.getType().equals("identifier")) {
                                pkg = p.text(n);
                            }
                        }
                    }
                    case "import_declaration" -> readImport(c);
                    default -> {
                        if (isTypeDecl(c)) {
                            readType(c, null, pkg.isEmpty() ? "" : pkg + ".");
                        }
                    }
                }
            }
            int lines = p.root().endRow + 1;
            return new FileDecl(path, pkg, List.copyOf(imports), List.copyOf(types), lines, p.hasError());
        }

        private void readImport(SyntaxNode c) {
            String text = p.text(c);
            boolean isStatic = text.matches("(?s)import\\s+static\\b.*");
            boolean wildcard = firstChildOfType(c, "asterisk") != null || text.replaceAll("\\s", "").endsWith(".*;");
            String name = null;
            for (SyntaxNode n : namedChildren(c)) {
                if (n.getType().equals("scoped_identifier") || n.getType().equals("identifier")) {
                    name = p.text(n);
                }
            }
            if (name != null) {
                imports.add(new Import(WS.matcher(name).replaceAll(""), isStatic, wildcard, line(c)));
            }
        }

        private void readType(SyntaxNode n, TypeDecl outer, String prefix) {
            String simple = p.text(field(n, "name"));
            String fqn = prefix + simple;
            Kind kind = switch (n.getType()) {
                case "interface_declaration" -> Kind.INTERFACE;
                case "enum_declaration" -> Kind.ENUM;
                case "record_declaration" -> Kind.RECORD;
                case "annotation_type_declaration" -> Kind.ANNOTATION;
                default -> Kind.CLASS;
            };
            SyntaxNode mods = firstChildOfType(n, "modifiers");
            String superclass = null;
            List<String> interfaces = new ArrayList<>();
            SyntaxNode sc = field(n, "superclass");
            if (sc != null && sc.namedChildCount() > 0) {
                superclass = p.text(namedChild(sc, 0));
            }
            SyntaxNode ifs = field(n, "interfaces");
            if (ifs == null) {
                ifs = firstChildOfType(n, "extends_interfaces"); // interface X extends A, B
            }
            if (ifs != null) {
                SyntaxNode list = firstChildOfType(ifs, "type_list");
                for (SyntaxNode t : namedChildren(list == null ? ifs : list)) {
                    interfaces.add(p.text(t));
                }
            }
            List<String> typeParams = typeParams(field(n, "type_parameters"));
            List<MemberDecl> members = new ArrayList<>();
            TypeDecl self = new TypeDecl(fqn, simple, kind, outer == null ? null : outer.fqn(), path, line(n), endLine(n),
                    modifierText(mods), annotations(mods), typeParams, superclass, List.copyOf(interfaces), members,
                    javadoc(p, n));
            types.add(self);

            if (kind == Kind.RECORD) {
                SyntaxNode params = field(n, "parameters");
                if (params != null) {
                    for (SyntaxNode fp : namedChildren(params)) {
                        if (fp.getType().equals("formal_parameter")) {
                            String name = p.text(field(fp, "name"));
                            String type = p.text(field(fp, "type"));
                            members.add(member(fqn, Kind.FIELD, name, List.of(), false, type, fp, "private final",
                                    List.of(), List.of(), null, type + " " + name));
                            members.add(member(fqn, Kind.METHOD, name, List.of(), false, type, fp, "public",
                                    List.of(), List.of(), null, type + " " + name + "()"));
                        }
                    }
                }
            }

            SyntaxNode body = field(n, "body");
            if (body == null) {
                return;
            }
            List<SyntaxNode> decls = new ArrayList<>();
            for (SyntaxNode c : namedChildren(body)) {
                if (c.getType().equals("enum_body_declarations")) {
                    decls.addAll(namedChildren(c));
                } else {
                    decls.add(c);
                }
            }
            for (SyntaxNode c : decls) {
                switch (c.getType()) {
                    case "method_declaration", "annotation_type_element_declaration" -> members.add(method(fqn, c, Kind.METHOD));
                    case "constructor_declaration", "compact_constructor_declaration" -> members.add(method(fqn, c, Kind.CONSTRUCTOR));
                    case "field_declaration", "constant_declaration" -> fields(fqn, c, members);
                    case "enum_constant" -> {
                        String name = p.text(field(c, "name"));
                        SyntaxNode cm = firstChildOfType(c, "modifiers");
                        members.add(member(fqn, Kind.FIELD, name, List.of(), false, fqn, c, "public static final",
                                annotations(cm), List.of(), javadoc(p, c), simple + " " + name));
                    }
                    default -> {
                        if (isTypeDecl(c)) {
                            readType(c, self, fqn + ".");
                        }
                    }
                }
            }
        }

        private MemberDecl method(String owner, SyntaxNode n, Kind kind) {
            SyntaxNode mods = firstChildOfType(n, "modifiers");
            String name = kind == Kind.CONSTRUCTOR ? owner.substring(owner.lastIndexOf('.') + 1) : p.text(field(n, "name"));
            List<String> params = new ArrayList<>();
            List<String> paramText = new ArrayList<>();
            boolean varargs = false;
            SyntaxNode fps = field(n, "parameters");
            if (fps != null) {
                for (SyntaxNode fp : namedChildren(fps)) {
                    switch (fp.getType()) {
                        case "formal_parameter" -> {
                            String t = p.text(field(fp, "type"));
                            params.add(t);
                            paramText.add(WS.matcher(t).replaceAll(" ") + " " + p.text(field(fp, "name")));
                        }
                        case "spread_parameter" -> {
                            varargs = true;
                            String t = null;
                            String pn = "";
                            for (SyntaxNode sc : namedChildren(fp)) {
                                if (sc.getType().equals("variable_declarator")) {
                                    pn = p.text(field(sc, "name"));
                                } else if (!sc.getType().equals("modifiers") && t == null) {
                                    t = p.text(sc);
                                }
                            }
                            params.add((t == null ? "Object" : t) + "[]");
                            paramText.add(t + "... " + pn);
                        }
                        default -> { }
                    }
                }
            }
            String type = kind == Kind.CONSTRUCTOR ? null : p.text(field(n, "type"));
            String sig = (type == null ? "" : WS.matcher(type).replaceAll(" ") + " ") + name
                    + "(" + String.join(", ", paramText) + ")";
            return member(owner, kind, name, params, varargs, type, n, modifierText(mods), annotations(mods),
                    typeParams(field(n, "type_parameters")), javadoc(p, n), sig);
        }

        private void fields(String owner, SyntaxNode n, List<MemberDecl> members) {
            SyntaxNode mods = firstChildOfType(n, "modifiers");
            String type = p.text(field(n, "type"));
            String doc = javadoc(p, n);
            for (SyntaxNode d : namedChildren(n)) {
                if (d.getType().equals("variable_declarator")) {
                    String name = p.text(field(d, "name"));
                    members.add(member(owner, Kind.FIELD, name, List.of(), false, type, d, modifierText(mods),
                            annotations(mods), List.of(), doc, WS.matcher(type).replaceAll(" ") + " " + name));
                }
            }
        }

        private MemberDecl member(String owner, Kind kind, String name, List<String> rawParams, boolean varargs,
                                  String type, SyntaxNode n, String modifiers, List<String> annotations,
                                  List<String> typeParams, String doc, String signature) {
            List<String> erased = rawParams.stream().map(t -> simpleType(erase(t))).toList();
            String id = kind == Kind.FIELD
                    ? owner + "#" + name
                    : owner + "#" + (kind == Kind.CONSTRUCTOR ? "<init>" : name) + "(" + String.join(",", erased) + ")";
            return new MemberDecl(id, owner, kind, name, erased, varargs, type, line(n), endLine(n), modifiers,
                    annotations, typeParams, doc, WS.matcher(signature).replaceAll(" ").strip());
        }

        private String modifierText(SyntaxNode mods) {
            if (mods == null) {
                return null;
            }
            List<String> words = new ArrayList<>();
            for (SyntaxNode c : mods.allChildren()) {
                String t = c.getType();
                if (!t.endsWith("annotation") && !t.equals("line_comment") && !t.equals("block_comment")) {
                    words.add(p.text(c));
                }
            }
            return words.isEmpty() ? null : String.join(" ", words);
        }

        private List<String> annotations(SyntaxNode mods) {
            if (mods == null) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            for (SyntaxNode c : namedChildren(mods)) {
                if (c.getType().equals("marker_annotation") || c.getType().equals("annotation")) {
                    out.add(p.text(field(c, "name")));
                }
            }
            return out;
        }

        private List<String> typeParams(SyntaxNode tps) {
            if (tps == null) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            for (SyntaxNode tp : namedChildren(tps)) {
                for (SyntaxNode c : namedChildren(tp)) {
                    if (c.getType().equals("type_identifier") || c.getType().equals("identifier")) {
                        out.add(p.text(c));
                        break;
                    }
                }
            }
            return out;
        }
    }

    static boolean isTypeDecl(SyntaxNode n) {
        return switch (n.getType()) {
            case "class_declaration", "interface_declaration", "enum_declaration", "record_declaration",
                 "annotation_type_declaration" -> true;
            default -> false;
        };
    }

    // ================================================================== Durchlauf 2

    /** Namen, bei denen ein Aufruf mit unbekanntem Empfänger nicht per Namenssuche geraten wird. */
    private static final Set<String> NO_GUESS = Set.of(
            "equals", "hashCode", "toString", "getClass", "clone", "finalize", "notify", "notifyAll", "wait",
            "compareTo", "get", "set", "add", "put", "remove", "size", "isEmpty", "contains", "clear", "addAll",
            "stream", "map", "filter", "forEach", "collect", "iterator", "next", "hasNext", "close", "append",
            "run", "call", "apply", "accept", "test", "of", "valueOf", "values", "name", "ordinal", "length",
            "format", "println", "print", "build", "builder", "orElse", "isPresent", "getKey", "getValue",
            "trim", "substring", "startsWith", "endsWith", "indexOf", "split", "replace", "toList", "keySet",
            "entrySet", "containsKey", "getOrDefault", "computeIfAbsent", "join", "debug", "info", "warn", "error");

    private static final class ReferenceReader {
        private final FileDecl decl;
        private final Parsed p;
        private final JavaResolver r;
        private final List<RawEdge> edges = new ArrayList<>();
        private final Map<String, TypeDecl> typesByFqn = new HashMap<>();

        ReferenceReader(FileDecl decl, Parsed p, JavaResolver r) {
            this.decl = decl;
            this.p = p;
            this.r = r;
            decl.types().forEach(t -> typesByFqn.put(t.fqn(), t));
        }

        List<RawEdge> read() {
            String fileId = GraphBuilder.fileId(decl.path());
            Ctx fileCtx = new Ctx(decl, null, Set.of());
            for (Import imp : decl.imports()) {
                String target = imp.isStatic() || !imp.wildcard() ? importedType(imp) : null;
                if (target != null) {
                    edges.add(new RawEdge(fileId, target, Relation.IMPORTS, Confidence.EXTRACTED, 1, imp.line()));
                }
            }
            String prefix = decl.pkg().isEmpty() ? "" : decl.pkg() + ".";
            for (SyntaxNode c : namedChildren(p.root())) {
                if (isTypeDecl(c)) {
                    walkType(c, prefix, fileCtx);
                }
            }
            return edges;
        }

        private String importedType(Import imp) {
            String name = imp.name();
            if (imp.isStatic() && !imp.wildcard()) {
                int dot = name.lastIndexOf('.');
                name = dot < 0 ? name : name.substring(0, dot);
            }
            return name;
        }

        private void walkType(SyntaxNode n, String prefix, Ctx outerCtx) {
            String fqn = prefix + p.text(field(n, "name"));
            TypeDecl t = typesByFqn.get(fqn);
            if (t == null) {
                return;
            }
            Set<String> tps = new HashSet<>(outerCtx.typeParams());
            tps.addAll(t.typeParams());
            Ctx ctx = new Ctx(decl, fqn, tps);

            if (t.superclass() != null) {
                typeEdge(fqn, t.superclass(), Relation.EXTENDS, ctx, t.line());
            }
            for (String i : t.interfaces()) {
                typeEdge(fqn, i, t.kind() == Kind.INTERFACE ? Relation.EXTENDS : Relation.IMPLEMENTS, ctx, t.line());
            }
            annotationEdges(fqn, t.annotations(), ctx, t.line());

            Map<String, MemberDecl> byLine = new HashMap<>();
            for (MemberDecl m : t.members()) {
                byLine.putIfAbsent(m.kind() + "@" + m.line() + "@" + m.name(), m);
                annotationEdges(m.id(), m.annotations(), ctx, m.line());
                if (m.kind() == Kind.FIELD && m.type() != null && !m.type().equals(fqn)) {
                    typeEdge(m.id(), m.type(), Relation.HAS_TYPE, withParams(ctx, m), m.line());
                } else if (m.kind() == Kind.METHOD && m.type() != null) {
                    typeEdge(m.id(), m.type(), Relation.HAS_TYPE, withParams(ctx, m), m.line());
                }
                if (m.kind() == Kind.METHOD && !m.isStatic()) {
                    overrides(t, m);
                }
            }

            SyntaxNode body = field(n, "body");
            if (body == null) {
                return;
            }
            List<SyntaxNode> decls = new ArrayList<>();
            for (SyntaxNode c : namedChildren(body)) {
                if (c.getType().equals("enum_body_declarations")) {
                    decls.addAll(namedChildren(c));
                } else {
                    decls.add(c);
                }
            }
            for (SyntaxNode c : decls) {
                switch (c.getType()) {
                    case "method_declaration", "constructor_declaration", "compact_constructor_declaration" -> {
                        Kind k = c.getType().equals("method_declaration") ? Kind.METHOD : Kind.CONSTRUCTOR;
                        String name = k == Kind.CONSTRUCTOR ? t.simpleName() : p.text(field(c, "name"));
                        MemberDecl m = byLine.get(k + "@" + line(c) + "@" + name);
                        SyntaxNode b = field(c, "body");
                        if (m != null && b != null) {
                            BodyWalker w = new BodyWalker(m.id(), withParams(ctx, m));
                            SyntaxNode fps = field(c, "parameters");
                            if (fps != null) {
                                for (SyntaxNode fp : namedChildren(fps)) {
                                    w.declareParam(fp);
                                }
                            }
                            w.walk(b);
                        }
                    }
                    case "field_declaration", "constant_declaration" -> {
                        for (SyntaxNode d : namedChildren(c)) {
                            if (d.getType().equals("variable_declarator") && field(d, "value") != null) {
                                MemberDecl m = byLine.get(Kind.FIELD + "@" + line(d) + "@" + p.text(field(d, "name")));
                                new BodyWalker(m == null ? fqn : m.id(), ctx).walk(field(d, "value"));
                            }
                        }
                    }
                    case "enum_constant" -> {
                        MemberDecl m = byLine.get(Kind.FIELD + "@" + line(c) + "@" + p.text(field(c, "name")));
                        new BodyWalker(m == null ? fqn : m.id(), ctx).walk(c);
                    }
                    case "static_initializer", "block" -> new BodyWalker(fqn, ctx).walk(c);
                    default -> {
                        if (isTypeDecl(c)) {
                            walkType(c, fqn + ".", ctx);
                        }
                    }
                }
            }
        }

        private Ctx withParams(Ctx ctx, MemberDecl m) {
            if (m.typeParams().isEmpty()) {
                return ctx;
            }
            Set<String> tps = new HashSet<>(ctx.typeParams());
            tps.addAll(m.typeParams());
            return new Ctx(ctx.file(), ctx.type(), tps);
        }

        /** Vererbung/Annotation/Feldtyp. Nicht auflösbare Namen werden als externe Knoten übernommen. */
        private void typeEdge(String from, String rawType, Relation rel, Ctx ctx, int line) {
            String erased = erase(rawType).replace("[]", "");
            String resolved = r.resolveType(erased, ctx);
            if (resolved == null) {
                if (rel == Relation.HAS_TYPE || erased.isEmpty() || JavaResolver.isPrimitive(erased)
                        || ctx.typeParams().contains(erased)) {
                    return;
                }
                edges.add(new RawEdge(from, erased, rel, Confidence.INFERRED, 0.5, line));
                return;
            }
            if (rel == Relation.HAS_TYPE && !r.isProjectType(resolved)) {
                return; // Feld-/Rückgabetypen nur innerhalb des Projekts – String, List … wären nur Rauschen
            }
            edges.add(new RawEdge(from, resolved, rel, Confidence.EXTRACTED, 1, line));
        }

        private void annotationEdges(String from, List<String> annotations, Ctx ctx, int line) {
            for (String a : annotations) {
                if (a.equals("Override") || a.equals("SuppressWarnings") || a.equals("FunctionalInterface")
                        || a.equals("SafeVarargs")) {
                    continue;
                }
                typeEdge(from, a, Relation.ANNOTATED_WITH, ctx, line);
            }
        }

        private void overrides(TypeDecl t, MemberDecl m) {
            Set<String> seen = new HashSet<>();
            List<String> queue = new ArrayList<>(r.supertypes(t.fqn()));
            boolean explicit = m.annotations().contains("Override");
            while (!queue.isEmpty()) {
                String s = queue.removeFirst();
                if (!seen.add(s) || !r.isProjectType(s)) {
                    continue;
                }
                MemberDecl hit = r.declaredMethod(s, m.name(), m.paramTypes());
                if (hit != null) {
                    edges.add(new RawEdge(m.id(), hit.id(), Relation.OVERRIDES,
                            explicit ? Confidence.EXTRACTED : Confidence.INFERRED, explicit ? 1 : 0.9, m.line()));
                } else {
                    queue.addAll(r.supertypes(s));
                }
            }
        }

        // -------------------------------------------------------------- Methodenrümpfe

        private final class BodyWalker {
            private final String owner;
            private final Ctx ctx;
            private final Map<String, TypeRef> vars = new HashMap<>();

            BodyWalker(String owner, Ctx ctx) {
                this.owner = owner;
                this.ctx = ctx;
            }

            void declareParam(SyntaxNode fp) {
                switch (fp.getType()) {
                    case "formal_parameter" -> declare(p.text(field(fp, "name")), p.text(field(fp, "type")), null);
                    case "spread_parameter" -> {
                        for (SyntaxNode c : namedChildren(fp)) {
                            if (c.getType().equals("variable_declarator")) {
                                declare(p.text(field(c, "name")), null, null);
                            }
                        }
                    }
                    default -> { }
                }
            }

            private void declare(String name, String rawType, SyntaxNode initializer) {
                if (name == null || name.isEmpty()) {
                    return;
                }
                TypeRef t = null;
                if (rawType != null && !rawType.equals("var")) {
                    String erased = erase(rawType);
                    if (!erased.endsWith("[]")) {
                        String fqn = r.resolveType(erased, ctx);
                        t = fqn == null ? null : new TypeRef(fqn, false, false);
                    }
                } else if (initializer != null) {
                    TypeRef inferred = exprType(initializer);
                    t = inferred == null ? null : new TypeRef(inferred.fqn(), true, false);
                }
                vars.put(name, t == null ? UNKNOWN : t);
            }

            void walk(SyntaxNode n) {
                switch (n.getType()) {
                    case "local_variable_declaration" -> {
                        String type = p.text(field(n, "type"));
                        for (SyntaxNode d : namedChildren(n)) {
                            if (d.getType().equals("variable_declarator")) {
                                declare(p.text(field(d, "name")), type, field(d, "value"));
                            }
                        }
                    }
                    case "enhanced_for_statement" -> declare(p.text(field(n, "name")), p.text(field(n, "type")), null);
                    case "resource" -> {
                        if (field(n, "name") != null) {
                            declare(p.text(field(n, "name")), p.text(field(n, "type")), field(n, "value"));
                        }
                    }
                    case "catch_formal_parameter" -> {
                        SyntaxNode ct = firstChildOfType(n, "catch_type");
                        String type = ct != null && ct.namedChildCount() == 1 ? p.text(namedChild(ct, 0)) : null;
                        declare(p.text(field(n, "name")), type, null);
                    }
                    case "formal_parameter" -> declare(p.text(field(n, "name")), p.text(field(n, "type")), null);
                    case "lambda_expression" -> {
                        SyntaxNode params = field(n, "parameters");
                        if (params != null) {
                            if (params.getType().equals("identifier")) {
                                vars.put(p.text(params), UNKNOWN);
                            } else if (params.getType().equals("inferred_parameters")) {
                                namedChildren(params).forEach(c -> vars.put(p.text(c), UNKNOWN));
                            }
                        }
                    }
                    case "instanceof_expression" -> {
                        if (field(n, "name") != null && field(n, "right") != null) {
                            declare(p.text(field(n, "name")), p.text(field(n, "right")), null);
                        }
                    }
                    case "type_pattern" -> {
                        List<SyntaxNode> cs = namedChildren(n);
                        if (cs.size() >= 2) {
                            declare(p.text(cs.getLast()), p.text(cs.get(cs.size() - 2)), null);
                        }
                    }
                    case "method_invocation" -> emit(resolveCall(n), line(n));
                    case "object_creation_expression" -> creation(n);
                    case "explicit_constructor_invocation" -> constructorCall(n);
                    case "method_reference" -> methodReference(n);
                    default -> { }
                }
                for (SyntaxNode child : n.namedChildren()) {
                    walk(child);
                }
            }

            private void emit(CallTargets c, int line) {
                for (MemberDecl m : c.targets()) {
                    edges.add(new RawEdge(owner, m.id(), Relation.CALLS, c.conf(), c.score(), line));
                }
            }

            private void creation(SyntaxNode n) {
                String type = erase(p.text(field(n, "type")));
                String fqn = r.resolveType(type, ctx);
                if (fqn == null || !r.isProjectType(fqn)) {
                    return;
                }
                edges.add(new RawEdge(owner, fqn, Relation.INSTANTIATES, Confidence.EXTRACTED, 1, line(n)));
                SyntaxNode args = field(n, "arguments");
                int arity = args == null ? 0 : args.namedChildCount();
                emit(choose(r.constructors(fqn, arity), false), line(n));
            }

            private void constructorCall(SyntaxNode n) {
                SyntaxNode ctor = field(n, "constructor");
                if (ctor == null || ctx.type() == null) {
                    return;
                }
                String target = ctor.getType().equals("super") ? r.superclass(ctx.type()) : ctx.type();
                if (target == null || !r.isProjectType(target)) {
                    return;
                }
                SyntaxNode args = field(n, "arguments");
                emit(choose(r.constructors(target, args == null ? 0 : args.namedChildCount()), false), line(n));
            }

            private void methodReference(SyntaxNode n) {
                List<SyntaxNode> cs = namedChildren(n);
                if (cs.isEmpty()) {
                    return;
                }
                SyntaxNode recv = cs.getFirst();
                String text = p.text(n);
                String name = text.substring(text.lastIndexOf("::") + 2).strip();
                TypeRef t = recv.getType().equals("this") || recv.getType().equals("super") || recv.getType().equals("identifier")
                        || recv.getType().equals("field_access") ? exprType(recv) : null;
                if (t == null) {
                    String fqn = r.resolveType(erase(p.text(recv)), ctx);
                    t = fqn == null ? null : new TypeRef(fqn, false, true);
                }
                if (t == null || !r.isProjectType(t.fqn())) {
                    return;
                }
                if (name.equals("new")) {
                    edges.add(new RawEdge(owner, t.fqn(), Relation.INSTANTIATES, Confidence.EXTRACTED, 1, line(n)));
                    return;
                }
                emit(choose(r.methodsAnyArity(t.fqn(), name), t.inferred()), line(n));
            }

            /** Löst einen Methodenaufruf auf – ohne Seiteneffekt, damit er auch für Typketten nutzbar ist. */
            CallTargets resolveCall(SyntaxNode n) {
                String name = p.text(field(n, "name"));
                SyntaxNode args = field(n, "arguments");
                int arity = args == null ? 0 : args.namedChildCount();
                SyntaxNode obj = field(n, "object");
                if (obj == null) {
                    if (ctx.type() != null) {
                        for (String t = ctx.type(); t != null; t = r.outerOf(t)) {
                            List<MemberDecl> found = r.methods(t, name, arity);
                            if (!found.isEmpty()) {
                                return choose(found, false);
                            }
                        }
                    }
                    List<MemberDecl> statics = r.staticImportMethods(decl, name, arity);
                    return statics.isEmpty() ? CallTargets.NONE : choose(statics, false);
                }
                TypeRef t = exprType(obj);
                if (t != null) {
                    if (!r.isProjectType(t.fqn())) {
                        return CallTargets.NONE; // externe Bibliothek
                    }
                    return choose(r.methods(t.fqn(), name, arity), t.inferred());
                }
                return guess(name, arity);
            }

            /** Empfängertyp unbekannt: über den Methodennamen raten, aber nur bei wenigen Kandidaten. */
            private CallTargets guess(String name, int arity) {
                if (NO_GUESS.contains(name)) {
                    return CallTargets.NONE;
                }
                List<MemberDecl> all = r.methodsByName(name, arity);
                if (all.isEmpty() || all.size() > 3) {
                    return CallTargets.NONE;
                }
                if (all.size() == 1) {
                    return new CallTargets(all, Confidence.INFERRED, 0.6);
                }
                return new CallTargets(all, Confidence.AMBIGUOUS, round(0.5 / all.size()));
            }

            private CallTargets choose(List<MemberDecl> found, boolean inferred) {
                if (found.isEmpty()) {
                    return CallTargets.NONE;
                }
                if (found.size() == 1) {
                    return new CallTargets(found, inferred ? Confidence.INFERRED : Confidence.EXTRACTED, inferred ? 0.8 : 1);
                }
                return new CallTargets(found, Confidence.AMBIGUOUS, round(1.0 / found.size()));
            }

            /** Statischer Typ eines Ausdrucks, soweit ohne Typinferenz ermittelbar. */
            TypeRef exprType(SyntaxNode n) {
                switch (n.getType()) {
                    case "this" -> {
                        return ctx.type() == null ? null : new TypeRef(ctx.type(), false, false);
                    }
                    case "super" -> {
                        String s = ctx.type() == null ? null : r.superclass(ctx.type());
                        return s == null ? null : new TypeRef(s, false, false);
                    }
                    case "identifier" -> {
                        String name = p.text(n);
                        TypeRef v = vars.get(name);
                        if (v != null) {
                            return v == UNKNOWN ? null : v;
                        }
                        for (String t = ctx.type(); t != null; t = r.outerOf(t)) {
                            TypeRef f = fieldType(t, name);
                            if (f != null) {
                                return f;
                            }
                        }
                        if (!name.isEmpty() && Character.isUpperCase(name.charAt(0))) {
                            String fqn = r.resolveType(name, ctx);
                            return fqn == null ? null : new TypeRef(fqn, false, true);
                        }
                        return null;
                    }
                    case "field_access" -> {
                        SyntaxNode obj = field(n, "object");
                        String fieldName = p.text(field(n, "field"));
                        TypeRef t = obj == null ? null : exprType(obj);
                        if (t != null && r.isProjectType(t.fqn())) {
                            TypeRef f = fieldType(t.fqn(), fieldName);
                            if (f != null) {
                                return t.inferred() ? new TypeRef(f.fqn(), true, false) : f;
                            }
                            String nested = r.nested(t.fqn(), fieldName);
                            if (nested != null) {
                                return new TypeRef(nested, false, true);
                            }
                            return null;
                        }
                        if (t == null && !fieldName.isEmpty() && Character.isUpperCase(fieldName.charAt(0))) {
                            String fqn = r.resolveType(WS.matcher(p.text(n)).replaceAll(""), ctx);
                            return fqn == null ? null : new TypeRef(fqn, false, true);
                        }
                        return null;
                    }
                    case "object_creation_expression" -> {
                        String fqn = r.resolveType(erase(p.text(field(n, "type"))), ctx);
                        return fqn == null ? null : new TypeRef(fqn, false, false);
                    }
                    case "cast_expression" -> {
                        String fqn = r.resolveType(erase(p.text(field(n, "type"))), ctx);
                        return fqn == null ? null : new TypeRef(fqn, false, false);
                    }
                    case "parenthesized_expression" -> {
                        return n.namedChildCount() == 1 ? exprType(namedChild(n, 0)) : null;
                    }
                    case "string_literal" -> {
                        return new TypeRef("java.lang.String", false, false);
                    }
                    case "method_invocation" -> {
                        CallTargets c = resolveCall(n);
                        if (c.targets().size() != 1 || c.conf() == Confidence.AMBIGUOUS) {
                            return null;
                        }
                        MemberDecl m = c.targets().getFirst();
                        String ret = r.returnType(m);
                        return ret == null ? null : new TypeRef(ret, true, false);
                    }
                    default -> {
                        return null;
                    }
                }
            }

            private TypeRef fieldType(String type, String name) {
                MemberDecl f = r.field(type, name);
                if (f == null) {
                    return null;
                }
                String fqn = r.memberType(f);
                return fqn == null ? UNKNOWN_FIELD : new TypeRef(fqn, false, false);
            }
        }
    }

    /** Variable bekannt, Typ aber nicht ermittelbar (verdeckt gleichnamige Felder/Typen). */
    private static final TypeRef UNKNOWN = new TypeRef("?", false, false);
    /** Feld bekannt, Typ nicht auflösbar. */
    private static final TypeRef UNKNOWN_FIELD = new TypeRef("?", false, false);

    static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private JavaExtractor() {
    }
}
