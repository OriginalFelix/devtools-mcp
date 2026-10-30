package systems.grebe.devtools.mcp.modules.graph;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import systems.grebe.devtools.mcp.modules.decompile.ClassFinder;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Kind;
import systems.grebe.devtools.mcp.modules.graph.JavaExtractor.MemberDecl;
import systems.grebe.devtools.mcp.modules.graph.JavaExtractor.TypeDecl;

/**
 * Klassenpfad für genauere Graphen: Typen aus dem JDK und aus Bibliotheks-JARs, gelesen mit {@code java.lang.classfile}
 * als {@link TypeDecl} (nicht-private Member, Obertypen, Rückgabetypen). JARs werden je Paket gesucht – zuerst die
 * ausdrücklich angegebenen und die im Projekt liegenden, dann in den Caches (Maven-Repository, Gradle-Cache), dort nur
 * unter Gruppen, deren Name mit den ersten zwei Paketsegmenten beginnt. Gibt es mehrere Versionen, gewinnt eine in den
 * Build-Dateien des Projekts genannte, sonst die höchste. Alles wird erst bei Bedarf gelesen und hier zwischengespeichert;
 * threadsicher (Referenzauflösung läuft parallel).
 *
 * <p>{@link #JDK_NAMES} kennt nur die Namen der JDK-Typen – für die Namensauflösung des gespeicherten Graphen
 * (Stern-Imports wie {@code java.util.*}, {@code java.lang}), ohne Member, damit er nicht mit JDK-Kanten wächst.
 */
final class Libraries implements AutoCloseable {

    static final Libraries JDK_NAMES = new Libraries(List.of(), List.of(), Set.of(), false);

    private static final List<String> ROOTS = List.of("", "BOOT-INF/classes/", "WEB-INF/classes/");
    private static final Set<String> JDK_PACKAGES = ModuleLayer.boot().modules().stream()
            .flatMap(m -> m.getPackages().stream()).collect(Collectors.toUnmodifiableSet());
    private static final String JDK = "JDK " + Runtime.version().feature();

    /** Pakete je JAR, über Graphen hinweg (Pfad, Größe und Änderungszeit als Schlüssel). */
    private static final Map<String, Set<String>> JAR_PACKAGES = new ConcurrentHashMap<>();

    /** Herkunft eines Pakets: JDK ({@code jar == null}) oder ein JAR. */
    private record Origin(Path jar, String label) {
    }

    private static final Origin NONE = new Origin(null, null);

    private final List<Path> pinned;
    private final List<Path> cacheRoots;
    private final Set<String> preferredVersions;
    private final boolean members;
    private final Map<String, Origin> byPackage = new ConcurrentHashMap<>();
    private final Map<String, List<Path>> cacheJars = new ConcurrentHashMap<>();
    private final Map<String, Optional<TypeDecl>> types = new ConcurrentHashMap<>();
    private final Map<String, Boolean> exists = new ConcurrentHashMap<>();
    private final Map<Path, ZipFile> zips = new ConcurrentHashMap<>();
    /** Benutzte Herkunft (JAR-Name bzw. JDK) → Anzahl gelesener Typen. */
    private final Map<String, Integer> used = new ConcurrentHashMap<>();

    /**
     * @param pinned            JARs oder Klassenverzeichnisse, die vor den Caches gelten (Projekt, ausdrücklich angegeben)
     * @param cacheRoots        Maven-Repository, Gradle-Cache …
     * @param preferredVersions Versionen aus den Build-Dateien des Projekts
     * @param members           {@code false} = nur JDK-Namen ({@link #JDK_NAMES})
     */
    Libraries(List<Path> pinned, List<Path> cacheRoots, Set<String> preferredVersions, boolean members) {
        this.pinned = pinned.stream().filter(p -> p.toString().endsWith(".jar") && Files.isRegularFile(p)).toList();
        this.cacheRoots = cacheRoots;
        this.preferredVersions = preferredVersions;
        this.members = members;
    }

    /** Liest Member und JARs ({@code false} nur bei {@link #JDK_NAMES}). */
    boolean hasMembers() {
        return members;
    }

    /** Gibt es diesen Typ (FQN mit Punkten, auch verschachtelt wie {@code java.util.Map.Entry})? */
    boolean exists(String fqn) {
        Boolean known = exists.get(fqn);
        if (known == null) {
            known = internalNames(fqn).stream().anyMatch(i -> members ? bytes(i) != null : jdkClass(i));
            exists.putIfAbsent(fqn, known);
        }
        return known;
    }

    /** Typ aus dem Klassenpfad oder {@code null} (immer bei {@link #JDK_NAMES}). */
    TypeDecl type(String fqn) {
        if (!members || fqn == null) {
            return null;
        }
        Optional<TypeDecl> t = types.get(fqn);
        if (t == null) {
            t = Optional.empty();
            for (String internal : internalNames(fqn)) {
                byte[] b = bytes(internal);
                if (b != null) {
                    String origin = origin(internal);
                    t = Optional.ofNullable(decl(b, origin));
                    t.ifPresent(x -> used.merge(origin, 1, Integer::sum));
                    break;
                }
            }
            Optional<TypeDecl> other = types.putIfAbsent(fqn, t);
            t = other != null ? other : t;
        }
        return t.orElse(null);
    }

    /** Herkunft eines Typs für Ausgaben ({@code JDK 25} bzw. JAR-Name) oder {@code null}. */
    String originOf(String fqn) {
        TypeDecl t = type(fqn);
        return t == null ? null : t.doc();
    }

    boolean isJdk(String fqn) {
        return JDK.equals(originOf(fqn));
    }

    Map<String, Integer> used() {
        return new TreeMap<>(used);
    }

    /** Mögliche interne Namen: {@code a.b.C.D} → {@code a/b/C$D}, {@code a/b/C/D} … (Großbuchstabe zuerst). */
    static List<String> internalNames(String fqn) {
        String[] s = fqn.split("\\.");
        List<String> out = new ArrayList<>();
        for (int i = s.length - 1; i >= 0; i--) {
            String pkg = String.join("/", java.util.Arrays.copyOf(s, i));
            String cls = String.join("$", java.util.Arrays.copyOfRange(s, i, s.length));
            String name = pkg.isEmpty() ? cls : pkg + "/" + cls;
            if (!cls.isEmpty() && Character.isUpperCase(s[i].charAt(0))) {
                out.addFirst(name);
            } else {
                out.add(name);
            }
        }
        return out.size() > 4 ? out.subList(0, 4) : out;
    }

    private static boolean jdkClass(String internal) {
        int slash = internal.lastIndexOf('/');
        return slash > 0 && JDK_PACKAGES.contains(internal.substring(0, slash).replace('/', '.'))
                && ClassLoader.getPlatformClassLoader().getResource(internal + ".class") != null;
    }

    private String origin(String internal) {
        int slash = internal.lastIndexOf('/');
        Origin o = packageOrigin(slash < 0 ? "" : internal.substring(0, slash).replace('/', '.'));
        return o.label();
    }

    /** Bytes einer Klasse (interner Name) oder {@code null}. */
    byte[] bytes(String internal) {
        int slash = internal.lastIndexOf('/');
        Origin o = packageOrigin(slash < 0 ? "" : internal.substring(0, slash).replace('/', '.'));
        if (o == NONE) {
            return null;
        }
        try {
            if (o.jar() == null) {
                try (InputStream in = ClassLoader.getPlatformClassLoader().getResourceAsStream(internal + ".class")) {
                    return in == null ? null : in.readAllBytes();
                }
            }
            ZipFile zip = zip(o.jar());
            for (String root : ROOTS) {
                ZipEntry e = zip.getEntry(root + internal + ".class");
                if (e != null) {
                    try (InputStream in = zip.getInputStream(e)) {
                        return in.readAllBytes();
                    }
                }
            }
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    private ZipFile zip(Path jar) throws IOException {
        ZipFile z = zips.get(jar);
        if (z == null) {
            synchronized (zips) {
                z = zips.get(jar);
                if (z == null) {
                    z = new ZipFile(jar.toFile());
                    zips.put(jar, z);
                }
            }
        }
        return z;
    }

    /** Woher ein Paket kommt; im JDK-Modus nur das JDK. */
    private Origin packageOrigin(String pkg) {
        Origin o = byPackage.get(pkg);
        if (o == null) {
            o = findOrigin(pkg);
            byPackage.putIfAbsent(pkg, o);
        }
        return o;
    }

    private Origin findOrigin(String pkg) {
        if (JDK_PACKAGES.contains(pkg)) {
            return new Origin(null, JDK);
        }
        if (!members || pkg.isEmpty()) {
            return NONE;
        }
        Path jar = best(pinned.stream().filter(j -> packages(j).contains(pkg)).toList());
        if (jar == null) {
            jar = best(cacheCandidates(pkg).stream().filter(j -> packages(j).contains(pkg)).toList());
        }
        return jar == null ? NONE : new Origin(jar, jar.getFileName().toString());
    }

    /** Version aus den Build-Dateien, sonst die höchste. */
    private Path best(List<Path> jars) {
        if (jars.size() <= 1) {
            return jars.isEmpty() ? null : jars.getFirst();
        }
        List<Path> preferred = jars.stream().filter(j -> preferredVersions.contains(ClassFinder.version(j))).toList();
        return (preferred.isEmpty() ? jars : preferred).stream()
                .max(Comparator.comparing((Path j) -> ClassFinder.parse(ClassFinder.version(j)))).orElseThrow();
    }

    /** JARs der Caches, deren Gruppe mit den ersten zwei Paketsegmenten beginnt (Gradle- und Maven-Layout). */
    private List<Path> cacheCandidates(String pkg) {
        String[] segs = pkg.split("\\.");
        if (segs.length < 2) {
            return List.of();
        }
        String key = segs[0] + "." + segs[1];
        return cacheJars.computeIfAbsent(key, k -> {
            List<Path> out = new ArrayList<>();
            for (Path root : cacheRoots) {
                if (!Files.isDirectory(root)) {
                    continue;
                }
                walkJars(root.resolve(segs[0]).resolve(segs[1]), 8, out); // Maven: org/foo/…
                try (Stream<Path> groups = Files.list(root)) { // Gradle: org.foo.bar/…
                    groups.filter(g -> {
                        String n = g.getFileName().toString();
                        return n.equals(k) || n.startsWith(k + ".");
                    }).forEach(g -> walkJars(g, 4, out));
                } catch (IOException ignored) {
                    // Cache nicht lesbar
                }
            }
            return out;
        });
    }

    private static void walkJars(Path dir, int depth, List<Path> out) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.walk(dir, depth, FileVisitOption.FOLLOW_LINKS)) {
            files.filter(p -> {
                String n = p.getFileName().toString();
                return n.endsWith(".jar") && !n.endsWith("-sources.jar") && !n.endsWith("-javadoc.jar");
            }).forEach(out::add);
        } catch (IOException | RuntimeException ignored) {
            // nicht lesbare Teilbäume überspringen
        }
    }

    /** Pakete eines JARs (zwischengespeichert). */
    static Set<String> packages(Path jar) {
        String key;
        try {
            key = jar + "|" + Files.size(jar) + "|" + Files.getLastModifiedTime(jar).toMillis();
        } catch (IOException e) {
            return Set.of();
        }
        return JAR_PACKAGES.computeIfAbsent(key, k -> {
            Set<String> out = new HashSet<>();
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    String n = stripRoot(entries.nextElement().getName());
                    if (n.endsWith(".class") && n.indexOf('/') > 0 && !n.startsWith("META-INF/")) {
                        out.add(n.substring(0, n.lastIndexOf('/')).replace('/', '.'));
                    }
                }
            } catch (IOException | RuntimeException e) {
                // kein gültiges Archiv
            }
            return Set.copyOf(out);
        });
    }

    private static String stripRoot(String name) {
        for (String root : ROOTS) {
            if (!root.isEmpty() && name.startsWith(root)) {
                return name.substring(root.length());
            }
        }
        return name;
    }

    /**
     * Klassen (FQN) eines Pakets und seiner Unterpakete bzw. eines JARs – für die Bibliotheksmuster eines dynamischen
     * Graphen. Verschachtelte, anonyme und synthetische Klassen bleiben außen vor (sie kommen über ihren äußeren Typ).
     */
    List<String> classes(String packagePrefix, Path jarOnly, int limit) {
        Set<String> out = new TreeSet<>();
        if (jarOnly == null && JDK_PACKAGES.stream().anyMatch(p -> inside(p, packagePrefix))) {
            var jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
            for (String pkg : JDK_PACKAGES.stream().filter(p -> inside(p, packagePrefix)).sorted().toList()) {
                try (Stream<Path> mods = Files.list(jrt.getPath("/packages", pkg))) {
                    for (Path mod : mods.toList()) {
                        Path dir = jrt.getPath("/modules", mod.getFileName().toString(), pkg.replace('.', '/'));
                        try (Stream<Path> files = Files.list(dir)) {
                            files.map(f -> f.getFileName().toString()).filter(Libraries::topLevelClass)
                                    .forEach(f -> out.add(pkg + "." + f.substring(0, f.length() - 6)));
                        }
                    }
                } catch (IOException ignored) {
                    // Paket ohne lesbares Modul
                }
                if (out.size() >= limit) {
                    break;
                }
            }
        }
        List<Path> jars = jarOnly != null ? List.of(jarOnly) : Stream.concat(pinned.stream(),
                cacheCandidates(packagePrefix).stream()).toList();
        Map<String, List<Path>> byPkg = new LinkedHashMap<>();
        for (Path j : jars) {
            for (String p : packages(j)) {
                if (jarOnly != null || inside(p, packagePrefix)) {
                    byPkg.computeIfAbsent(p, k -> new ArrayList<>()).add(j);
                }
            }
        }
        for (var e : new TreeMap<>(byPkg).entrySet()) {
            Path jar = jarOnly != null ? jarOnly : best(e.getValue());
            String dir = e.getKey().replace('.', '/') + "/";
            try {
                ZipFile zip = zip(jar);
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements() && out.size() < limit) {
                    String n = stripRoot(entries.nextElement().getName());
                    if (n.startsWith(dir) && n.indexOf('/', dir.length()) < 0 && topLevelClass(n.substring(dir.length()))) {
                        out.add(n.substring(0, n.length() - 6).replace('/', '.'));
                    }
                }
                byPackage.putIfAbsent(e.getKey(), new Origin(jar, jar.getFileName().toString()));
            } catch (IOException ignored) {
                // Archiv nicht lesbar
            }
            if (out.size() >= limit) {
                break;
            }
        }
        return new ArrayList<>(out);
    }

    private static boolean inside(String pkg, String prefix) {
        return prefix.isEmpty() || pkg.equals(prefix) || pkg.startsWith(prefix + ".");
    }

    private static boolean topLevelClass(String file) {
        return file.endsWith(".class") && !file.contains("$") && !file.equals("module-info.class")
                && !file.equals("package-info.class");
    }

    @Override
    public void close() {
        synchronized (zips) {
            for (ZipFile z : zips.values()) {
                try {
                    z.close();
                } catch (IOException ignored) {
                    // schon zu
                }
            }
            zips.clear();
        }
    }

    // ------------------------------------------------------------------ Klassendatei → Deklaration

    /** FQN aus internem Namen: {@code java/util/Map$Entry} → {@code java.util.Map.Entry}. */
    static String fqn(String internal) {
        return internal.replace('/', '.').replace('$', '.');
    }

    /** Typ einer Klassendatei; {@code null} für anonyme, lokale und synthetische Klassen. */
    static TypeDecl decl(byte[] bytes, String origin) {
        ClassModel cm = ClassFile.of().parse(bytes);
        String internal = cm.thisClass().asInternalName();
        int dollar = internal.lastIndexOf('$');
        String simpleInternal = internal.substring(Math.max(dollar, internal.lastIndexOf('/')) + 1);
        if (cm.flags().has(AccessFlag.SYNTHETIC) || simpleInternal.isEmpty()
                || Character.isDigit(simpleInternal.charAt(0))) {
            return null;
        }
        String fqn = fqn(internal);
        Kind kind = cm.flags().has(AccessFlag.ANNOTATION) ? Kind.ANNOTATION
                : cm.flags().has(AccessFlag.INTERFACE) ? Kind.INTERFACE
                : cm.flags().has(AccessFlag.ENUM) ? Kind.ENUM
                : cm.findAttribute(Attributes.record()).isPresent() ? Kind.RECORD : Kind.CLASS;
        String outer = dollar > internal.lastIndexOf('/') ? fqn(internal.substring(0, dollar)) : null;
        String superclass = cm.superclass().map(c -> fqn(c.asInternalName())).orElse(null);
        List<String> interfaces = cm.interfaces().stream().map(i -> fqn(i.asInternalName())).toList();
        List<MemberDecl> members = new ArrayList<>();
        for (MethodModel m : cm.methods()) {
            String name = m.methodName().stringValue();
            if (name.equals("<clinit>") || m.flags().has(AccessFlag.SYNTHETIC) || m.flags().has(AccessFlag.BRIDGE)
                    || m.flags().has(AccessFlag.PRIVATE)) {
                continue;
            }
            MethodTypeDesc d = m.methodTypeSymbol();
            List<String> params = d.parameterList().stream().map(Libraries::simple).toList();
            boolean ctor = name.equals("<init>");
            String ret = ctor ? null : type(d.returnType());
            String id = fqn + "#" + name + "(" + String.join(",", params) + ")";
            String sig = (ret == null ? "" : simple(d.returnType()) + " ") + (ctor ? simpleInternal : name) + "("
                    + String.join(", ", params) + ")";
            members.add(new MemberDecl(id, fqn, ctor ? Kind.CONSTRUCTOR : Kind.METHOD, ctor ? simpleInternal : name,
                    params, m.flags().has(AccessFlag.VARARGS), ret, 0, 0, modifiers(m.flags().flags()), List.of(),
                    List.of(), null, sig));
        }
        for (FieldModel f : cm.fields()) {
            if (f.flags().has(AccessFlag.SYNTHETIC) || f.flags().has(AccessFlag.PRIVATE)) {
                continue;
            }
            String name = f.fieldName().stringValue();
            members.add(new MemberDecl(fqn + "#" + name, fqn, Kind.FIELD, name, List.of(), false,
                    type(f.fieldTypeSymbol()), 0, 0, modifiers(f.flags().flags()), List.of(), List.of(), null,
                    simple(f.fieldTypeSymbol()) + " " + name));
        }
        return new TypeDecl(fqn, simpleInternal, kind, outer, null, 0, 0, modifiers(cm.flags().flags()), List.of(),
                List.of(), "java.lang.Object".equals(fqn) ? null : superclass, interfaces, members, origin);
    }

    /** Einfacher Name wie in den IDs des Projekts: {@code Map$Entry[]} → {@code Entry[]}. */
    static String simple(ClassDesc c) {
        if (c.isArray()) {
            return simple(c.componentType()) + "[]";
        }
        String n = c.displayName();
        return n.substring(n.lastIndexOf('$') + 1);
    }

    /** Typ als FQN ({@code void} → {@code null}). */
    static String type(ClassDesc c) {
        if (c.isArray()) {
            String t = type(c.componentType());
            return t == null ? null : t + "[]";
        }
        if (c.isPrimitive()) {
            return c.displayName().equals("void") ? null : c.displayName();
        }
        String d = c.descriptorString();
        return fqn(d.substring(1, d.length() - 1));
    }

    private static String modifiers(Collection<AccessFlag> flags) {
        List<String> words = new ArrayList<>();
        for (AccessFlag f : List.of(AccessFlag.PUBLIC, AccessFlag.PROTECTED, AccessFlag.STATIC, AccessFlag.ABSTRACT,
                AccessFlag.FINAL)) {
            if (flags.contains(f)) {
                words.add(f.name().toLowerCase(java.util.Locale.ROOT));
            }
        }
        return words.isEmpty() ? null : String.join(" ", words);
    }
}
