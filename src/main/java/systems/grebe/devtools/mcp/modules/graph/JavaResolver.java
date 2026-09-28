package systems.grebe.devtools.mcp.modules.graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Kind;
import systems.grebe.devtools.mcp.modules.graph.JavaExtractor.Ctx;
import systems.grebe.devtools.mcp.modules.graph.JavaExtractor.FileDecl;
import systems.grebe.devtools.mcp.modules.graph.JavaExtractor.Import;
import systems.grebe.devtools.mcp.modules.graph.JavaExtractor.MemberDecl;
import systems.grebe.devtools.mcp.modules.graph.JavaExtractor.TypeDecl;

/**
 * Namensauflösung über alle Deklarationen eines Projekts – wie der Compiler, aber ohne Classpath:
 * Typparameter → verschachtelte/umschließende Typen (inkl. geerbter) → Einzel-Imports → eigenes Paket →
 * Stern-Imports → {@code java.lang} → vollqualifizierte Namen. Typen außerhalb des Projekts werden nur über
 * Einzel-Imports (bzw. {@code java.lang}) erkannt.
 */
final class JavaResolver {

    private static final Set<String> PRIMITIVES = Set.of("int", "long", "short", "byte", "char", "boolean", "float",
            "double", "void");
    private static final Set<String> JAVA_LANG = Set.of("Object", "String", "Integer", "Long", "Short", "Byte",
            "Character", "Boolean", "Float", "Double", "Number", "Math", "System", "Thread", "Runnable", "Iterable",
            "Comparable", "CharSequence", "StringBuilder", "StringBuffer", "Enum", "Record", "Class", "Void",
            "Exception", "RuntimeException", "Error", "Throwable", "IllegalArgumentException",
            "IllegalStateException", "NullPointerException", "UnsupportedOperationException", "Override",
            "Deprecated", "SuppressWarnings", "FunctionalInterface", "SafeVarargs", "AutoCloseable", "Cloneable",
            "InterruptedException", "IndexOutOfBoundsException", "ClassCastException", "ThreadLocal",
            "ClassNotFoundException", "CloneNotSupportedException", "ArithmeticException",
            "NumberFormatException", "ReflectiveOperationException", "SecurityException", "Process",
            "ProcessBuilder", "Runtime", "StackTraceElement", "Appendable", "Readable", "ClassLoader", "Module",
            "Package", "InheritableThreadLocal", "ScopedValue", "StrictMath");

    /** Platzhalter für „nicht auflösbar“ im Cache (ConcurrentHashMap erlaubt kein {@code null}). */
    private static final String UNRESOLVED = "\u0000unresolved";

    // Nur im Konstruktor befüllt, danach nur gelesen – von mehreren Threads gleichzeitig (GraphBuilder-Pool).
    private final Map<String, TypeDecl> types = new HashMap<>();
    private final Map<String, FileDecl> fileOf = new HashMap<>();
    private final Map<String, List<String>> typesByPackage = new HashMap<>();
    private final Map<String, List<MemberDecl>> methodsByName = new HashMap<>();

    // Caches: werden während der Referenzauflösung parallel beschrieben.
    private final Map<String, List<String>> supertypesCache = new ConcurrentHashMap<>();
    private final Map<String, String> resolveCache = new ConcurrentHashMap<>();
    /**
     * Zyklenschutz je Thread (A extends B, B extends A bzw. Auflösung, die über Obertypen zurückführt). Liegt bewusst
     * nicht im gemeinsamen Cache: ein Platzhalter dort wäre für andere Threads als „keine Obertypen“ sichtbar.
     * Wird geleert, sobald die äußerste Berechnung fertig ist – bleibt also nicht an Pool-Threads hängen.
     */
    private final ThreadLocal<Set<String>> supertypesInProgress = ThreadLocal.withInitial(HashSet::new);

    JavaResolver(List<FileDecl> files) {
        for (FileDecl f : files) {
            for (TypeDecl t : f.types()) {
                if (types.putIfAbsent(t.fqn(), t) != null) {
                    continue; // doppelte Klasse (z.B. zweites Quellverzeichnis): erste gewinnt
                }
                fileOf.put(t.fqn(), f);
                if (t.outer() == null) {
                    typesByPackage.computeIfAbsent(f.pkg(), k -> new ArrayList<>()).add(t.fqn());
                }
                for (MemberDecl m : t.members()) {
                    if (m.kind() == Kind.METHOD) {
                        methodsByName.computeIfAbsent(m.name(), k -> new ArrayList<>()).add(m);
                    }
                }
            }
        }
    }

    static boolean isPrimitive(String t) {
        return PRIMITIVES.contains(t);
    }

    boolean isProjectType(String fqn) {
        return fqn != null && types.containsKey(fqn);
    }

    TypeDecl type(String fqn) {
        return types.get(fqn);
    }

    String outerOf(String fqn) {
        TypeDecl t = types.get(fqn);
        return t == null ? null : t.outer();
    }

    String superclass(String fqn) {
        TypeDecl t = types.get(fqn);
        if (t == null || t.superclass() == null) {
            return null;
        }
        return resolveType(JavaExtractor.erase(t.superclass()), ctxOf(t));
    }

    /** Direkte Obertypen (Oberklasse zuerst), aufgelöst; externe als Name, sofern über Imports erkennbar. */
    List<String> supertypes(String fqn) {
        List<String> cached = supertypesCache.get(fqn);
        if (cached != null) {
            return cached;
        }
        Set<String> inProgress = supertypesInProgress.get();
        if (!inProgress.add(fqn)) {
            return List.of(); // Zyklus in dieser Berechnung – nicht cachen
        }
        try {
            TypeDecl t = types.get(fqn);
            List<String> out = new ArrayList<>();
            if (t != null) {
                Ctx ctx = ctxOf(t);
                if (t.superclass() != null) {
                    add(out, resolveType(JavaExtractor.erase(t.superclass()), ctx));
                }
                for (String i : t.interfaces()) {
                    add(out, resolveType(JavaExtractor.erase(i), ctx));
                }
            }
            List<String> result = List.copyOf(out);
            List<String> other = supertypesCache.putIfAbsent(fqn, result);
            return other != null ? other : result;
        } finally {
            inProgress.remove(fqn);
            if (inProgress.isEmpty()) {
                supertypesInProgress.remove();
            }
        }
    }

    private static void add(List<String> out, String v) {
        if (v != null && !out.contains(v)) {
            out.add(v);
        }
    }

    Ctx ctxOf(TypeDecl t) {
        Set<String> tps = new HashSet<>();
        for (TypeDecl cur = t; cur != null; cur = cur.outer() == null ? null : types.get(cur.outer())) {
            tps.addAll(cur.typeParams());
        }
        return new Ctx(fileOf.get(t.fqn()), t.fqn(), tps);
    }

    // ------------------------------------------------------------------ Typen auflösen

    /**
     * Löst einen (gelöschten) Typnamen im Kontext auf.
     *
     * @return FQN eines Projekttyps, Name eines externen Typs (Import/java.lang/vollqualifiziert) oder {@code null}
     */
    String resolveType(String name, Ctx ctx) {
        if (name == null || name.isEmpty() || isPrimitive(name) || name.endsWith("[]")) {
            return null;
        }
        if (ctx.typeParams().contains(name)) {
            return null;
        }
        String key = (ctx.type() == null ? ctx.file().path() : ctx.type()) + "|" + name;
        String cached = resolveCache.get(key);
        if (cached != null) {
            return UNRESOLVED.equals(cached) ? null : cached;
        }
        String result = doResolve(name, ctx);
        resolveCache.putIfAbsent(key, result == null ? UNRESOLVED : result);
        return result;
    }

    private String doResolve(String name, Ctx ctx) {
        int dot = name.indexOf('.');
        if (dot > 0) {
            // Outer.Inner oder vollqualifiziert
            String head = doResolveSimple(name.substring(0, dot), ctx);
            if (head != null && isProjectType(head)) {
                String cur = head;
                for (String part : name.substring(dot + 1).split("\\.")) {
                    String next = nested(cur, part);
                    if (next == null) {
                        return isProjectType(name) ? name : null;
                    }
                    cur = next;
                }
                return cur;
            }
            if (isProjectType(name)) {
                return name;
            }
            // pkg.Outer.Inner
            String[] parts = name.split("\\.");
            for (int i = parts.length - 1; i > 0; i--) {
                String prefix = String.join(".", java.util.Arrays.copyOf(parts, i));
                if (isProjectType(prefix)) {
                    String cur = prefix;
                    for (int j = i; j < parts.length && cur != null; j++) {
                        cur = nested(cur, parts[j]);
                    }
                    return cur;
                }
            }
            return Character.isLowerCase(name.charAt(0)) ? name : (head != null ? head + name.substring(dot) : null);
        }
        return doResolveSimple(name, ctx);
    }

    private String doResolveSimple(String name, Ctx ctx) {
        // 1. verschachtelte Typen des aktuellen und der umschließenden Typen (inkl. geerbter)
        for (String t = ctx.type(); t != null; t = outerOf(t)) {
            if (simpleName(t).equals(name)) {
                return t; // der Typ selbst bzw. ein umschließender
            }
            String n = nestedInHierarchy(t, name, new HashSet<>());
            if (n != null) {
                return n;
            }
        }
        FileDecl f = ctx.file();
        if (f == null) {
            return null;
        }
        // 2. Top-Level-Typen derselben Datei
        for (TypeDecl t : f.types()) {
            if (t.outer() == null && t.simpleName().equals(name)) {
                return t.fqn();
            }
        }
        // 3. Einzel-Imports
        for (Import imp : f.imports()) {
            if (!imp.isStatic() && !imp.wildcard() && imp.name().endsWith("." + name)) {
                return imp.name();
            }
        }
        // 4. eigenes Paket
        String same = f.pkg().isEmpty() ? name : f.pkg() + "." + name;
        if (isProjectType(same)) {
            return same;
        }
        // 5. Stern-Imports (Paket oder Typ)
        for (Import imp : f.imports()) {
            if (imp.wildcard()) {
                String candidate = imp.name() + "." + name;
                if (isProjectType(candidate)) {
                    return candidate;
                }
            }
        }
        // 6. java.lang
        if (JAVA_LANG.contains(name)) {
            return "java.lang." + name;
        }
        return null;
    }

    private static String simpleName(String fqn) {
        return fqn.substring(fqn.lastIndexOf('.') + 1);
    }

    /** Direkt verschachtelter Typ {@code outer.name}, falls vorhanden. */
    String nested(String outer, String name) {
        String candidate = outer + "." + name;
        TypeDecl t = types.get(candidate);
        return t != null && outer.equals(t.outer()) ? candidate : nestedInSupertypes(outer, name);
    }

    private String nestedInSupertypes(String type, String name) {
        for (String s : supertypes(type)) {
            if (isProjectType(s)) {
                String n = nestedInHierarchy(s, name, new HashSet<>());
                if (n != null) {
                    return n;
                }
            }
        }
        return null;
    }

    private String nestedInHierarchy(String type, String name, Set<String> seen) {
        if (type == null || !seen.add(type) || !isProjectType(type)) {
            return null;
        }
        String candidate = type + "." + name;
        TypeDecl t = types.get(candidate);
        if (t != null && type.equals(t.outer())) {
            return candidate;
        }
        for (String s : supertypes(type)) {
            String n = nestedInHierarchy(s, name, seen);
            if (n != null) {
                return n;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ Member

    /** Methoden {@code name} mit passender Stelligkeit – nächste Ebene der Hierarchie, in der es Treffer gibt. */
    List<MemberDecl> methods(String type, String name, int arity) {
        return searchHierarchy(type, m -> m.kind() == Kind.METHOD && m.name().equals(name) && m.accepts(arity));
    }

    List<MemberDecl> methodsAnyArity(String type, String name) {
        return searchHierarchy(type, m -> m.kind() == Kind.METHOD && m.name().equals(name));
    }

    List<MemberDecl> constructors(String type, int arity) {
        TypeDecl t = types.get(type);
        if (t == null) {
            return List.of();
        }
        return t.members().stream().filter(m -> m.kind() == Kind.CONSTRUCTOR && m.accepts(arity)).toList();
    }

    MemberDecl field(String type, String name) {
        List<MemberDecl> hits = searchHierarchy(type, m -> m.kind() == Kind.FIELD && m.name().equals(name));
        return hits.isEmpty() ? null : hits.getFirst();
    }

    /** Methode mit exakt diesen (einfachen, gelöschten) Parametertypen, nur im Typ selbst. */
    MemberDecl declaredMethod(String type, String name, List<String> paramTypes) {
        TypeDecl t = types.get(type);
        if (t == null) {
            return null;
        }
        for (MemberDecl m : t.members()) {
            if (m.kind() == Kind.METHOD && m.name().equals(name) && m.paramTypes().equals(paramTypes)) {
                return m;
            }
        }
        return null;
    }

    List<MemberDecl> methodsByName(String name, int arity) {
        return methodsByName.getOrDefault(name, List.of()).stream().filter(m -> m.accepts(arity)).toList();
    }

    List<MemberDecl> staticImportMethods(FileDecl f, String name, int arity) {
        List<MemberDecl> out = new ArrayList<>();
        for (Import imp : f.imports()) {
            if (!imp.isStatic()) {
                continue;
            }
            String owner;
            if (imp.wildcard()) {
                owner = imp.name();
            } else if (imp.name().endsWith("." + name)) {
                owner = imp.name().substring(0, imp.name().lastIndexOf('.'));
            } else {
                continue;
            }
            if (isProjectType(owner)) {
                out.addAll(methods(owner, name, arity));
            }
        }
        return out;
    }

    /** Aufgelöster Typ eines Felds bzw. Rückgabetyp einer Methode im Kontext ihres Besitzers. */
    String memberType(MemberDecl m) {
        TypeDecl owner = types.get(m.owner());
        if (owner == null || m.type() == null) {
            return null;
        }
        String erased = JavaExtractor.erase(m.type());
        Ctx ctx = ctxOf(owner);
        if (!m.typeParams().isEmpty()) {
            Set<String> tps = new HashSet<>(ctx.typeParams());
            tps.addAll(m.typeParams());
            ctx = new Ctx(ctx.file(), ctx.type(), tps);
        }
        return resolveType(erased, ctx);
    }

    String returnType(MemberDecl m) {
        return memberType(m);
    }

    private List<MemberDecl> searchHierarchy(String start, java.util.function.Predicate<MemberDecl> match) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> level = List.of(start);
        while (!level.isEmpty()) {
            List<MemberDecl> hits = new ArrayList<>();
            List<String> next = new ArrayList<>();
            for (String t : level) {
                if (!seen.add(t)) {
                    continue;
                }
                TypeDecl decl = types.get(t);
                if (decl == null) {
                    continue;
                }
                for (MemberDecl m : decl.members()) {
                    if (match.test(m)) {
                        hits.add(m);
                    }
                }
                next.addAll(supertypes(t));
            }
            if (!hits.isEmpty()) {
                return hits;
            }
            level = next;
        }
        return List.of();
    }
}
