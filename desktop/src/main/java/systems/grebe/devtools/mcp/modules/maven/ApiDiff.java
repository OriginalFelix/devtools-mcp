package systems.grebe.devtools.mcp.modules.maven;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.InnerClassesAttribute;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Vergleicht die öffentliche API (public/protected Klassen, Methoden, Felder) zweier JARs auf Bytecode-Ebene und
 * meldet binär- bzw. quelltextinkompatible Änderungen – ähnlich japicmp/Revapi, aber ohne Abhängigkeiten.
 *
 * <p>Verglichen werden gelöschte Signaturen (Erasure), eingeschränkte Sichtbarkeit, neue {@code final}/{@code abstract}-
 * Modifier, Wechsel static/Instanz, geänderte Feldtypen, weggefallene Obertypen und neue abstrakte Methoden in
 * Interfaces/abstrakten Klassen. Methoden, die in eine Oberklasse desselben JARs verschoben wurden, gelten nicht als
 * gelöscht. Semantische Änderungen erkennt der Vergleich naturgemäß nicht.
 */
final class ApiDiff {

    private static final Pattern INTERNAL = Pattern.compile("(^|\\.)(internal|impl|shaded|shade|repackaged)(\\.|$)");
    /** In Interfaces redeklarierte Object-Methoden müssen nicht implementiert werden. */
    private static final Set<String> OBJECT_METHODS = Set.of("equals(Ljava/lang/Object;)Z", "hashCode()I",
            "toString()Ljava/lang/String;");

    /** Eine gefundene Änderung; {@code internal}, wenn sie in einem als intern erkennbaren Paket liegt. */
    record Change(String type, String element, String detail, boolean internal) {
    }

    record Result(List<Change> breaking, List<Change> potentiallyBreaking, int addedClasses, int addedMembers,
                  int oldClasses, int newClasses) {
    }

    /** {@code hasDefault}: Annotation-Element mit Standardwert. */
    private record Member(String name, String descriptor, Set<AccessFlag> flags, boolean hasDefault) {
        boolean isStatic() {
            return flags.contains(AccessFlag.STATIC);
        }
    }

    /**
     * Eine Klasse des JARs. Alle Klassen werden für die Typhierarchie gebraucht (Methoden wandern oft in
     * package-private Oberklassen); verglichen werden nur {@code exported}, also von außen sichtbare.
     */
    private record Api(String name, Set<AccessFlag> flags, boolean exported, String superName, List<String> interfaces,
                       Map<String, Member> methods, Map<String, Member> fields) {
        boolean isInterface() {
            return flags.contains(AccessFlag.INTERFACE);
        }

        boolean isAbstract() {
            return flags.contains(AccessFlag.ABSTRACT);
        }

        boolean isFinal() {
            return flags.contains(AccessFlag.FINAL);
        }

        /** Kann außerhalb des Pakets abgeleitet werden (nicht final, sichtbarer Konstruktor oder Interface). */
        boolean extensible() {
            if (isFinal() || flags.contains(AccessFlag.ENUM)) {
                return false;
            }
            return isInterface() || methods.values().stream().anyMatch(m -> m.name().equals("<init>"));
        }
    }

    private ApiDiff() {
    }

    static Result compare(byte[] oldJar, byte[] newJar) {
        Map<String, Api> before = read(oldJar);
        Map<String, Api> after = read(newJar);
        List<Change> breaking = new ArrayList<>();
        List<Change> potential = new ArrayList<>();
        int addedMembers = 0;

        for (Api o : before.values()) {
            if (!o.exported()) {
                continue;
            }
            Api n = after.get(o.name());
            String cls = display(o.name());
            boolean internal = INTERNAL.matcher(cls).find();
            if (n == null) {
                breaking.add(new Change("Klasse entfernt", cls, "", internal));
                continue;
            }
            if (!n.exported()) {
                breaking.add(new Change("Klasse nicht mehr öffentlich", cls, "", internal));
                continue;
            }
            if (o.isInterface() != n.isInterface()) {
                breaking.add(new Change("Klassenart geändert", cls,
                        (o.isInterface() ? "Interface" : "Klasse") + " → " + (n.isInterface() ? "Interface" : "Klasse"),
                        internal));
                continue;
            }
            if (!o.isFinal() && n.isFinal() && o.extensible()) {
                breaking.add(new Change("Klasse jetzt final", cls, "Unterklassen brechen", internal));
            }
            if (!o.isInterface() && !o.isAbstract() && n.isAbstract()) {
                breaking.add(new Change("Klasse jetzt abstract", cls, "Instanziierung bricht", internal));
            }
            Set<String> lost = new HashSet<>(supertypes(o.name(), before));
            lost.removeAll(supertypes(n.name(), after));
            // nicht sichtbare Obertypen konnte niemand verwenden
            lost.removeIf(s -> before.containsKey(s) && !before.get(s).exported());
            for (String s : lost.stream().sorted().toList()) {
                breaking.add(new Change("Obertyp entfernt", cls, display(s), internal));
            }

            for (Member m : o.methods().values()) {
                String key = m.name() + m.descriptor();
                Member nm = n.methods().get(key);
                String sig = methodSignature(cls, m);
                if (nm == null) {
                    nm = inherited(n, key, after);
                }
                if (nm == null) {
                    List<String> overloads = n.methods().values().stream()
                            .filter(x -> x.name().equals(m.name()) && !x.descriptor().equals(m.descriptor()))
                            .map(x -> methodSignature(cls, x)).toList();
                    breaking.add(new Change(m.name().equals("<init>") ? "Konstruktor entfernt" : "Methode entfernt", sig,
                            overloads.isEmpty() ? "" : "stattdessen vorhanden: " + String.join(", ", overloads), internal));
                    continue;
                }
                memberFlags(breaking, sig, m, nm, o, internal);
            }
            for (Member m : n.methods().values()) {
                String key = m.name() + m.descriptor();
                if (o.methods().containsKey(key)) {
                    continue;
                }
                addedMembers++;
                if (!m.flags().contains(AccessFlag.ABSTRACT) || inherited(o, key, before) != null
                        || OBJECT_METHODS.contains(key)) {
                    continue;
                }
                if (n.flags().contains(AccessFlag.ANNOTATION)) {
                    if (!m.hasDefault()) {
                        breaking.add(new Change("Neues Pflicht-Element in Annotation", methodSignature(cls, m),
                                "Verwendungen ohne diesen Wert kompilieren nicht mehr", internal));
                    }
                } else if (o.extensible()) {
                    potential.add(new Change(n.isInterface() ? "Neue abstrakte Interface-Methode" : "Neue abstrakte Methode",
                            methodSignature(cls, m), "eigene Implementierungen müssen sie ergänzen", internal));
                }
            }
            for (Member f : o.fields().values()) {
                Member nf = n.fields().get(f.name());
                String sig = cls + "." + f.name();
                if (nf == null) {
                    breaking.add(new Change("Feld entfernt", sig, typeName(f.descriptor()), internal));
                    continue;
                }
                if (!nf.descriptor().equals(f.descriptor())) {
                    breaking.add(new Change("Feldtyp geändert", sig,
                            typeName(f.descriptor()) + " → " + typeName(nf.descriptor()), internal));
                }
                memberFlags(breaking, sig, f, nf, o, internal);
            }
            addedMembers += (int) n.fields().keySet().stream().filter(k -> !o.fields().containsKey(k)).count();
        }
        int addedClasses = (int) after.values().stream()
                .filter(a -> a.exported() && (!before.containsKey(a.name()) || !before.get(a.name()).exported())).count();
        return new Result(breaking, potential, addedClasses, addedMembers, exportedCount(before), exportedCount(after));
    }

    private static int exportedCount(Map<String, Api> classes) {
        return (int) classes.values().stream().filter(Api::exported).count();
    }

    private static void memberFlags(List<Change> out, String sig, Member o, Member n, Api owner, boolean internal) {
        // Konstruktoren abstrakter Klassen sind ohnehin nur für Unterklassen nutzbar
        boolean abstractCtor = o.name().equals("<init>") && owner.isAbstract();
        if (o.flags().contains(AccessFlag.PUBLIC) && n.flags().contains(AccessFlag.PROTECTED) && !abstractCtor) {
            out.add(new Change("Sichtbarkeit eingeschränkt", sig, "public → protected", internal));
        }
        if (o.isStatic() != n.isStatic()) {
            out.add(new Change("static geändert", sig, o.isStatic() ? "static → Instanz" : "Instanz → static", internal));
        }
        if (!o.flags().contains(AccessFlag.FINAL) && n.flags().contains(AccessFlag.FINAL)) {
            // Feld: Schreibzugriffe brechen; Methode: nur relevant, wenn sie überschrieben werden konnte
            if (!o.descriptor().startsWith("(")) {
                out.add(new Change("Feld jetzt final", sig, "", internal));
            } else if (owner.extensible() && !o.isStatic() && !o.name().equals("<init>")) {
                out.add(new Change("Methode jetzt final", sig, "", internal));
            }
        }
        if (!owner.isInterface() && owner.extensible() && !o.flags().contains(AccessFlag.ABSTRACT)
                && n.flags().contains(AccessFlag.ABSTRACT)) {
            out.add(new Change("Methode jetzt abstract", sig, "", internal));
        }
    }

    /** Sucht eine sichtbare Methode in den Obertypen (innerhalb des JARs). */
    private static Member inherited(Api api, String key, Map<String, Api> all) {
        for (String s : supertypes(api.name(), all)) {
            Api sup = all.get(s);
            if (sup != null && sup.methods().containsKey(key)) {
                return sup.methods().get(key);
            }
        }
        return null;
    }

    /**
     * Transitive Obertypen. Typen außerhalb des JARs werden über die JDK-Klassen weiterverfolgt (z.B. erbt eine
     * Unterklasse von {@code Number} {@code Serializable}), sonstige fremde Typen nur aufgenommen.
     */
    private static Set<String> supertypes(String name, Map<String, Api> all) {
        Set<String> out = new HashSet<>();
        List<String> todo = new ArrayList<>(List.of(name));
        while (!todo.isEmpty()) {
            String current = todo.removeLast();
            Api a = all.get(current);
            List<String> direct = new ArrayList<>();
            if (a != null) {
                direct.addAll(a.interfaces());
                if (a.superName() != null) {
                    direct.add(a.superName());
                }
            } else {
                direct.addAll(jdkSupertypes(current));
            }
            for (String s : direct) {
                if (!s.equals("java/lang/Object") && out.add(s)) {
                    todo.add(s);
                }
            }
        }
        return out;
    }

    private static List<String> jdkSupertypes(String internalName) {
        if (!internalName.startsWith("java/") && !internalName.startsWith("javax/")) {
            return List.of();
        }
        try {
            Class<?> c = Class.forName(internalName.replace('/', '.'), false, ClassLoader.getPlatformClassLoader());
            List<String> out = new ArrayList<>();
            if (c.getSuperclass() != null) {
                out.add(c.getSuperclass().getName().replace('.', '/'));
            }
            for (Class<?> i : c.getInterfaces()) {
                out.add(i.getName().replace('.', '/'));
            }
            return out;
        } catch (ClassNotFoundException | LinkageError e) {
            return List.of();
        }
    }

    // ------------------------------------------------------------------ Einlesen

    private static Map<String, Api> read(byte[] jar) {
        Map<String, ClassModel> models = new LinkedHashMap<>();
        ClassFile cf = ClassFile.of();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jar))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                String n = e.getName();
                if (e.isDirectory() || !n.endsWith(".class") || n.startsWith("META-INF/")
                        || n.endsWith("module-info.class") || n.endsWith("package-info.class")) {
                    continue;
                }
                try {
                    ClassModel m = cf.parse(zip.readAllBytes());
                    models.put(m.thisClass().asInternalName(), m);
                } catch (IllegalArgumentException ignored) {
                    // nicht lesbare Klassendatei (z.B. neueres Format) überspringen
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("JAR nicht lesbar: " + ex.getMessage(), ex);
        }

        // Sichtbarkeit verschachtelter Klassen steht korrekt nur im InnerClasses-Attribut
        Map<String, Set<AccessFlag>> innerFlags = new HashMap<>();
        Set<String> anonymous = new HashSet<>();
        for (ClassModel m : models.values()) {
            m.findAttribute(Attributes.innerClasses()).map(InnerClassesAttribute::classes)
                    .ifPresent(list -> list.forEach(ic -> {
                        innerFlags.putIfAbsent(ic.innerClass().asInternalName(), ic.flags());
                        if (ic.innerName().isEmpty()) {
                            anonymous.add(ic.innerClass().asInternalName()); // anonyme Klassen und Lambdas (Kotlin)
                        }
                    }));
        }

        Map<String, Api> out = new TreeMap<>();
        for (ClassModel m : models.values()) {
            String name = m.thisClass().asInternalName();
            Set<AccessFlag> flags = m.flags().flags();
            Set<AccessFlag> inner = innerFlags.get(name);
            boolean exported = !m.flags().has(AccessFlag.SYNTHETIC) && visible(flags) && !anonymous.contains(name)
                    && (inner == null || visible(inner)) && outerVisible(name, models, innerFlags);
            if (inner != null) {
                // Final/abstract/Sichtbarkeit aus InnerClasses; static ist nur dort gesetzt und für die API unerheblich
                Set<AccessFlag> merged = new HashSet<>(flags);
                merged.removeAll(Set.of(AccessFlag.PUBLIC, AccessFlag.PROTECTED, AccessFlag.FINAL));
                inner.stream().filter(f -> f == AccessFlag.PUBLIC || f == AccessFlag.PROTECTED || f == AccessFlag.FINAL)
                        .forEach(merged::add);
                flags = merged;
            }
            Map<String, Member> methods = new LinkedHashMap<>();
            for (MethodModel mm : m.methods()) {
                Set<AccessFlag> f = mm.flags().flags();
                if (visible(f) && !f.contains(AccessFlag.SYNTHETIC) && !f.contains(AccessFlag.BRIDGE)
                        && !mm.methodName().stringValue().equals("<clinit>")) {
                    Member mem = new Member(mm.methodName().stringValue(), mm.methodType().stringValue(), f,
                            mm.findAttribute(Attributes.annotationDefault()).isPresent());
                    methods.put(mem.name() + mem.descriptor(), mem);
                }
            }
            Map<String, Member> fields = new LinkedHashMap<>();
            for (FieldModel fm : m.fields()) {
                Set<AccessFlag> f = fm.flags().flags();
                if (visible(f) && !f.contains(AccessFlag.SYNTHETIC)) {
                    fields.put(fm.fieldName().stringValue(),
                            new Member(fm.fieldName().stringValue(), fm.fieldType().stringValue(), f, false));
                }
            }
            out.put(name, new Api(name, flags, exported, m.superclass().map(c -> c.asInternalName()).orElse(null),
                    m.interfaces().stream().map(c -> c.asInternalName()).toList(), methods, fields));
        }
        return out;
    }

    /** Äußere Klassen einer verschachtelten Klasse müssen ebenfalls sichtbar sein. */
    private static boolean outerVisible(String name, Map<String, ClassModel> models, Map<String, Set<AccessFlag>> inner) {
        int idx = name.lastIndexOf('$');
        while (idx > 0) {
            String outer = name.substring(0, idx);
            ClassModel om = models.get(outer);
            if (om != null) {
                Set<AccessFlag> f = inner.getOrDefault(outer, om.flags().flags());
                if (!visible(f)) {
                    return false;
                }
            }
            idx = outer.lastIndexOf('$');
        }
        return true;
    }

    private static boolean visible(Set<AccessFlag> flags) {
        return flags.contains(AccessFlag.PUBLIC) || flags.contains(AccessFlag.PROTECTED);
    }

    // ------------------------------------------------------------------ Darstellung

    static String display(String internalName) {
        return internalName.replace('/', '.');
    }

    private static String methodSignature(String cls, Member m) {
        String d = m.descriptor();
        String params = d.substring(1, d.indexOf(')'));
        List<String> types = new ArrayList<>();
        int i = 0;
        while (i < params.length()) {
            int start = i;
            while (params.charAt(i) == '[') {
                i++;
            }
            if (params.charAt(i) == 'L') {
                i = params.indexOf(';', i);
            }
            i++;
            types.add(typeName(params.substring(start, i)));
        }
        String simple = cls.substring(cls.lastIndexOf('.') + 1);
        String name = m.name().equals("<init>") ? simple.substring(simple.lastIndexOf('$') + 1) : m.name();
        String ret = m.name().equals("<init>") ? "" : typeName(d.substring(d.indexOf(')') + 1)) + " ";
        return (m.isStatic() ? "static " : "") + ret + cls + "#" + name + "(" + String.join(", ", types) + ")";
    }

    /** Typ aus einem Feld-Deskriptor, mit einfachem Klassennamen (z.B. {@code List[]}). */
    static String typeName(String desc) {
        int dims = 0;
        while (desc.charAt(dims) == '[') {
            dims++;
        }
        String base = switch (desc.charAt(dims)) {
            case 'Z' -> "boolean";
            case 'B' -> "byte";
            case 'C' -> "char";
            case 'S' -> "short";
            case 'I' -> "int";
            case 'J' -> "long";
            case 'F' -> "float";
            case 'D' -> "double";
            case 'V' -> "void";
            default -> {
                String n = desc.substring(dims + 1, desc.length() - 1);
                yield n.substring(n.lastIndexOf('/') + 1).replace('$', '.');
            }
        };
        return base + "[]".repeat(dims);
    }
}
