package systems.grebe.devtools.mcp.modules.graph;

import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LineNumber;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.constant.ConstantDesc;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Confidence;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Kind;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Node;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Relation;
import systems.grebe.devtools.mcp.modules.graph.JavaExtractor.MemberDecl;
import systems.grebe.devtools.mcp.modules.graph.JavaExtractor.RawEdge;
import systems.grebe.devtools.mcp.modules.graph.JavaExtractor.TypeDecl;

/**
 * Bibliotheken im dynamischen Graphen. Die Klassen der Muster ({@code org.springframework.web.client.*}, eine Klasse,
 * ein Paket oder ein JAR) kommen mit allen nicht-privaten Membern hinein; ihre Kanten stammen aus dem Bytecode und sind
 * damit exakt (EXTRACTED): Aufrufe ({@code invoke*}, bis zur deklarierenden Klasse aufgelöst), Instanziierungen,
 * Methodenreferenzen; Lambdas und anonyme Klassen zählen zur umschließenden Methode. Dazu kommen Bibliothekstypen, die das
 * Projekt verwendet. Welche Bibliotheksknoten am Ende bleiben, entscheidet {@link #keep}.
 */
final class LibraryGraph {

    static final int MAX_CLASSES = 4000;

    private final Libraries libs;
    private final List<String> prefixes = new ArrayList<>();
    private final List<String> exact = new ArrayList<>();
    private final List<Pattern> globs = new ArrayList<>();
    private final List<Path> jars = new ArrayList<>();
    private final Set<String> jarNames = new HashSet<>();
    private final List<String> notes = new ArrayList<>();

    /**
     * @param patterns Paket ({@code org.foo}, {@code org.foo.*}), Klasse ({@code org.foo.Bar}), Muster mit {@code *}
     *                 ({@code org.foo.*Template}) oder Pfad zu einem JAR; leer = alle Bibliotheken außer dem JDK,
     *                 soweit das Projekt sie verwendet
     */
    LibraryGraph(Libraries libs, List<String> patterns) {
        this.libs = libs;
        for (String raw : patterns) {
            String p = raw.strip();
            if (p.isEmpty()) {
                continue;
            }
            if (p.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                Path jar = Path.of(p).toAbsolutePath().normalize();
                jars.add(jar);
                jarNames.add(jar.getFileName().toString());
            } else if (p.endsWith(".*") || p.endsWith(".**")) {
                prefixes.add(p.substring(0, p.indexOf(".*")));
            } else if (p.contains("*")) {
                globs.add(Pattern.compile(Pattern.quote(p).replace("*", "\\E.*\\Q")));
            } else if (Character.isUpperCase(p.charAt(p.lastIndexOf('.') + 1))) {
                exact.add(p);
            } else {
                prefixes.add(p);
            }
        }
    }

    /** JARs aus den Mustern – gehören in den Klassenpfad. */
    static List<Path> jarsOf(List<String> patterns) {
        return patterns.stream().map(String::strip).filter(p -> p.toLowerCase(Locale.ROOT).endsWith(".jar"))
                .map(p -> Path.of(p).toAbsolutePath().normalize()).toList();
    }

    boolean hasPatterns() {
        return !prefixes.isEmpty() || !exact.isEmpty() || !globs.isEmpty() || !jars.isEmpty();
    }

    List<String> notes() {
        return notes;
    }

    /** Bleibt ein Bibliothekstyp im Graphen? Mit Mustern nur passende, sonst alle außer dem JDK. */
    boolean keep(String typeFqn) {
        if (!hasPatterns()) {
            return !libs.isJdk(typeFqn);
        }
        for (String p : prefixes) {
            if (typeFqn.equals(p) || typeFqn.startsWith(p + ".")) {
                return true;
            }
        }
        for (String e : exact) {
            if (typeFqn.equals(e) || typeFqn.startsWith(e + ".")) {
                return true;
            }
        }
        for (Pattern g : globs) {
            if (g.matcher(typeFqn).matches()) {
                return true;
            }
        }
        return !jarNames.isEmpty() && jarNames.contains(libs.originOf(typeFqn));
    }

    /** Knoten für einen Bibliothekstyp bzw. -member, sonst {@code null}. */
    Node node(String id) {
        int hash = id.indexOf('#');
        TypeDecl t = libs.type(hash < 0 ? id : id.substring(0, hash));
        if (t == null) {
            return null;
        }
        String doc = "Bibliothek: " + t.doc();
        if (hash < 0) {
            return new Node(t.fqn(), t.kind(), t.simpleName(), null, null, null, t.modifiers(), null, doc, null);
        }
        for (MemberDecl m : t.members()) {
            if (m.id().equals(id)) {
                return new Node(id, m.kind(), m.name(), null, null, null, m.modifiers(), m.signature(), doc, null);
            }
        }
        return null;
    }

    /** Typ eines Bibliotheksknotens (Member → Besitzer). */
    static String typeOf(String id) {
        return CodeGraph.ownerOf(id);
    }

    // ------------------------------------------------------------------ Klassen der Muster

    /** Klassen der Muster (ohne verschachtelte – die liest {@link #bytecode} über ihren äußeren Typ). */
    List<String> classes() {
        Set<String> out = new LinkedHashSet<>();
        for (String p : prefixes) {
            out.addAll(libs.classes(p, null, MAX_CLASSES - out.size()));
        }
        for (Pattern g : globs) {
            String literal = g.pattern().replace("\\Q", "").replace("\\E", "");
            String head = literal.substring(0, literal.indexOf(".*"));
            head = head.contains(".") ? head.substring(0, head.lastIndexOf('.')) : "";
            libs.classes(head, null, MAX_CLASSES * 4).stream().filter(c -> g.matcher(c).matches())
                    .limit(Math.max(0, MAX_CLASSES - out.size())).forEach(out::add);
        }
        for (Path jar : jars) {
            out.addAll(libs.classes("", jar, MAX_CLASSES - out.size()));
        }
        out.addAll(exact);
        if (out.size() >= MAX_CLASSES) {
            notes.add("Höchstens " + MAX_CLASSES + " Klassen aus den Mustern übernommen – Muster enger fassen.");
        }
        return new ArrayList<>(out);
    }

    /**
     * Kanten der Klassen der Muster aus dem Bytecode: enthält (Member), Obertypen, Aufrufe, Instanziierungen,
     * Methodenreferenzen – samt verschachtelter, anonymer und lokaler Klassen.
     */
    List<RawEdge> bytecode(List<String> classes) {
        List<RawEdge> out = new ArrayList<>();
        Deque<String> todo = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        for (String c : classes) {
            todo.add(Libraries.internalNames(c).getFirst());
        }
        while (!todo.isEmpty()) {
            String internal = todo.poll();
            if (!seen.add(internal)) {
                continue;
            }
            byte[] bytes = libs.bytes(internal);
            if (bytes == null) {
                continue;
            }
            ClassModel cm = ClassFile.of().parse(bytes);
            cm.findAttribute(Attributes.innerClasses()).ifPresent(a -> a.classes().forEach(ic -> {
                String inner = ic.innerClass().asInternalName();
                if (inner.startsWith(internal + "$")) {
                    todo.add(inner);
                }
            }));
            String enclosing = enclosing(cm);
            TypeDecl t = enclosing == null ? libs.type(Libraries.fqn(internal)) : null;
            if (t != null) {
                for (MemberDecl m : t.members()) {
                    out.add(new RawEdge(t.fqn(), m.id(), Relation.CONTAINS, Confidence.EXTRACTED, 1, 0));
                }
            }
            for (MethodModel m : cm.methods()) {
                String from = enclosing != null ? enclosing : from(t, cm, m);
                if (from != null) {
                    m.code().ifPresent(code -> edges(from, code.elementList(), out));
                }
            }
        }
        return out;
    }

    /** Anonyme/lokale Klasse: die umschließende Methode (bzw. Klasse), der ihre Aufrufe zugerechnet werden. */
    private String enclosing(ClassModel cm) {
        String internal = cm.thisClass().asInternalName();
        String simple = internal.substring(internal.lastIndexOf('$') + 1);
        if (internal.indexOf('$') < 0 || simple.isEmpty() || !Character.isDigit(simple.charAt(0))) {
            return null;
        }
        return cm.findAttribute(Attributes.enclosingMethod()).map(em -> {
            String owner = Libraries.fqn(em.enclosingClass().asInternalName());
            return em.enclosingMethodName().map(n -> member(owner, n.stringValue(),
                    MethodTypeDesc.ofDescriptor(em.enclosingMethodType().orElseThrow().stringValue()))).orElse(owner);
        }).orElse(Libraries.fqn(internal.substring(0, internal.indexOf('$'))));
    }

    /** Knoten, dem die Kanten einer Methode gehören; Lambdas ({@code lambda$name$0}) der Methode {@code name}. */
    private String from(TypeDecl t, ClassModel cm, MethodModel m) {
        String name = m.methodName().stringValue();
        String owner = Libraries.fqn(cm.thisClass().asInternalName());
        if (name.startsWith("lambda$")) {
            String host = name.split("\\$")[1];
            if (host.equals("static") || host.equals("new")) {
                return owner;
            }
            return t == null ? owner : t.members().stream().filter(x -> x.name().equals(host)).map(MemberDecl::id)
                    .findFirst().orElse(owner);
        }
        if (m.flags().has(AccessFlag.SYNTHETIC) || m.flags().has(AccessFlag.BRIDGE)) {
            return null;
        }
        if (name.equals("<clinit>")) {
            return owner;
        }
        String id = member(owner, name, m.methodTypeSymbol());
        return t != null && t.members().stream().anyMatch(x -> x.id().equals(id)) ? id : owner; // private → Klasse
    }

    private void edges(String from, List<CodeElement> code, List<RawEdge> out) {
        int line = 0;
        for (CodeElement e : code) {
            switch (e) {
                case LineNumber ln -> line = ln.line();
                case InvokeInstruction inv -> {
                    String owner = inv.owner().asInternalName();
                    if (!owner.startsWith("[")) {
                        call(from, owner, inv.name().stringValue(), inv.typeSymbol(), line, out);
                    }
                }
                case NewObjectInstruction n -> {
                    String c = n.className().asInternalName();
                    String simple = c.substring(c.lastIndexOf('$') + 1);
                    if (simple.isEmpty() || !Character.isDigit(simple.charAt(0))) {
                        out.add(new RawEdge(from, Libraries.fqn(c), Relation.INSTANTIATES, Confidence.EXTRACTED, 1, line));
                    }
                }
                case InvokeDynamicInstruction indy -> {
                    for (ConstantDesc arg : indy.bootstrapArgs()) {
                        if (arg instanceof DirectMethodHandleDesc mh && !mh.methodName().startsWith("lambda$")) {
                            String owner = mh.owner().descriptorString();
                            call(from, owner.substring(1, owner.length() - 1), mh.methodName(),
                                    MethodTypeDesc.ofDescriptor(mh.lookupDescriptor()), line, out);
                        }
                    }
                }
                default -> { }
            }
        }
    }

    private void call(String from, String ownerInternal, String name, MethodTypeDesc d, int line, List<RawEdge> out) {
        if (name.equals("<clinit>") || name.startsWith("lambda$") || name.startsWith("access$")) {
            return;
        }
        String owner = Libraries.fqn(ownerInternal);
        String target = declaring(owner, name, d);
        if (name.equals("<init>")) {
            out.add(new RawEdge(from, owner, Relation.INSTANTIATES, Confidence.EXTRACTED, 1, line));
        }
        out.add(new RawEdge(from, target, Relation.CALLS, Confidence.EXTRACTED, 1, line));
    }

    private static String member(String owner, String name, MethodTypeDesc d) {
        List<String> params = d.parameterList().stream().map(Libraries::simple).toList();
        return owner + "#" + name + "(" + String.join(",", params) + ")";
    }

    /** Deklarierende Klasse eines Aufrufziels: {@code invokevirtual Sub.m} kann in einer Oberklasse stehen. */
    private String declaring(String owner, String name, MethodTypeDesc d) {
        String id = member(owner, name, d);
        String suffix = id.substring(id.indexOf('#'));
        Deque<String> todo = new ArrayDeque<>(List.of(owner));
        Set<String> seen = new HashSet<>();
        while (!todo.isEmpty() && seen.size() < 40) {
            String t = todo.poll();
            if (!seen.add(t)) {
                continue;
            }
            TypeDecl decl = libs.type(t);
            if (decl == null) {
                continue;
            }
            for (MemberDecl m : decl.members()) {
                if (m.id().equals(t + suffix)) {
                    return m.id();
                }
            }
            if (decl.superclass() != null) {
                todo.add(decl.superclass());
            }
            todo.addAll(decl.interfaces());
        }
        return id;
    }

    /** Methode, die {@code m} in einem Obertyp von {@code type} überschreibt (nächste Ebene), sonst leer. */
    List<String> overridden(String type, MemberDecl m) {
        if (m.kind() != Kind.METHOD || m.isStatic()) {
            return List.of();
        }
        TypeDecl t = libs.type(type);
        if (t == null) {
            return List.of();
        }
        String suffix = m.id().substring(m.id().indexOf('#'));
        List<String> out = new ArrayList<>();
        Deque<String> todo = new ArrayDeque<>();
        if (t.superclass() != null) {
            todo.add(t.superclass());
        }
        todo.addAll(t.interfaces());
        Set<String> seen = new HashSet<>();
        while (!todo.isEmpty() && seen.size() < 40) {
            String s = todo.poll();
            TypeDecl sd = seen.add(s) ? libs.type(s) : null;
            if (sd == null) {
                continue;
            }
            if (sd.members().stream().anyMatch(x -> x.id().equals(s + suffix) && !x.isStatic())) {
                out.add(s + suffix);
            } else {
                if (sd.superclass() != null) {
                    todo.add(sd.superclass());
                }
                todo.addAll(sd.interfaces());
            }
        }
        return out;
    }

    /** Member eines Bibliotheksknotens oder {@code null}. */
    MemberDecl memberDecl(String id) {
        TypeDecl t = libs.type(CodeGraph.ownerOf(id));
        return t == null ? null : t.members().stream().filter(m -> m.id().equals(id)).findFirst().orElse(null);
    }

    TypeDecl typeDecl(String fqn) {
        return libs.type(fqn);
    }
}
