package systems.grebe.devtools.mcp.modules.scripts.assist;

import java.util.List;
import java.util.StringJoiner;
import java.util.stream.Collectors;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;

/** Vorschläge aus javac-Elementen: Methoden mit Parametern, Felder, Klassen – mit einfachen Typnamen wie in IntelliJ. */
final class Members {

    private Members() {
    }

    /** Typ mit einfachen Namen: {@code Map<String, List<Integer>>}, {@code String[]}, {@code int}. */
    static String type(TypeMirror t) {
        return switch (t.getKind()) {
            case DECLARED -> {
                DeclaredType d = (DeclaredType) t;
                String name = d.asElement().getSimpleName().toString();
                yield d.getTypeArguments().isEmpty() ? name : name + d.getTypeArguments().stream().map(Members::type)
                        .collect(Collectors.joining(", ", "<", ">"));
            }
            case ARRAY -> type(((ArrayType) t).getComponentType()) + "[]";
            case TYPEVAR -> ((TypeVariable) t).asElement().getSimpleName().toString();
            case WILDCARD -> {
                WildcardType w = (WildcardType) t;
                yield w.getExtendsBound() != null ? "? extends " + type(w.getExtendsBound())
                        : w.getSuperBound() != null ? "? super " + type(w.getSuperBound()) : "?";
            }
            case ERROR -> {
                String s = t.toString();
                yield s.substring(s.lastIndexOf('.') + 1);
            }
            default -> t.toString();
        };
    }

    /**
     * Methode als Vorschlag: {@code name(Typ name, …)} mit Rückgabetyp rechts. Einfügen setzt die Schreibmarke in die
     * Klammern, wenn es Parameter gibt; in Groovy wird eine Methode, deren einziger (restlicher) Parameter eine
     * Closure ist, als {@code name { … }} eingefügt.
     *
     * @param asMember Typ der Methode am Empfänger (Generics eingesetzt) oder {@code null}
     * @param skip     so viele Parameter vorne auslassen (1 bei GDK-Methoden: der Empfänger selbst)
     */
    static Completion method(ExecutableElement m, ExecutableType asMember, int skip, boolean groovy) {
        String name = m.getSimpleName().toString();
        List<? extends VariableElement> params = m.getParameters();
        List<? extends TypeMirror> types = asMember != null ? asMember.getParameterTypes()
                : params.stream().map(Element::asType).toList();
        TypeMirror ret = asMember != null ? asMember.getReturnType() : m.getReturnType();
        StringJoiner tail = new StringJoiner(", ", "(", ")");
        for (int i = skip; i < params.size(); i++) {
            String t = type(types.get(i));
            if (m.isVarArgs() && i == params.size() - 1 && t.endsWith("[]")) {
                t = t.substring(0, t.length() - 2) + "...";
            }
            String p = params.get(i).getSimpleName().toString();
            tail.add(p.matches("arg\\d+") ? t : t + " " + p);
        }
        int count = params.size() - skip;
        Completion c = Completion.of(Completion.Kind.METHOD, name).withTail(tail.toString()).withDetail(type(ret));
        if (groovy && count == 1 && isClosure(types.get(params.size() - 1))) {
            return c.withInsert(name + " {  }", name.length() + 3, 0);
        }
        return count == 0 ? c.withInsert(name + "()") : c.withInsert(name + "()", name.length() + 1, 0);
    }

    /** Methoden von {@code Object} ({@code wait}, {@code equals} …) stehen wie in IntelliJ weiter unten. */
    static boolean fromObject(Element e) {
        return e.getEnclosingElement() instanceof TypeElement owner
                && owner.getQualifiedName().contentEquals("java.lang.Object");
    }

    static Completion field(VariableElement f, TypeMirror type) {
        boolean constant = f.getKind() == ElementKind.ENUM_CONSTANT
                || f.getModifiers().contains(javax.lang.model.element.Modifier.STATIC)
                && f.getModifiers().contains(javax.lang.model.element.Modifier.FINAL);
        return Completion.of(constant ? Completion.Kind.CONSTANT : Completion.Kind.FIELD, f.getSimpleName().toString())
                .withDetail(type(type));
    }

    /** Klasse mit Paket als grauem Zusatz (wie IntelliJ: {@code HttpClient (java.net.http)}). */
    static Completion type(TypeElement t) {
        String pkg = packageOf(t);
        return Completion.of(kind(t), t.getSimpleName().toString()).withTail(pkg.isEmpty() ? null : " (" + pkg + ")");
    }

    static Completion.Kind kind(Element e) {
        return switch (e.getKind()) {
            case INTERFACE -> Completion.Kind.INTERFACE;
            case ENUM -> Completion.Kind.ENUM;
            case ANNOTATION_TYPE -> Completion.Kind.ANNOTATION;
            default -> Completion.Kind.CLASS;
        };
    }

    static String packageOf(Element e) {
        Element p = e;
        while (p != null && p.getKind() != ElementKind.PACKAGE) {
            p = p.getEnclosingElement();
        }
        return p == null ? "" : ((javax.lang.model.element.PackageElement) p).getQualifiedName().toString();
    }

    /** Groovy-Eigenschaft zu einem Getter ({@code getName()} → {@code name}, {@code isEmpty()} → {@code empty}). */
    static String property(ExecutableElement m) {
        if (!m.getParameters().isEmpty()) {
            return null;
        }
        String n = m.getSimpleName().toString();
        String rest;
        if (n.startsWith("get") && n.length() > 3 && !n.equals("getClass")
                && m.getReturnType().getKind() != javax.lang.model.type.TypeKind.VOID) {
            rest = n.substring(3);
        } else if (n.startsWith("is") && n.length() > 2
                && m.getReturnType().getKind() == javax.lang.model.type.TypeKind.BOOLEAN) {
            rest = n.substring(2);
        } else {
            return null;
        }
        if (!Character.isUpperCase(rest.charAt(0))) {
            return null;
        }
        // wie java.beans.Introspector: „URL“ bleibt „URL“
        if (rest.length() > 1 && Character.isUpperCase(rest.charAt(1))) {
            return rest;
        }
        return Character.toLowerCase(rest.charAt(0)) + rest.substring(1);
    }

    private static boolean isClosure(TypeMirror t) {
        return t instanceof DeclaredType d && d.asElement() instanceof TypeElement te
                && te.getQualifiedName().contentEquals("groovy.lang.Closure");
    }
}
