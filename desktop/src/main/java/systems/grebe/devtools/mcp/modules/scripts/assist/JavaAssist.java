package systems.grebe.devtools.mcp.modules.scripts.assist;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ErroneousTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Scope;
import com.sun.source.tree.Tree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import systems.grebe.devtools.mcp.modules.scripts.assist.Completion.Kind;

/**
 * Autovervollständigung für Java-Skripte – semantisch über javac wie in einer IDE: Der Quelltext wird mit einem
 * Platzhalter an der Schreibmarke analysiert (auch halbfertig), dann liefern die Bäume und Typen von javac, was dort
 * passt: Member des Ausdrucks vor dem Punkt (mit Generics und Sichtbarkeit), Pakete und Klassen in Imports, Variablen,
 * Felder und Methoden im Gültigkeitsbereich, Schlüsselwörter je nach Stelle und Klassen aus dem Klassenpfad mit
 * automatischem Import.
 */
final class JavaAssist {

    /** Platzhalter an der Schreibmarke – ein gültiger Bezeichner, damit javac den Ausdruck vollständig sieht. */
    private static final String MARKER = "__dtCaret__";
    private static final int CLASS_LIMIT = 150;
    private static final Pattern PUBLIC_CLASS = Pattern.compile(
            "public\\s+(?:(?:final|abstract|sealed|non-sealed|strictfp)\\s+)*(?:class|record|interface|enum)\\s+(\\w+)");
    private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*import\\s+(static\\s+)?([\\w.]+)(\\.\\*)?\\s*;");

    private static final List<Completion> STATEMENT_KEYWORDS = keywords("if", "else", "for", "while", "do",
            "return", "new", "try", "catch", "finally", "throw", "switch", "case", "default", "break", "continue",
            "var", "final", "this", "super", "null", "true", "false", "instanceof", "yield", "assert", "synchronized",
            "boolean", "int", "long", "double", "char", "byte", "short", "float");
    private static final List<Completion> MEMBER_KEYWORDS = keywords("public", "private", "protected", "static",
            "final", "abstract", "synchronized", "void", "class", "interface", "enum", "record", "boolean", "int",
            "long", "double", "char", "byte", "short", "float", "transient", "volatile", "default");
    private static final List<Completion> TOP_KEYWORDS = keywords("import", "package", "public", "class",
            "interface", "enum", "record", "final", "abstract");

    private final JavaModel model;
    private final ClassIndex classes;

    JavaAssist(JavaModel model, ClassIndex classes) {
        this.model = model;
        this.classes = classes;
    }

    CompletionResult complete(String text, int caret) {
        CodeLexer lexer = CodeLexer.lex(text, false);
        if (lexer.inLiteral(caret)) {
            return CompletionResult.none(caret);
        }
        int from = caret;
        while (from > 0 && Character.isJavaIdentifierPart(text.charAt(from - 1))) {
            from--;
        }
        if (from < caret && Character.isDigit(text.charAt(from))) {
            return CompletionResult.none(caret);
        }
        String typed = text.substring(from, caret);
        int lineEnd = text.indexOf('\n', caret);
        boolean restBlank = text.substring(caret, lineEnd < 0 ? text.length() : lineEnd).isBlank();
        // ein Semikolon schließt eine angefangene Anweisung ab, damit javac die folgenden Zeilen nicht dazuliest
        String source = text.substring(0, caret) + MARKER + (restBlank ? ";" : "") + text.substring(caret);
        int start = from;
        CompletionResult result = model.analyze(fileName(text), source, a -> complete(a, text, start, typed));
        return result != null ? result : fallback(text, from, typed);
    }

    private static String fileName(String text) {
        Matcher m = PUBLIC_CLASS.matcher(text);
        return (m.find() ? m.group(1) : "Script") + ".java";
    }

    private CompletionResult complete(JavaModel.Analysis a, String text, int from, String typed) {
        TreePath path = find(a);
        if (path == null) {
            path = pathAt(a, from);
        }
        if (path == null) {
            return fallback(text, from, typed);
        }
        Context c;
        try {
            c = new Context(a, path, typed);
        } catch (RuntimeException e) { // kein Gültigkeitsbereich an dieser Stelle
            return fallback(text, from, typed);
        }
        Tree leaf = path.getLeaf();
        if (leaf instanceof MemberSelectTree ms && ms.getIdentifier().toString().contains(MARKER)) {
            c.memberSelect(ms);
        } else if (leaf instanceof MemberReferenceTree ref) {
            c.memberReference(ref);
        } else {
            c.identifier();
        }
        return new CompletionResult(from, c.out, c.incomplete, false);
    }

    private static TreePath find(JavaModel.Analysis a) {
        TreePath[] found = new TreePath[1];
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitIdentifier(IdentifierTree t, Void v) {
                if (found[0] == null && t.getName().toString().contains(MARKER)) {
                    found[0] = getCurrentPath();
                }
                return null;
            }

            @Override
            public Void visitMemberSelect(MemberSelectTree t, Void v) {
                if (found[0] == null && t.getIdentifier().toString().contains(MARKER)) {
                    found[0] = getCurrentPath();
                    return null;
                }
                return super.visitMemberSelect(t, v);
            }

            @Override
            public Void visitMemberReference(MemberReferenceTree t, Void v) {
                if (found[0] == null && t.getName().toString().contains(MARKER)) {
                    found[0] = getCurrentPath();
                    return null;
                }
                return super.visitMemberReference(t, v);
            }

            @Override
            public Void visitErroneous(ErroneousTree t, Void v) {
                return scan(t.getErrorTrees(), v); // halbfertiger Code („not a statement“) steckt hier drin
            }
        }.scan(new TreePath(a.unit()), null);
        return found[0];
    }

    /** Tiefster Baum, der die Position enthält – falls javac den Platzhalter nicht als Bezeichner sieht. */
    private static TreePath pathAt(JavaModel.Analysis a, int pos) {
        SourcePositions sp = a.trees().getSourcePositions();
        TreePath[] best = new TreePath[1];
        new TreePathScanner<Void, Void>() {
            @Override
            public Void scan(Tree tree, Void v) {
                if (tree == null) {
                    return null;
                }
                long s = sp.getStartPosition(a.unit(), tree);
                long e = sp.getEndPosition(a.unit(), tree);
                if (s >= 0 && s <= pos && pos <= e) {
                    best[0] = new TreePath(getCurrentPath(), tree);
                    return super.scan(tree, v);
                }
                return null;
            }

            @Override
            public Void visitErroneous(ErroneousTree t, Void v) {
                return scan(t.getErrorTrees(), v);
            }
        }.scan(new TreePath(a.unit()), null);
        return best[0];
    }

    /** Vorschläge an der gefundenen Stelle. */
    private final class Context {
        final JavaModel.Analysis a;
        final TreePath path;
        final Trees trees;
        final Elements el;
        final Types ty;
        final Scope scope;
        final String typed;
        final List<Completion> out = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        boolean incomplete;

        Context(JavaModel.Analysis a, TreePath path, String typed) {
            this.a = a;
            this.path = path;
            this.trees = a.trees();
            this.el = a.elements();
            this.ty = a.types();
            this.scope = trees.getScope(path);
            this.typed = typed;
        }

        // -------------------------------------------------------------- nach dem Punkt

        void memberSelect(MemberSelectTree ms) {
            TreePath exprPath = new TreePath(path, ms.getExpression());
            Element target = trees.getElement(exprPath);
            TypeMirror type = trees.getTypeMirror(exprPath);
            if (target != null && target.getKind() == ElementKind.PACKAGE) {
                packageMembers(((PackageElement) target).getQualifiedName().toString());
            } else if (target instanceof TypeElement te && isTypeName(ms.getExpression())) {
                staticMembers(te);
            } else if (type != null && type.getKind() != TypeKind.ERROR) {
                instanceMembers(type);
            } else if (target == null && ms.getExpression().toString().matches("[a-z_][\\w.]*")) {
                packageMembers(ms.getExpression().toString()); // z.B. in Imports ohne Symbol
            }
        }

        /** {@code Typ::|} oder {@code ausdruck::|}: Methoden. */
        void memberReference(MemberReferenceTree ref) {
            TreePath exprPath = new TreePath(path, ref.getQualifierExpression());
            Element target = trees.getElement(exprPath);
            TypeMirror type = target instanceof TypeElement te ? te.asType() : trees.getTypeMirror(exprPath);
            if (!(type instanceof DeclaredType dt)) {
                return;
            }
            for (Element e : el.getAllMembers((TypeElement) dt.asElement())) {
                if (e instanceof ExecutableElement m && m.getKind() == ElementKind.METHOD && accessible(e, dt)
                        && seen.add(m.getSimpleName().toString())) {
                    out.add(Completion.of(Kind.METHOD, m.getSimpleName().toString()).withDetail(
                            Members.type(m.getReturnType())));
                }
            }
            out.add(Completion.of(Kind.KEYWORD, "new"));
        }

        private boolean isTypeName(Tree expr) {
            return expr.getKind() == Tree.Kind.IDENTIFIER || expr.getKind() == Tree.Kind.MEMBER_SELECT
                    || expr.getKind() == Tree.Kind.PARAMETERIZED_TYPE;
        }

        private void instanceMembers(TypeMirror type) {
            TypeMirror t = type;
            while (t instanceof TypeVariable tv) {
                t = tv.getUpperBound();
            }
            if (t.getKind() == TypeKind.ARRAY) {
                out.add(Completion.of(Kind.FIELD, "length").withDetail("int").withPriority(5));
                out.add(Completion.of(Kind.METHOD, "clone").withTail("()").withDetail(Members.type(t))
                        .withInsert("clone()"));
                t = el.getTypeElement("java.lang.Object").asType();
            }
            if (!(t instanceof DeclaredType dt)) {
                return;
            }
            for (Element e : el.getAllMembers((TypeElement) dt.asElement())) {
                if (e.getModifiers().contains(Modifier.STATIC) || !accessible(e, dt)) {
                    continue;
                }
                if (e instanceof ExecutableElement m && m.getKind() == ElementKind.METHOD) {
                    addMethod(m, asMember(dt, m));
                } else if (e.getKind() == ElementKind.FIELD) {
                    addField((VariableElement) e, memberType(dt, e));
                }
            }
        }

        private void staticMembers(TypeElement te) {
            DeclaredType dt = (DeclaredType) te.asType();
            for (Element e : el.getAllMembers(te)) {
                if (!accessible(e, dt)) {
                    continue;
                }
                if (e instanceof TypeElement nested) {
                    if (seen.add("type:" + nested.getSimpleName())) {
                        out.add(Members.type(nested).withTail(null));
                    }
                } else if (!e.getModifiers().contains(Modifier.STATIC)) {
                    continue;
                } else if (e instanceof ExecutableElement m && m.getKind() == ElementKind.METHOD) {
                    addMethod(m, null);
                } else if (e instanceof VariableElement v) {
                    addField(v, v.asType());
                }
            }
            out.add(Completion.of(Kind.KEYWORD, "class").withDetail("Class"));
        }

        private void packageMembers(String pkg) {
            boolean inImport = ancestor(Tree.Kind.IMPORT);
            Set<String> names = new HashSet<>();
            PackageElement pe = el.getPackageElement(pkg);
            if (pe != null) {
                for (Element e : pe.getEnclosedElements()) {
                    if (e instanceof TypeElement te && te.getModifiers().contains(Modifier.PUBLIC)
                            && names.add(te.getSimpleName().toString())) {
                        out.add(Members.type(te).withTail(null));
                    }
                }
            }
            classes.startLoading();
            Set<String> sub = new HashSet<>();
            for (String p : classes.packages()) {
                if (p.startsWith(pkg + ".")) {
                    String rest = p.substring(pkg.length() + 1);
                    int dot = rest.indexOf('.');
                    sub.add(dot < 0 ? rest : rest.substring(0, dot));
                }
            }
            for (String s : sub) {
                out.add(Completion.of(Kind.PACKAGE, s).withPriority(1));
            }
            if (inImport) {
                out.add(Completion.of(Kind.KEYWORD, "*").withPriority(-1));
            }
        }

        // -------------------------------------------------------------- Bezeichner

        void identifier() {
            Tree leaf = path.getLeaf();
            Tree parent = path.getParentPath() == null ? null : path.getParentPath().getLeaf();
            boolean annotation = parent instanceof AnnotationTree at && at.getAnnotationType() == leaf;
            boolean newClass = parent instanceof NewClassTree nc && nc.getIdentifier() == leaf;
            if (!annotation && !newClass) {
                locals();
                enclosingMembers();
                out.addAll(keywords());
            }
            types(annotation, newClass);
        }

        private void locals() {
            for (Scope s = scope; s != null; s = s.getEnclosingScope()) {
                for (Element e : s.getLocalElements()) {
                    switch (e.getKind()) {
                        case LOCAL_VARIABLE, PARAMETER, EXCEPTION_PARAMETER, RESOURCE_VARIABLE, BINDING_VARIABLE -> {
                            String n = e.getSimpleName().toString();
                            if (!n.contains(MARKER) && seen.add("var:" + n)) {
                                out.add(Completion.of(e.getKind() == ElementKind.PARAMETER ? Kind.PARAMETER
                                        : Kind.VARIABLE, n).withDetail(Members.type(e.asType())).withPriority(50));
                            }
                        }
                        default -> {
                        }
                    }
                }
            }
        }

        private void enclosingMembers() {
            for (TypeElement c = scope.getEnclosingClass(); c != null; c = enclosingType(c)) {
                DeclaredType dt = (DeclaredType) c.asType();
                for (Element e : el.getAllMembers(c)) {
                    if (!accessible(e, dt)) {
                        continue;
                    }
                    if (e instanceof ExecutableElement m && m.getKind() == ElementKind.METHOD) {
                        addMethod(m, null);
                    } else if (e.getKind() == ElementKind.FIELD || e.getKind() == ElementKind.ENUM_CONSTANT) {
                        addField((VariableElement) e, e.asType());
                    } else if (e instanceof TypeElement nested && seen.add("type:" + nested.getSimpleName())) {
                        out.add(Members.type(nested).withTail(null).withPriority(5));
                    }
                }
            }
        }

        private List<Completion> keywords() {
            for (TreePath p = path; p != null; p = p.getParentPath()) {
                switch (p.getLeaf().getKind()) {
                    case BLOCK, LAMBDA_EXPRESSION, METHOD -> {
                        return STATEMENT_KEYWORDS;
                    }
                    case CLASS, INTERFACE, ENUM, RECORD -> {
                        return MEMBER_KEYWORDS;
                    }
                    case COMPILATION_UNIT -> {
                        return TOP_KEYWORDS;
                    }
                    default -> {
                    }
                }
            }
            return STATEMENT_KEYWORDS;
        }

        /** Klassen aus Imports und Klassenpfad; außerhalb von {@code java.lang} und dem eigenen Paket mit Import. */
        private void types(boolean annotation, boolean newClass) {
            if (typed.isEmpty() && !annotation) {
                return;
            }
            if (!classes.ready()) {
                classes.startLoading();
                incomplete = true;
                return;
            }
            String pkg = a.unit().getPackageName() == null ? "" : a.unit().getPackageName().toString();
            Set<String> imported = new HashSet<>();
            Set<String> starImported = new HashSet<>();
            for (ImportTree i : a.unit().getImports()) {
                if (i.isStatic()) {
                    continue;
                }
                String q = i.getQualifiedIdentifier().toString();
                if (q.endsWith(".*")) {
                    starImported.add(q.substring(0, q.length() - 2));
                } else {
                    imported.add(q);
                }
            }
            ClassIndex.Hits hits = classes.search(typed, CLASS_LIMIT);
            incomplete |= hits.truncated();
            for (ClassIndex.Entry e : hits.entries()) {
                String q = e.qualifiedName();
                if (!seen.add("type:" + q)) {
                    continue;
                }
                Completion.Kind kind = Kind.CLASS;
                if (annotation || newClass) {
                    TypeElement te = el.getTypeElement(q);
                    if (te == null || annotation && te.getKind() != ElementKind.ANNOTATION_TYPE
                            || newClass && (te.getKind() != ElementKind.CLASS && te.getKind() != ElementKind.RECORD
                            || te.getModifiers().contains(Modifier.ABSTRACT))) {
                        continue;
                    }
                    kind = Members.kind(te);
                }
                Completion c = Completion.of(kind, e.simpleName()).withTail(" (" + e.packageName() + ")")
                        .withPriority(-10);
                if (newClass) {
                    c = c.withInsert(e.simpleName() + "()", e.simpleName().length() + 1, 0);
                }
                boolean known = e.packageName().equals("java.lang") || e.packageName().equals(pkg)
                        || imported.contains(q) || starImported.contains(e.packageName());
                out.add(known ? c : c.withImport(q));
            }
        }

        // -------------------------------------------------------------- Hilfen

        private void addMethod(ExecutableElement m, ExecutableType asMember) {
            Completion c = Members.method(m, asMember, 0, false);
            if (Members.fromObject(m)) {
                c = c.withPriority(-3);
            }
            if (seen.add("m:" + c.label() + c.tail())) {
                out.add(c);
            }
        }

        private void addField(VariableElement f, TypeMirror type) {
            if (seen.add("f:" + f.getSimpleName())) {
                out.add(Members.field(f, type).withPriority(2));
            }
        }

        private boolean accessible(Element e, DeclaredType type) {
            if (e.getKind() == ElementKind.CONSTRUCTOR || e.getKind() == ElementKind.STATIC_INIT
                    || e.getKind() == ElementKind.INSTANCE_INIT) {
                return false;
            }
            try {
                return trees.isAccessible(scope, e, type);
            } catch (RuntimeException ex) {
                return e.getModifiers().contains(Modifier.PUBLIC);
            }
        }

        private ExecutableType asMember(DeclaredType dt, ExecutableElement m) {
            try {
                return (ExecutableType) ty.asMemberOf(dt, m);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        private TypeMirror memberType(DeclaredType dt, Element e) {
            try {
                return ty.asMemberOf(dt, e);
            } catch (IllegalArgumentException ex) {
                return e.asType();
            }
        }

        private TypeElement enclosingType(TypeElement c) {
            for (Element e = c.getEnclosingElement(); e != null; e = e.getEnclosingElement()) {
                if (e instanceof TypeElement te) {
                    return te;
                }
            }
            return null;
        }

        private boolean ancestor(Tree.Kind kind) {
            for (TreePath p = path; p != null; p = p.getParentPath()) {
                if (p.getLeaf().getKind() == kind) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Ohne JDK: Schlüsselwörter und Klassennamen. */
    private CompletionResult fallback(String text, int from, String typed) {
        List<Completion> out = new ArrayList<>(STATEMENT_KEYWORDS);
        boolean incomplete = false;
        if (!typed.isEmpty()) {
            if (classes.ready()) {
                Set<String> imported = new HashSet<>();
                Matcher m = IMPORT.matcher(text);
                while (m.find()) {
                    if (m.group(1) == null) {
                        imported.add(m.group(3) != null ? m.group(2) + ".*" : m.group(2));
                    }
                }
                ClassIndex.Hits hits = classes.search(typed, CLASS_LIMIT);
                incomplete = hits.truncated();
                for (ClassIndex.Entry e : hits.entries()) {
                    Completion c = Completion.of(Kind.CLASS, e.simpleName()).withTail(" (" + e.packageName() + ")")
                            .withPriority(-10);
                    boolean known = e.packageName().equals("java.lang") || imported.contains(e.qualifiedName())
                            || imported.contains(e.packageName() + ".*");
                    out.add(known ? c : c.withImport(e.qualifiedName()));
                }
            } else {
                classes.startLoading();
                incomplete = true;
            }
        }
        return new CompletionResult(from, out, incomplete, false);
    }

    private static List<Completion> keywords(String... words) {
        return java.util.Arrays.stream(words).map(w -> Completion.of(Kind.KEYWORD, w).withPriority(5)).toList();
    }
}
