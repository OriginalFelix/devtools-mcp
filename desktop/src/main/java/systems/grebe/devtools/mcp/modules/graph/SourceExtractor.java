package systems.grebe.devtools.mcp.modules.graph;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Confidence;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Kind;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Relation;
import systems.grebe.devtools.mcp.modules.graph.JavaExtractor.FileDecl;
import systems.grebe.devtools.mcp.modules.graph.JavaExtractor.Import;
import systems.grebe.devtools.mcp.modules.graph.JavaExtractor.MemberDecl;
import systems.grebe.devtools.mcp.modules.graph.JavaExtractor.RawEdge;
import systems.grebe.devtools.mcp.modules.graph.JavaExtractor.TypeDecl;
import systems.grebe.devtools.mcp.syntax.Language;
import systems.grebe.devtools.mcp.syntax.SyntaxEngine;
import systems.grebe.devtools.mcp.syntax.SyntaxNode;
import systems.grebe.devtools.mcp.syntax.SyntaxTree;

/**
 * Liest alle übrigen tree-sitter-Sprachen ({@link Language} außer Java) in den Graphen: Typen (Klassen, Interfaces,
 * Traits, Structs, Enums), Funktionen und Methoden, Felder, Imports, Vererbung und Aufrufe. Anders als bei Java
 * ({@link JavaExtractor}) ohne Typinferenz: Aufrufe werden über Namen aufgelöst – im eigenen Typ, in der eigenen Datei
 * und im eigenen Modul sicher (EXTRACTED), projektweit nur bei wenigen Kandidaten (INFERRED/AMBIGUOUS).
 *
 * <p>IDs: Typen mit Paket/Namespace (Kotlin, Scala, C#, PHP, C++), sonst mit dem Modul der Datei (Pfad ohne Endung,
 * {@code web/src/user.service.ts} → {@code web.src.user_service}; Go: das Verzeichnis = Paket). Funktionen außerhalb
 * von Typen gehören dem Modul ({@code web.src.user_service#helper(x)}), in Kotlin der Datei-Fassade wie in der JVM
 * ({@code com.acme.UtilsKt}) – so finden auch Java-Aufrufe sie.
 */
final class SourceExtractor {

    /** Größere bzw. minifizierte Dateien (sehr lange Zeilen) bleiben reine Dateiknoten – kein Code zum Navigieren. */
    static final int MAX_CHARS = 1_000_000;
    static final int MAX_AVG_LINE = 300;

    private static final Set<Language> AVAILABLE = Set.copyOf(SyntaxEngine.availableLanguages());
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_$][\\w$]*");
    private static final Pattern WS = Pattern.compile("\\s+");
    private static final Pattern KOTLIN_KIND = Pattern.compile("\\b(enum|interface|annotation)\\b");
    private static final Pattern MODIFIER = Pattern.compile("\\b(public|private|protected|internal|static|abstract|"
            + "final|async|override|open|const|virtual|sealed|readonly|pub|inline|suspend|data|export)\\b");

    private static final Set<String> NAME_TYPES = Set.of("identifier", "type_identifier", "simple_identifier",
            "constant", "name", "word", "field_identifier", "property_identifier", "namespace_identifier",
            "package_identifier", "private_property_identifier");
    private static final Set<String> FIELD_NAME_TYPES = Set.of("identifier", "simple_identifier", "field_identifier",
            "property_identifier", "name", "variable_name", "private_property_identifier");
    private static final Set<String> SELF = Set.of("this", "self", "$this", "super", "base", "@");
    private static final Set<String> SELF_PARAMS = Set.of("self", "cls", "this", "self_parameter");
    private static final Set<String> FUNCTION_VALUES = Set.of("arrow_function", "function_expression", "function",
            "generator_function");
    private static final Set<String> DOC_WRAPPERS = Set.of("export_statement", "decorated_definition",
            "template_declaration");
    private static final Set<String> CONSTRUCTORS = Set.of("__init__", "constructor", "__construct", "initialize");
    private static final Set<String> HERITAGE = Set.of("class_heritage", "extends_clause", "implements_clause",
            "base_list", "base_class_clause", "base_clause", "class_interface_clause", "delegation_specifiers",
            "superclass", "extends_interfaces");
    private static final Set<String> TYPE_LEAVES = Set.of("user_type", "generic_type", "generic_name", "qualified_name",
            "qualified_identifier", "scoped_type_identifier", "scoped_identifier", "attribute", "member_expression",
            "stable_type_identifier", "nested_identifier");
    /** Zusätzlich zu {@link JavaExtractor#NO_GUESS}: allgegenwärtige Namen anderer Sprachen. */
    private static final Set<String> NO_GUESS = Set.of("push", "pop", "log", "len", "append", "items", "keys", "then",
            "catch", "json", "emit", "on", "send", "write", "read", "update", "delete", "insert", "find", "new", "init",
            "__init__", "main", "slice", "splice", "unwrap", "expect", "into", "iter", "printf", "free", "malloc",
            "strlen", "echo", "cd", "ls", "exit", "require", "include", "str", "int", "dict", "list", "super",
            "setState", "render", "describe", "it", "fmt", "Println", "Printf", "Errorf");

    private SourceExtractor() {
    }

    /** Sprache einer Datei, wenn sie hier gelesen wird (alle verfügbaren tree-sitter-Sprachen außer Java). */
    static Language language(String path) {
        Language l = Language.forFileName(path.substring(path.lastIndexOf('/') + 1)).orElse(null);
        return l == null || l == Language.JAVA || !AVAILABLE.contains(l) ? null : l;
    }

    /** Lohnt sich das Lesen? Sehr große oder minifizierte Dateien nicht. */
    static boolean readable(String source) {
        if (source.length() > MAX_CHARS) {
            return false;
        }
        int lines = 1;
        for (int i = 0; i < source.length(); i++) {
            if (source.charAt(i) == '\n') {
                lines++;
            }
        }
        return source.length() / lines <= MAX_AVG_LINE;
    }

    /** Sprachfamilie: innerhalb einer Familie werden Namen über Dateien hinweg aufgelöst. */
    static String family(Language l) {
        return switch (l) {
            case JAVA, KOTLIN, SCALA -> "jvm";
            case JAVASCRIPT, TYPESCRIPT, TSX -> "js";
            case C, CPP -> "c";
            default -> l.id();
        };
    }

    /** Typen tragen Paket bzw. Namespace statt des Moduls der Datei. */
    private static boolean namespaced(Language l) {
        return l == Language.KOTLIN || l == Language.SCALA || l == Language.CSHARP || l == Language.PHP
                || l == Language.CPP;
    }

    /** Unqualifizierte Aufrufe treffen zuerst Member des eigenen Typs (in Python, JS, PHP, Rust nur über self/this). */
    private static boolean implicitThis(Language l) {
        return l == Language.KOTLIN || l == Language.SCALA || l == Language.CSHARP || l == Language.CPP
                || l == Language.RUBY;
    }

    /** Modul einer Datei: Pfad ohne Endung bzw. (Go) Verzeichnis, Segmente durch Punkte getrennt. */
    static String module(String path, Language l) {
        int slash = path.lastIndexOf('/');
        String p;
        if (l == Language.GO && slash > 0) {
            p = path.substring(0, slash);
        } else {
            int dot = path.lastIndexOf('.');
            p = dot > slash ? path.substring(0, dot) : path;
        }
        StringBuilder sb = new StringBuilder();
        for (String seg : p.split("/")) {
            if (!seg.isEmpty()) {
                sb.append(sb.isEmpty() ? "" : ".").append(seg.replaceAll("[^\\w$]", "_"));
            }
        }
        return sb.toString();
    }

    /** Besitzer von Funktionen außerhalb von Typen: Kotlin-Fassade {@code <Datei>Kt} wie in der JVM, sonst das Modul. */
    static String fileOwner(String path, Language lang, String pkg) {
        if (lang == Language.KOTLIN && path.endsWith(".kt")) {
            String file = path.substring(path.lastIndexOf('/') + 1, path.length() - 3);
            String facade = Character.toUpperCase(file.charAt(0)) + file.substring(1).replaceAll("[^\\w$]", "_") + "Kt";
            return pkg.isEmpty() ? facade : pkg + "." + facade;
        }
        return module(path, lang);
    }

    /** Letzter Bezeichner eines Ausdrucks/Typs ohne Generics: {@code a.b.List<X>[]} → {@code List[]}. */
    static String simple(String type) {
        if (type == null) {
            return null;
        }
        String e = JavaExtractor.erase(type.strip());
        boolean array = e.endsWith("[]") || e.startsWith("[]");
        String last = null;
        Matcher m = IDENT.matcher(e);
        while (m.find()) {
            last = m.group();
        }
        return last == null ? null : array ? last + "[]" : last;
    }

    private static String text(SyntaxNode n) {
        return n == null ? null : n.text();
    }

    private static String unquote(String s) {
        return s == null ? null : s.strip().replaceAll("^[\"'`<]+|[\"'`>]+$", "");
    }

    // ================================================================== Durchlauf 1: Deklarationen

    static FileDecl declarations(String path, Language lang, String source) {
        return new Decls(path, lang, SyntaxEngine.parse(lang, source)).read();
    }

    private static final class Decls {
        private final String path;
        private final Language lang;
        private final SyntaxTree tree;
        private final String module;
        private final List<Import> imports = new ArrayList<>();
        private final List<TypeDecl> types = new ArrayList<>();
        private final List<MemberDecl> functions = new ArrayList<>();
        /** Rust: Traits aus {@code impl Trait for Typ}, je Typ-ID. */
        private final Map<String, List<String>> implTraits = new HashMap<>();
        private String pkg = "";
        /** Rust: Besitzer der Funktionen eines {@code impl}-Blocks, dessen Typ in einer anderen Datei steht. */
        private String implOwner;

        Decls(String path, Language lang, SyntaxTree tree) {
            this.path = path;
            this.lang = lang;
            this.tree = tree;
            this.module = module(path, lang);
        }

        FileDecl read() {
            visit(tree.root(), null, null, 0);
            List<TypeDecl> out = new ArrayList<>(types.size() + 1);
            for (TypeDecl t : types) {
                List<String> traits = implTraits.get(t.fqn());
                if (traits == null) {
                    out.add(t);
                } else {
                    List<String> ifs = new ArrayList<>(t.interfaces());
                    traits.stream().filter(s -> !ifs.contains(s)).forEach(ifs::add);
                    out.add(new TypeDecl(t.fqn(), t.simpleName(), t.kind(), t.outer(), t.file(), t.line(), t.endLine(),
                            t.modifiers(), t.annotations(), t.typeParams(), t.superclass(), List.copyOf(ifs),
                            t.members(), t.doc()));
                }
            }
            List<MemberDecl> fns = List.copyOf(functions);
            if (kotlinFacade() && !fns.isEmpty()) {
                String facade = fileOwner();
                out.add(new TypeDecl(facade, facade.substring(facade.lastIndexOf('.') + 1), Kind.CLASS, null, path, 1,
                        tree.root().endLine(), "public final", List.of(), List.of(), null, List.of(),
                        new ArrayList<>(fns), "Kotlin-Datei-Fassade: Funktionen und Eigenschaften außerhalb von Klassen."));
                fns = List.of();
            }
            return new FileDecl(path, pkg, List.copyOf(imports), List.copyOf(out), tree.root().endLine(),
                    tree.hasError(), fns);
        }

        private boolean kotlinFacade() {
            return lang == Language.KOTLIN && path.endsWith(".kt");
        }

        private String fileOwner() {
            return SourceExtractor.fileOwner(path, lang, pkg);
        }

        /** Präfix für Typen auf oberster Ebene. */
        private String typePrefix(String ns) {
            if (ns != null && !ns.isEmpty()) {
                return ns;
            }
            if (namespaced(lang) && (!pkg.isEmpty() || lang == Language.KOTLIN || lang == Language.SCALA)) {
                return pkg;
            }
            return module;
        }

        private static String join(String a, String b) {
            return a == null || a.isEmpty() ? b : a + "." + b;
        }

        private void visit(SyntaxNode n, TypeDecl owner, String ns, int depth) {
            if (depth > 40) {
                return; // tief geschachtelte Ausdrücke enthalten keine Deklarationen mehr
            }
            for (SyntaxNode c : n.namedChildren()) {
                switch (c.type()) {
                    case "package_header" -> pkg = c.namedChildCount() == 0 ? "" : WS.matcher(c.namedChildren()
                            .getFirst().text()).replaceAll("");
                    case "package_clause" -> {
                        if (lang == Language.SCALA && c.child("name") != null) {
                            pkg = WS.matcher(c.child("name").text()).replaceAll("");
                        }
                    }
                    case "namespace_declaration", "namespace_definition", "file_scoped_namespace_declaration" -> {
                        String name = c.child("name") == null ? "" : c.child("name").text().strip()
                                .replace('\\', '.').replace("::", ".");
                        String inner = join(ns != null ? ns : pkg, name);
                        SyntaxNode body = c.child("body");
                        if (body != null) {
                            visit(body, owner, inner, depth + 1);
                        } else {
                            pkg = inner; // namespace X; – gilt für den Rest der Datei
                            visit(c, owner, null, depth + 1);
                        }
                    }
                    case "mod_item" -> {
                        SyntaxNode body = c.child("body");
                        if (body != null && c.child("name") != null) {
                            visit(body, null, join(ns != null ? ns : module, c.child("name").text()), depth + 1);
                        }
                    }
                    case "impl_item" -> readImpl(c, ns, depth);
                    case "import", "import_header" -> {
                        SyntaxNode q = c.namedChildCount() == 0 ? null : c.namedChildren().getFirst();
                        if (q != null) {
                            addImport(WS.matcher(q.text()).replaceAll(""), c.text().strip().endsWith("*"), c);
                        }
                    }
                    case "import_statement", "import_from_statement" -> readImport(c);
                    case "import_spec" -> addImport(unquote(text(c.child("path"))), false, c);
                    case "preproc_include" -> {
                        SyntaxNode p = c.child("path");
                        if (p != null && !p.type().equals("system_lib_string")) {
                            addImport(unquote(p.text()), false, c);
                        }
                    }
                    case "using_directive" -> {
                        SyntaxNode q = c.child("name") != null ? c.child("name")
                                : c.namedChildCount() == 0 ? null : c.namedChildren().getLast();
                        if (q != null) {
                            addImport(q.text().strip(), true, c);
                        }
                    }
                    case "namespace_use_clause" -> addImport(c.namedChildren().isEmpty() ? null : c.namedChildren()
                            .getFirst().text().strip().replace('\\', '.').replaceAll("^\\.", ""), false, c);
                    case "use_declaration" -> addImport(text(c.child("argument")) == null ? null
                            : WS.matcher(c.child("argument").text()).replaceAll("").replace("::", "."), false, c);
                    case "lexical_declaration", "variable_declaration" -> {
                        if (!functionVariables(c, owner)) {
                            visit(c, owner, ns, depth + 1);
                        }
                    }
                    default -> {
                        if (c.child("source") != null && c.type().equals("export_statement")) {
                            addImport(unquote(c.child("source").text()), false, c);
                        }
                        Kind type = typeKind(c);
                        Kind member = type == null ? memberKind(c, owner) : null;
                        if (type != null) {
                            readType(c, type, owner, ns, depth);
                        } else if (member == Kind.FIELD) {
                            readFields(c, owner, ns);
                        } else if (member != null) {
                            readFunction(c, member, owner, ns, c.child("name"), c);
                        } else {
                            visit(c, owner, ns, depth + 1);
                        }
                    }
                }
            }
        }

        private void addImport(String name, boolean wildcard, SyntaxNode at) {
            if (name != null && !name.isBlank()) {
                String n = name.endsWith(".*") ? name.substring(0, name.length() - 2) : name;
                imports.add(new Import(n, false, wildcard || name.endsWith(".*"), at.line()));
            }
        }

        /** Python: {@code import a.b} bzw. {@code from a.b import c} (Modul und Modul.c); JS/TS: {@code from './x'}. */
        private void readImport(SyntaxNode c) {
            if (c.child("source") != null) {
                addImport(unquote(c.child("source").text()), false, c);
                return;
            }
            SyntaxNode module = c.child("module_name");
            String base = module == null ? null : WS.matcher(module.text()).replaceAll("");
            if (base != null) {
                addImport(base, false, c);
            }
            for (SyntaxNode n : c.children("name")) {
                SyntaxNode dotted = n.type().equals("aliased_import") ? n.child("name") : n;
                if (dotted == null) {
                    continue;
                }
                String name = WS.matcher(dotted.text()).replaceAll("");
                addImport(base == null ? name : base.endsWith(".") ? base + name : base + "." + name, false, c);
            }
        }

        private Kind typeKind(SyntaxNode c) {
            return switch (c.type()) {
                case "class_declaration" -> {
                    if (lang != Language.KOTLIN) {
                        yield Kind.CLASS;
                    }
                    SyntaxNode name = c.child("name");
                    String header = name == null ? "" : tree.text(c.startByte(), name.startByte());
                    Matcher m = KOTLIN_KIND.matcher(header);
                    yield !m.find() ? Kind.CLASS : switch (m.group(1)) {
                        case "enum" -> Kind.ENUM;
                        case "interface" -> Kind.INTERFACE;
                        default -> Kind.ANNOTATION;
                    };
                }
                case "abstract_class_declaration", "class_definition", "object_definition", "object_declaration",
                     "class_specifier", "struct_item", "union_item", "struct_declaration", "class", "module" ->
                        c.child("name") == null ? null : Kind.CLASS;
                case "struct_specifier", "union_specifier" -> c.child("body") == null ? null : Kind.CLASS;
                case "enum_specifier" -> c.child("body") == null ? null : Kind.ENUM;
                case "record_declaration", "record_struct_declaration" -> Kind.RECORD;
                case "interface_declaration", "trait_definition", "trait_item", "trait_declaration" -> Kind.INTERFACE;
                case "enum_declaration", "enum_item", "enum_definition" -> Kind.ENUM;
                case "type_spec" -> {
                    SyntaxNode t = c.child("type");
                    yield t == null ? null : t.type().equals("struct_type") ? Kind.CLASS
                            : t.type().equals("interface_type") ? Kind.INTERFACE : null;
                }
                default -> null;
            };
        }

        private Kind memberKind(SyntaxNode c, TypeDecl owner) {
            return switch (c.type()) {
                case "function_declaration", "function_definition", "method_definition", "method_declaration",
                     "function_item", "method", "singleton_method", "generator_function_declaration",
                     "method_signature", "abstract_method_signature", "function_signature_item", "method_elem",
                     "function_signature" -> Kind.METHOD;
                case "constructor_declaration", "secondary_constructor" -> Kind.CONSTRUCTOR;
                case "property_declaration", "field_declaration", "public_field_definition", "field_definition",
                     "val_definition", "var_definition", "val_declaration", "var_declaration" ->
                        owner != null || implOwner != null || kotlinFacade() ? Kind.FIELD : null;
                default -> null;
            };
        }

        private void readType(SyntaxNode c, Kind kind, TypeDecl outer, String ns, int depth) {
            String name = simple(text(c.child("name")));
            if (name == null) {
                return;
            }
            String fqn = outer != null ? outer.fqn() + "." + name : join(typePrefix(ns), name);
            List<String> supers = new ArrayList<>();
            String superclass = null;
            for (SyntaxNode s : c.namedChildren()) {
                String f = s.field();
                if ("superclasses".equals(f) || "superclass".equals(f) || "extend".equals(f) || "interfaces".equals(f)
                        || (f == null && HERITAGE.contains(s.type()))) {
                    if (lang == Language.KOTLIN && s.type().equals("delegation_specifiers")) {
                        for (SyntaxNode d : s.namedChildren()) {
                            SyntaxNode call = d.firstChildOfType("constructor_invocation");
                            if (call != null && superclass == null) {
                                superclass = simple(call.namedChildren().getFirst().text());
                            } else {
                                collectSupers(d, supers, 0);
                            }
                        }
                    } else {
                        collectSupers(s, supers, 0);
                    }
                }
            }
            TypeDecl t = new TypeDecl(fqn, name, kind, outer == null ? null : outer.fqn(), path, c.line(), c.endLine(),
                    modifiers(c), List.of(), List.of(), superclass, List.copyOf(supers), new ArrayList<>(), doc(c));
            types.add(t);
            SyntaxNode body = c.child("body") != null ? c.child("body") : c.type().equals("type_spec") ? c.child("type") : c;
            visit(body, t, ns, depth + 1);
        }

        private void collectSupers(SyntaxNode n, List<String> out, int depth) {
            for (SyntaxNode s : n.namedChildren()) {
                switch (s.type()) {
                    case "keyword_argument", "value_arguments", "arguments", "access_specifier", "type_arguments",
                         "type_parameters", "comment", "line_comment", "block_comment" -> { }
                    default -> {
                        if (NAME_TYPES.contains(s.type()) || TYPE_LEAVES.contains(s.type())) {
                            String name = simple(s.text());
                            if (name != null && !out.contains(name)) {
                                out.add(name);
                            }
                        } else if (depth < 4) {
                            collectSupers(s, out, depth + 1);
                        }
                    }
                }
            }
        }

        /** Rust {@code impl [Trait for] Typ}: Funktionen gehören dem Typ, der Trait wird zum Obertyp. */
        private void readImpl(SyntaxNode c, String ns, int depth) {
            String type = simple(text(c.child("type")));
            SyntaxNode body = c.child("body");
            if (type == null || body == null) {
                return;
            }
            String id = join(ns != null ? ns : module, type);
            String trait = simple(text(c.child("trait")));
            if (trait != null) {
                implTraits.computeIfAbsent(id, k -> new ArrayList<>()).add(trait);
            }
            TypeDecl local = types.stream().filter(t -> t.fqn().equals(id)).findFirst().orElse(null);
            String before = implOwner;
            implOwner = local == null ? id : null;
            visit(body, local, ns, depth + 1);
            implOwner = before;
        }

        /** JS/TS: {@code const f = (a) => …} bzw. {@code = function …} auf oberster Ebene ist eine Funktion. */
        private boolean functionVariables(SyntaxNode c, TypeDecl owner) {
            boolean any = false;
            for (SyntaxNode d : c.namedChildren()) {
                SyntaxNode value = d.child("value");
                if (d.type().equals("variable_declarator") && value != null && FUNCTION_VALUES.contains(value.type())) {
                    readFunction(d, Kind.METHOD, owner, null, d.child("name"), value);
                    any = true;
                }
            }
            return any;
        }

        private record FnName(String name, String scope, SyntaxNode declarator) {
        }

        /** Name einer Funktion; C/C++ über die Deklaratorkette ({@code A::k} → Bereich {@code A}). */
        private FnName functionName(SyntaxNode c, SyntaxNode nameNode) {
            if (nameNode != null) {
                return new FnName(nameNode.text().strip(), null, null);
            }
            SyntaxNode d = c.child("declarator");
            SyntaxNode fd = null;
            List<String> scope = new ArrayList<>();
            for (int i = 0; d != null && i < 10; i++) {
                switch (d.type()) {
                    case "function_declarator" -> {
                        fd = d;
                        d = d.child("declarator");
                    }
                    case "qualified_identifier" -> {
                        if (d.child("scope") != null) {
                            scope.add(d.child("scope").text().strip());
                        }
                        d = d.child("name");
                    }
                    case "identifier", "field_identifier", "destructor_name", "operator_name" -> {
                        return new FnName(d.text().strip(), scope.isEmpty() ? null : String.join(".", scope), fd);
                    }
                    default -> d = d.child("declarator") != null ? d.child("declarator")
                            : d.namedChildCount() > 0 ? d.namedChildren().getFirst() : null;
                }
            }
            return null;
        }

        private void readFunction(SyntaxNode c, Kind kind, TypeDecl owner, String ns, SyntaxNode nameNode,
                                  SyntaxNode params) {
            FnName fn = functionName(c, nameNode);
            if (fn == null || fn.name().isEmpty()) {
                return;
            }
            String ownerId;
            if (owner != null) {
                ownerId = owner.fqn();
            } else if (implOwner != null) {
                ownerId = implOwner;
            } else if (c.type().equals("method_declaration") && lang == Language.GO) {
                String recv = receiverType(c.child("receiver"));
                ownerId = recv == null ? fileOwner() : module + "." + recv;
            } else if (fn.scope() != null) {
                ownerId = join(typePrefix(ns), fn.scope());
            } else {
                ownerId = fileOwner();
            }
            String name = fn.name();
            if (kind == Kind.METHOD && owner != null && CONSTRUCTORS.contains(name)) {
                kind = Kind.CONSTRUCTOR;
            }
            List<String> ps = params(params, fn.declarator());
            String simpleOwner = ownerId.substring(ownerId.lastIndexOf('.') + 1);
            String id = ownerId + "#" + (kind == Kind.CONSTRUCTOR ? "<init>" : name) + "(" + String.join(",", ps) + ")";
            MemberDecl m = new MemberDecl(id, ownerId, kind, kind == Kind.CONSTRUCTOR ? simpleOwner : name, ps, false,
                    returnType(c), c.line(), c.endLine(), modifiers(c), List.of(), List.of(), doc(c), signature(c));
            add(owner, m);
        }

        private void add(TypeDecl owner, MemberDecl m) {
            if (owner != null && m.owner().equals(owner.fqn())) {
                owner.members().add(m);
            } else {
                functions.add(m);
            }
        }

        private static String receiverType(SyntaxNode receiver) {
            if (receiver == null) {
                return null;
            }
            for (SyntaxNode p : receiver.namedChildren()) {
                SyntaxNode t = p.child("type");
                if (t != null) {
                    return simple(t.text().replace("*", ""));
                }
            }
            return null;
        }

        private List<String> params(SyntaxNode c, SyntaxNode declarator) {
            SyntaxNode ps = c.child("parameters");
            if (ps == null && declarator != null) {
                ps = declarator.child("parameters");
            }
            if (ps == null) {
                ps = c.firstChildOfType("function_value_parameters");
            }
            if (ps == null) {
                SyntaxNode single = c.child("parameter"); // JS: x => …
                return single == null ? List.of() : List.of(single.text().strip());
            }
            List<String> out = new ArrayList<>();
            for (SyntaxNode p : ps.namedChildren()) {
                if (p.type().contains("comment") || SELF_PARAMS.contains(p.type())) {
                    continue;
                }
                String type = typeText(p);
                String token = type != null ? simple(type) : paramName(p);
                if (token != null && !SELF_PARAMS.contains(token)) {
                    out.add(token);
                }
            }
            return out;
        }

        private static String typeText(SyntaxNode p) {
            SyntaxNode t = p.child("type");
            if (t != null) {
                return t.text().replaceFirst("^\\s*:", "");
            }
            if (p.type().equals("parameter") && p.namedChildCount() >= 2) { // Kotlin: name, Typ
                return p.namedChildren().get(1).text();
            }
            return null;
        }

        private static String paramName(SyntaxNode p) {
            SyntaxNode n = p.child("name") != null ? p.child("name") : p.child("pattern");
            if (n == null) {
                n = FIELD_NAME_TYPES.contains(p.type()) ? p : null;
                for (int i = 0; n == null && i < p.namedChildCount(); i++) {
                    SyntaxNode c = p.namedChildren().get(i);
                    n = FIELD_NAME_TYPES.contains(c.type()) ? c : null;
                }
            }
            return n == null ? simple(p.text()) : simple(n.text().replace("$", ""));
        }

        private String returnType(SyntaxNode c) {
            for (String f : List.of("return_type", "result", "returns")) {
                SyntaxNode t = c.child(f);
                if (t != null) {
                    return t.text().replaceFirst("^\\s*(:|->)", "").strip();
                }
            }
            if (lang == Language.KOTLIN) {
                boolean afterParams = false;
                for (SyntaxNode n : c.namedChildren()) {
                    if (afterParams && (n.type().equals("user_type") || n.type().equals("nullable_type"))) {
                        return n.text().replace("?", "").strip();
                    }
                    afterParams |= n.type().equals("function_value_parameters");
                }
            }
            if ((lang == Language.C || lang == Language.CPP || lang == Language.CSHARP) && c.child("type") != null) {
                return c.child("type").text().strip();
            }
            return null;
        }

        private void readFields(SyntaxNode c, TypeDecl owner, String ns) {
            SyntaxNode value = c.child("value");
            if (value != null && FUNCTION_VALUES.contains(value.type())) {
                readFunction(c, Kind.METHOD, owner, ns, c.child("name"), value); // JS/TS: handler = () => …
                return;
            }
            if (!c.findByType("function_declarator").isEmpty()) {
                return; // C/C++: Methoden-Prototyp – die Definition wird zur Methode
            }
            String ownerId = owner != null ? owner.fqn() : implOwner != null ? implOwner : fileOwner();
            List<String> names = new ArrayList<>();
            c.children("name").forEach(n -> names.add(n.text().strip().replace("$", "")));
            if (names.isEmpty()) {
                fieldNames(c, names, 0);
            }
            SyntaxNode typeNode = c.child("type");
            String type = typeNode == null ? null : typeNode.text().replaceFirst("^\\s*:", "").strip();
            for (String name : names) {
                add(owner, new MemberDecl(ownerId + "#" + name, ownerId, Kind.FIELD, name, List.of(), false, type,
                        c.line(), c.endLine(), modifiers(c), List.of(), List.of(), doc(c), signature(c)));
            }
        }

        private static void fieldNames(SyntaxNode n, List<String> out, int depth) {
            for (SyntaxNode c : n.namedChildren()) {
                String f = c.field();
                if ("value".equals(f) || "type".equals(f) || "default_value".equals(f) || "body".equals(f)
                        || c.type().contains("type") || c.type().contains("annotation") || c.type().contains("comment")
                        || c.type().equals("modifiers") || c.type().equals("decorator")) {
                    continue;
                }
                if (FIELD_NAME_TYPES.contains(c.type())) {
                    String name = c.text().strip().replace("$", "");
                    if (!name.isEmpty() && !out.contains(name)) {
                        out.add(name);
                    }
                } else if (depth < 3) {
                    fieldNames(c, out, depth + 1);
                }
            }
        }

        private String modifiers(SyntaxNode c) {
            List<String> words = new ArrayList<>();
            for (SyntaxNode k : c.children()) {
                String t = k.type();
                if (t.equals("modifiers") || t.equals("modifier") || t.equals("visibility_modifier")
                        || t.equals("storage_class_specifier") || t.equals("accessibility_modifier") || !k.isNamed()) {
                    Matcher m = MODIFIER.matcher(k.text());
                    while (m.find()) {
                        if (!words.contains(m.group())) {
                            words.add(m.group());
                        }
                    }
                }
            }
            return words.isEmpty() ? null : String.join(" ", words);
        }

        /** Kopf der Deklaration bis zum Rumpf, eine Zeile, höchstens 200 Zeichen. */
        private String signature(SyntaxNode c) {
            SyntaxNode body = c.child("body");
            String s = body != null && body.startByte() > c.startByte() ? tree.text(c.startByte(), body.startByte())
                    : c.text().lines().findFirst().orElse("");
            s = WS.matcher(s).replaceAll(" ").strip().replaceAll("[{:=]+$", "").strip();
            return s.length() > 200 ? s.substring(0, 197) + "…" : s;
        }

        /** Docstring (Python) bzw. Kommentar direkt über der Deklaration, erster Satz. */
        private String doc(SyntaxNode c) {
            if (lang == Language.PYTHON && c.child("body") != null && c.child("body").namedChildCount() > 0) {
                SyntaxNode first = c.child("body").namedChildren().getFirst();
                if (first.type().equals("expression_statement") && first.namedChildCount() == 1
                        && first.namedChildren().getFirst().type().equals("string")) {
                    return cleanDoc(first.text());
                }
            }
            SyntaxNode at = c;
            while (at.parent() != null && DOC_WRAPPERS.contains(at.parent().type())) {
                at = at.parent();
            }
            Deque<String> lines = new ArrayDeque<>();
            int expect = at.line() - 1;
            for (SyntaxNode prev = at.prevNamedSibling(); prev != null && prev.type().contains("comment")
                    && prev.endLine() >= expect && lines.size() < 30; prev = prev.prevNamedSibling()) {
                lines.addFirst(prev.text());
                expect = prev.line() - 1;
            }
            return lines.isEmpty() ? null : cleanDoc(String.join("\n", lines));
        }
    }

    static String cleanDoc(String raw) {
        StringBuilder sb = new StringBuilder();
        for (String l : raw.split("\\R")) {
            String s = l.strip().replaceAll("^(/\\*\\*|/\\*|///|//!|//|#|--|\"\"\"|'''|\\*)+", "")
                    .replaceAll("(\\*/|\"\"\"|''')$", "").strip();
            if (s.startsWith("@") || s.startsWith(":param") || s.startsWith(":return")) {
                break;
            }
            sb.append(s).append(' ');
        }
        String text = WS.matcher(sb.toString().replaceAll("\\{@\\w+\\s+([^}]*)}", "$1").replaceAll("<[^>]+>", ""))
                .replaceAll(" ").strip();
        int dot = text.indexOf(". ");
        if (dot > 0) {
            text = text.substring(0, dot + 1);
        }
        if (text.length() > 200) {
            text = text.substring(0, 197) + "…";
        }
        return text.isEmpty() ? null : text;
    }

    // ================================================================== Index über alle Dateien

    /** Deklarationen aller Dateien (Java eingeschlossen, für JVM-Sprachen) nach Namen – für Auflösung über Dateien. */
    static final class Index {
        final Map<String, TypeDecl> typesById = new HashMap<>();
        final Map<String, List<TypeDecl>> typesByName = new HashMap<>();
        /** Familie + Name → Funktionen außerhalb von Typen bzw. Methoden von Typen. */
        final Map<String, List<MemberDecl>> functionsByName = new HashMap<>();
        final Map<String, List<MemberDecl>> methodsByName = new HashMap<>();
        /** Besitzer (Modul, Fassade, Typ) → Member. */
        final Map<String, List<MemberDecl>> byOwner = new HashMap<>();
        /** Letztes Segment des Moduls ({@code util} für {@code web.src.util}) → Funktionen, für {@code util.f()}. */
        final Map<String, List<MemberDecl>> functionsByModuleTail = new HashMap<>();
        final Set<String> files = new HashSet<>();
        final Map<String, List<String>> filesByName = new HashMap<>();

        Index(List<FileDecl> decls, Collection<String> paths) {
            for (String p : paths) {
                files.add(p);
                filesByName.computeIfAbsent(p.substring(p.lastIndexOf('/') + 1), k -> new ArrayList<>()).add(p);
            }
            for (FileDecl d : decls) {
                Language l = GraphBuilder.isJava(d.path()) ? Language.JAVA : language(d.path());
                if (l == null) {
                    continue;
                }
                String fam = family(l);
                for (TypeDecl t : d.types()) {
                    if (typesById.putIfAbsent(t.fqn(), t) != null) {
                        continue;
                    }
                    typesByName.computeIfAbsent(fam + "|" + t.simpleName(), k -> new ArrayList<>()).add(t);
                    for (MemberDecl m : t.members()) {
                        byOwner.computeIfAbsent(m.owner(), k -> new ArrayList<>()).add(m);
                        if (m.kind() == Kind.METHOD) {
                            methodsByName.computeIfAbsent(fam + "|" + m.name(), k -> new ArrayList<>()).add(m);
                        }
                    }
                }
                for (MemberDecl m : d.functions()) {
                    byOwner.computeIfAbsent(m.owner(), k -> new ArrayList<>()).add(m);
                    String key = fam + "|" + m.name();
                    if (m.kind() == Kind.FIELD) {
                        continue;
                    }
                    if (typesById.containsKey(m.owner())) {
                        methodsByName.computeIfAbsent(key, k -> new ArrayList<>()).add(m); // Go/Rust: Methode eines Typs
                    } else {
                        functionsByName.computeIfAbsent(key, k -> new ArrayList<>()).add(m);
                        String tail = m.owner().substring(m.owner().lastIndexOf('.') + 1);
                        functionsByModuleTail.computeIfAbsent(fam + "|" + tail + "|" + m.name(),
                                k -> new ArrayList<>()).add(m);
                    }
                }
            }
        }

        /** Eindeutige Datei mit diesem Pfadende ({@code a/b.py} passt auf {@code src/a/b.py}). */
        String fileBySuffix(String suffix) {
            List<String> hits = filesByName.getOrDefault(suffix.substring(suffix.lastIndexOf('/') + 1), List.of())
                    .stream().filter(p -> p.equals(suffix) || p.endsWith("/" + suffix)).toList();
            return hits.size() == 1 ? hits.getFirst() : null;
        }
    }

    // ================================================================== Durchlauf 2: Referenzen

    static List<RawEdge> references(FileDecl decl, Language lang, String source, Index idx) {
        return new Refs(decl, lang, SyntaxEngine.parse(lang, source), idx).read();
    }

    /** Aufgelöste Ziele mit Sicherheit. */
    private record Hit(List<String> ids, Confidence conf, double score) {
        static final Hit NONE = new Hit(List.of(), Confidence.EXTRACTED, 0);

        static Hit of(List<String> ids, Confidence conf, double score) {
            if (ids.isEmpty()) {
                return NONE;
            }
            return ids.size() == 1 ? new Hit(ids, conf, score)
                    : new Hit(ids, Confidence.AMBIGUOUS, JavaExtractor.round(Math.min(score, 1.0) / ids.size()));
        }
    }

    private static final class Refs {
        private final FileDecl decl;
        private final Language lang;
        private final SyntaxTree tree;
        private final Index idx;
        private final String fam;
        private final String fileId;
        private final String fileOwner;
        /** Typen und Member dieser Datei, kleinster Bereich zuerst – für den umschließenden Knoten eines Aufrufs. */
        private final List<Scope> scopes = new ArrayList<>();
        private final List<RawEdge> edges = new ArrayList<>();

        private record Scope(String id, String type, int line, int endLine) {
        }

        Refs(FileDecl decl, Language lang, SyntaxTree tree, Index idx) {
            this.decl = decl;
            this.lang = lang;
            this.tree = tree;
            this.idx = idx;
            this.fam = family(lang);
            this.fileId = GraphBuilder.fileId(decl.path());
            for (TypeDecl t : decl.types()) {
                scopes.add(new Scope(t.fqn(), t.fqn(), t.line(), t.endLine()));
                for (MemberDecl m : t.members()) {
                    scopes.add(new Scope(m.id(), t.fqn(), m.line(), m.endLine()));
                }
            }
            for (MemberDecl m : decl.functions()) {
                scopes.add(new Scope(m.id(), idx.typesById.containsKey(m.owner()) ? m.owner() : null, m.line(),
                        m.endLine()));
            }
            this.fileOwner = fileOwner(decl.path(), lang, decl.pkg());
            scopes.sort(Comparator.comparingInt((Scope s) -> s.endLine() - s.line()));
        }

        List<RawEdge> read() {
            for (Import imp : decl.imports()) {
                String target = importTarget(imp);
                if (target != null) {
                    edges.add(new RawEdge(fileId, target, Relation.IMPORTS, Confidence.EXTRACTED, 1, imp.line()));
                }
            }
            for (TypeDecl t : decl.types()) {
                List<String> supers = new ArrayList<>();
                if (t.superclass() != null) {
                    supers.add(t.superclass());
                }
                supers.addAll(t.interfaces());
                for (String s : supers) {
                    Hit h = type(s);
                    if (h.ids().isEmpty()) {
                        edges.add(new RawEdge(t.fqn(), s, Relation.EXTENDS, Confidence.INFERRED, 0.5, t.line()));
                    }
                    for (String target : h.ids()) {
                        TypeDecl td = idx.typesById.get(target);
                        Relation rel = td != null && td.kind() == Kind.INTERFACE && t.kind() != Kind.INTERFACE
                                ? Relation.IMPLEMENTS : Relation.EXTENDS;
                        edges.add(new RawEdge(t.fqn(), target, rel, h.conf(), h.score(), t.line()));
                    }
                }
            }
            Deque<SyntaxNode> todo = new ArrayDeque<>();
            todo.push(tree.root());
            while (!todo.isEmpty()) { // iterativ: tief geschachtelte Ausdrücke sprengen sonst den Stack
                SyntaxNode n = todo.pop();
                switch (n.type()) {
                    case "call_expression", "call", "invocation_expression", "function_call_expression",
                         "member_call_expression", "nullsafe_member_call_expression", "scoped_call_expression",
                         "command" -> call(n);
                    case "new_expression", "object_creation_expression", "instance_creation_expression" -> creation(n);
                    default -> { }
                }
                List<SyntaxNode> kids = n.namedChildren();
                for (int i = kids.size() - 1; i >= 0; i--) {
                    todo.push(kids.get(i));
                }
            }
            return edges;
        }

        /** Import → Typ (JVM, PHP) bzw. Datei (JS/TS relativ, Python, C-Includes). */
        private String importTarget(Import imp) {
            String spec = imp.name();
            switch (fam) {
                case "jvm", "php" -> {
                    return imp.wildcard() || !idx.typesById.containsKey(spec) ? null : spec;
                }
                case "js" -> {
                    if (!spec.startsWith(".")) {
                        return null;
                    }
                    String base = normalize(dir(decl.path()) + "/" + spec);
                    for (String ext : List.of("", ".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs", ".d.ts", "/index.ts",
                            "/index.tsx", "/index.js", "/index.jsx")) {
                        if (idx.files.contains(base + ext)) {
                            return GraphBuilder.fileId(base + ext);
                        }
                    }
                    return null;
                }
                case "python" -> {
                    int dots = 0;
                    while (dots < spec.length() && spec.charAt(dots) == '.') {
                        dots++;
                    }
                    String rest = spec.substring(dots).replace('.', '/');
                    for (String cand : rest.isEmpty() ? List.of("__init__.py") : List.of(rest + ".py",
                            rest + "/__init__.py")) {
                        String hit;
                        if (dots > 0) {
                            String base = dir(decl.path());
                            for (int i = 1; i < dots; i++) {
                                base = dir(base);
                            }
                            hit = normalize(base + "/" + cand);
                            hit = idx.files.contains(hit) ? hit : null;
                        } else {
                            hit = idx.fileBySuffix(cand);
                        }
                        if (hit != null && !hit.equals(decl.path())) {
                            return GraphBuilder.fileId(hit);
                        }
                    }
                    return null;
                }
                case "c" -> {
                    String rel = normalize(dir(decl.path()) + "/" + spec);
                    String hit = idx.files.contains(rel) ? rel : idx.fileBySuffix(spec);
                    return hit == null ? null : GraphBuilder.fileId(hit);
                }
                default -> {
                    return null;
                }
            }
        }

        private static String dir(String path) {
            int slash = path.lastIndexOf('/');
            return slash < 0 ? "" : path.substring(0, slash);
        }

        private static String normalize(String p) {
            String n = Path.of(p.startsWith("/") ? p.substring(1) : p).normalize().toString().replace('\\', '/');
            return n.startsWith("./") ? n.substring(2) : n;
        }

        private Scope enclosing(int line) {
            for (Scope s : scopes) {
                if (s.line() <= line && line <= s.endLine()) {
                    return s;
                }
            }
            return null;
        }

        private void call(SyntaxNode n) {
            String name;
            SyntaxNode recv = null;
            SyntaxNode fn = null;
            switch (n.type()) {
                case "call" -> {
                    if (lang == Language.RUBY) {
                        name = text(n.child("method"));
                        recv = n.child("receiver");
                    } else {
                        name = null;
                        fn = n.child("function");
                    }
                }
                case "member_call_expression", "nullsafe_member_call_expression" -> {
                    name = text(n.child("name"));
                    recv = n.child("object");
                }
                case "scoped_call_expression" -> {
                    name = text(n.child("name"));
                    recv = n.child("scope");
                }
                case "command" -> name = text(n.child("name"));
                default -> {
                    name = null;
                    fn = n.child("function") != null ? n.child("function")
                            : n.namedChildCount() > 0 ? n.namedChildren().getFirst() : null;
                }
            }
            for (int i = 0; fn != null && i < 3 && name == null; i++) {
                if (NAME_TYPES.contains(fn.type())) {
                    name = fn.text();
                } else if (fn.type().equals("generic_function")) {
                    fn = fn.child("function");
                } else if (fn.child("name") != null && (fn.child("path") != null || fn.child("scope") != null)) {
                    recv = fn.child("path") != null ? fn.child("path") : fn.child("scope"); // a::b, A::b
                    name = fn.child("name").text();
                } else if (fn.namedChildCount() >= 2) {
                    recv = fn.namedChildren().getFirst();
                    name = fn.namedChildren().getLast().text();
                } else {
                    fn = null;
                }
            }
            name = simple(name);
            if (name == null) {
                return;
            }
            Scope from = enclosing(n.line());
            String fromId = from == null ? fileId : from.id();
            String recvText = recv == null ? null : WS.matcher(recv.text()).replaceAll("");
            boolean self = recvText != null && SELF.contains(recvText);
            if (recv == null || self) {
                String ownType = from == null ? null : from.type();
                if (ownType != null && (self || implicitThis(lang))) {
                    Hit h = Hit.of(inHierarchy(ownType, name), Confidence.EXTRACTED, 1);
                    if (emit(fromId, h, n)) {
                        return;
                    }
                }
                if (self) {
                    return;
                }
                if (emit(fromId, Hit.of(ids(named(idx.byOwner.get(fileOwner), name)), Confidence.EXTRACTED, 1), n)
                        || (!fileOwner.equals(module(decl.path(), lang)) && emit(fromId, Hit.of(ids(named(
                        idx.byOwner.get(module(decl.path(), lang)), name)), Confidence.EXTRACTED, 1), n))) {
                    return;
                }
                if (Character.isUpperCase(name.charAt(0))) {
                    Hit t = type(name);
                    if (!t.ids().isEmpty()) {
                        instantiate(fromId, t, n);
                        return;
                    }
                }
                if (!guessable(name)) {
                    return;
                }
                List<MemberDecl> fns = idx.functionsByName.getOrDefault(fam + "|" + name, List.of());
                emit(fromId, fns.size() > 3 ? Hit.NONE : Hit.of(ids(fns), Confidence.INFERRED, 0.7), n);
                return;
            }
            String head = simple(recvText);
            if (head != null && recvText.matches("[\\w$.:\\\\]+")) {
                if (Character.isUpperCase(head.charAt(0))) { // Typ.methode() – statisch
                    Hit t = type(head);
                    if (t.ids().size() == 1 && emit(fromId, Hit.of(inHierarchy(t.ids().getFirst(), name), t.conf(),
                            t.score()), n)) {
                        return;
                    }
                }
                List<MemberDecl> mod = idx.functionsByModuleTail.get(fam + "|" + head + "|" + name);
                if (mod != null && mod.size() <= 3 && emit(fromId, Hit.of(ids(mod), Confidence.INFERRED, 0.9), n)) {
                    return; // paket.funktion() – Go, Python-Module, Rust-Module
                }
            }
            if (!guessable(name)) {
                return;
            }
            List<MemberDecl> ms = idx.methodsByName.getOrDefault(fam + "|" + name, List.of());
            emit(fromId, ms.size() > 3 ? Hit.NONE : Hit.of(ids(ms), Confidence.INFERRED, 0.6), n);
        }

        private static boolean guessable(String name) {
            return !JavaExtractor.NO_GUESS.contains(name) && !NO_GUESS.contains(name) && name.length() > 1;
        }

        private void creation(SyntaxNode n) {
            SyntaxNode t = n.child("constructor") != null ? n.child("constructor")
                    : n.child("type") != null ? n.child("type")
                    : n.namedChildCount() > 0 ? n.namedChildren().getFirst() : null;
            String name = t == null ? null : simple(t.text());
            if (name == null) {
                return;
            }
            Scope from = enclosing(n.line());
            Hit h = type(name);
            if (!h.ids().isEmpty()) {
                instantiate(from == null ? fileId : from.id(), h, n);
            }
        }

        private void instantiate(String from, Hit types, SyntaxNode at) {
            for (String t : types.ids()) {
                edges.add(new RawEdge(from, t, Relation.INSTANTIATES, types.conf(), types.score(), at.line()));
            }
            if (types.ids().size() == 1) {
                List<MemberDecl> ctors = idx.byOwner.getOrDefault(types.ids().getFirst(), List.of()).stream()
                        .filter(m -> m.kind() == Kind.CONSTRUCTOR).toList();
                emit(from, Hit.of(ids(ctors), types.conf(), types.score()), at);
            }
        }

        private boolean emit(String from, Hit h, SyntaxNode at) {
            for (String id : h.ids()) {
                edges.add(new RawEdge(from, id, Relation.CALLS, h.conf(), h.score(), at.line()));
            }
            return !h.ids().isEmpty();
        }

        private static List<String> ids(List<MemberDecl> ms) {
            return ms == null ? List.of() : ms.stream().map(MemberDecl::id).toList();
        }

        private static List<MemberDecl> named(List<MemberDecl> ms, String name) {
            return ms == null ? List.of() : ms.stream().filter(m -> m.kind() != Kind.FIELD && m.name().equals(name))
                    .toList();
        }

        /** Methoden {@code name} im Typ, sonst in seinen Obertypen (nächste Ebene mit Treffern). */
        private List<String> inHierarchy(String type, String name) {
            Set<String> seen = new HashSet<>();
            List<String> level = List.of(type);
            for (int depth = 0; depth < 6 && !level.isEmpty(); depth++) {
                List<String> hits = new ArrayList<>();
                List<String> next = new ArrayList<>();
                for (String t : level) {
                    if (!seen.add(t)) {
                        continue;
                    }
                    hits.addAll(ids(named(idx.byOwner.get(t), name)));
                    TypeDecl td = idx.typesById.get(t);
                    if (td != null) {
                        if (td.superclass() != null) {
                            next.addAll(type(td.superclass()).ids());
                        }
                        td.interfaces().forEach(i -> next.addAll(type(i).ids()));
                    }
                }
                if (!hits.isEmpty()) {
                    return hits;
                }
                level = next;
            }
            return List.of();
        }

        /** Typ über seinen Namen: diese Datei, Imports, eigenes Paket/Modul, sonst projektweit (wenige Kandidaten). */
        private Hit type(String name) {
            String simpleName = simple(name);
            if (simpleName == null) {
                return Hit.NONE;
            }
            for (TypeDecl t : decl.types()) {
                if (t.simpleName().equals(simpleName)) {
                    return Hit.of(List.of(t.fqn()), Confidence.EXTRACTED, 1);
                }
            }
            for (Import imp : decl.imports()) {
                String cand = imp.wildcard() ? imp.name() + "." + simpleName
                        : imp.name().endsWith("." + simpleName) ? imp.name() : null;
                if (cand != null && idx.typesById.containsKey(cand)) {
                    return Hit.of(List.of(cand), Confidence.EXTRACTED, 1);
                }
            }
            for (String prefix : List.of(decl.pkg(), module(decl.path(), lang))) {
                String cand = prefix + "." + simpleName;
                if (!prefix.isEmpty() && idx.typesById.containsKey(cand)) {
                    return Hit.of(List.of(cand), Confidence.EXTRACTED, 1);
                }
            }
            List<TypeDecl> all = idx.typesByName.getOrDefault(fam + "|" + simpleName, List.of());
            return all.size() > 3 ? Hit.NONE : Hit.of(all.stream().map(TypeDecl::fqn).toList(), Confidence.INFERRED, 0.8);
        }
    }
}
