package systems.grebe.devtools.mcp.backend.scripts;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;

import groovy.lang.GroovyClassLoader;
import org.codehaus.groovy.ast.CodeVisitorSupport;
import org.codehaus.groovy.ast.expr.ArgumentListExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.MultipleCompilationErrorsException;
import org.codehaus.groovy.control.Phases;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.messages.ExceptionMessage;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import org.codehaus.groovy.syntax.SyntaxException;
import systems.grebe.devtools.mcp.modules.scripts.ScriptViews;

/**
 * Syntaxprüfung eines Skripts <em>ohne</em> es auszuführen – für das Backend (auch auf dem Team-Server, wo Skripte
 * nie laufen).
 *
 * <ul>
 *   <li><b>Groovy:</b> übersetzt nur bis zur Phase {@code CONVERSION} (Quelltext → AST). Lokale AST-Transformationen wie
 *       {@code @ASTTest} greifen erst danach, {@code @Grab} (würde Abhängigkeiten herunterladen) ist abgeschaltet. Die
 *       Beschreibung kommt aus {@code module { description '…' }}.</li>
 *   <li><b>Java:</b> nur parsen ({@code JavacTask#parse}, ohne Klassenpfad, ohne Annotation-Processing); Typfehler
 *       meldet erst die Desktop-App. Die Beschreibung kommt aus {@code description()} mit {@code return "…";}. Läuft
 *       das Backend ohne JDK, entfällt die Java-Prüfung.</li>
 *   <li><b>Gherkin:</b> parsen und die Regeln für Skripte prüfen ({@link GherkinScripts}); ob jeder Schritt zu einem
 *       bekannten Schritt passt, prüft erst die Desktop-App. Die Beschreibung ist der Freitext unter der
 *       {@code Funktionalität}.</li>
 * </ul>
 */
public final class ScriptSyntax {

    private static final Pattern PUBLIC_CLASS = Pattern.compile(
            "public\\s+(?:(?:final|abstract|sealed|non-sealed|strictfp)\\s+)*(?:class|record)\\s+(\\w+)");

    private ScriptSyntax() {
    }

    /**
     * Prüft je nach Sprache.
     *
     * @return die Beschreibung, falls sie als fester Text im Quelltext steht
     * @throws IllegalArgumentException bei Syntaxfehlern (mit Zeile und Spalte)
     */
    public static Optional<String> check(ScriptViews.Language language, String scriptName, String source) {
        if (language == ScriptViews.Language.JAVA) {
            return checkJava(source);
        }
        if (language == ScriptViews.Language.GHERKIN) {
            return Optional.of(GherkinScripts.parse(scriptName, source).description());
        }
        return check("script_" + scriptName + ".groovy", source);
    }

    /** Java: nur parsen; ohne JDK im Backend keine Prüfung. */
    static Optional<String> checkJava(String source) {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) {
            return Optional.empty();
        }
        Matcher m = PUBLIC_CLASS.matcher(source);
        String file = (m.find() ? m.group(1) : "Script") + ".java";
        JavaFileObject unit = new SimpleJavaFileObject(URI.create("string:///" + file), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        JavacTask task = (JavacTask) javac.getTask(null, null, diagnostics, List.of("-proc:none"), null,
                List.of(unit));
        String[] description = new String[1];
        try {
            task.parse().forEach(cu -> cu.accept(new TreeScanner<Void, Void>() {
                @Override
                public Void visitMethod(MethodTree method, Void unused) {
                    if (description[0] == null && method.getName().contentEquals("description")
                            && method.getParameters().isEmpty() && method.getBody() != null) {
                        List<? extends StatementTree> body = method.getBody().getStatements();
                        if (body.size() == 1 && body.getFirst() instanceof ReturnTree r
                                && r.getExpression() instanceof LiteralTree lit && lit.getValue() instanceof String s) {
                            description[0] = s;
                        }
                    }
                    return super.visitMethod(method, unused);
                }
            }, null));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        List<String> errors = diagnostics.getDiagnostics().stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .limit(5).map(d -> "Zeile " + d.getLineNumber() + ", Spalte " + d.getColumnNumber() + ": "
                        + d.getMessage(Locale.GERMAN).lines().map(String::strip).filter(l -> !l.isEmpty()).limit(3)
                        .collect(Collectors.joining(" – "))).toList();
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Das Java-Skript lässt sich nicht übersetzen: " + String.join("; ", errors));
        }
        return Optional.ofNullable(description[0]).map(String::strip).filter(d -> !d.isEmpty());
    }

    /**
     * @param fileName Dateiname für Meldungen, z.B. {@code script_jira.groovy}
     * @return die Beschreibung aus {@code module { description '…' }}, falls als fester Text angegeben
     * @throws IllegalArgumentException bei Syntaxfehlern (mit Zeile und Spalte)
     */
    public static Optional<String> check(String fileName, String source) {
        CompilerConfiguration cc = new CompilerConfiguration();
        cc.setDisabledGlobalASTTransformations(Set.of("groovy.grape.GrabAnnotationTransformation"));
        try (GroovyClassLoader loader = new GroovyClassLoader(ScriptSyntax.class.getClassLoader(), cc)) {
            CompilationUnit unit = new CompilationUnit(cc, null, loader);
            SourceUnit su = unit.addSource(fileName, source);
            try {
                unit.compile(Phases.CONVERSION);
            } catch (CompilationFailedException e) {
                throw new IllegalArgumentException("Das Skript lässt sich nicht übersetzen: " + describe(e));
            }
            DescriptionFinder finder = new DescriptionFinder();
            su.getAST().getStatementBlock().visit(finder);
            return Optional.ofNullable(finder.description).map(String::strip).filter(d -> !d.isEmpty());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Übersetzungsfehler kompakt: „Zeile 3, Spalte 5: …“, höchstens fünf. */
    public static String describe(CompilationFailedException e) {
        if (e instanceof MultipleCompilationErrorsException m && m.getErrorCollector().getErrorCount() > 0) {
            List<?> errors = m.getErrorCollector().getErrors();
            return errors.stream().limit(5).map(err -> {
                if (err instanceof SyntaxErrorMessage s) {
                    SyntaxException x = s.getCause();
                    return "Zeile " + x.getLine() + ", Spalte " + x.getStartColumn() + ": " + x.getOriginalMessage();
                }
                if (err instanceof ExceptionMessage x) {
                    return x.getCause().getMessage();
                }
                return String.valueOf(err);
            }).collect(Collectors.joining("; "));
        }
        return e.getMessage();
    }

    /** Sucht {@code module { description 'Text' }} auf oberster Ebene. */
    private static final class DescriptionFinder extends CodeVisitorSupport {
        String description;
        private boolean inModule;

        @Override
        public void visitMethodCallExpression(MethodCallExpression call) {
            String name = call.getMethodAsString();
            if (!inModule && call.isImplicitThis() && "module".equals(name)) {
                Expression closure = last(call.getArguments());
                if (closure instanceof ClosureExpression c) {
                    inModule = true;
                    c.getCode().visit(this);
                    inModule = false;
                }
                return;
            }
            if (inModule && description == null && call.isImplicitThis() && "description".equals(name)
                    && last(call.getArguments()) instanceof ConstantExpression text
                    && text.getValue() instanceof String s) {
                description = s;
                return;
            }
            super.visitMethodCallExpression(call);
        }

        private static Expression last(Expression args) {
            return args instanceof ArgumentListExpression list && !list.getExpressions().isEmpty()
                    ? list.getExpressions().getLast() : null;
        }
    }
}
