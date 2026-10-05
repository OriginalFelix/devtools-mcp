package systems.grebe.devtools.mcp.modules.scripts;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

import groovy.lang.Binding;
import groovy.lang.GroovyClassLoader;
import groovy.lang.GroovyShell;
import groovy.lang.Script;
import groovy.transform.ThreadInterrupt;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.MultipleCompilationErrorsException;
import org.codehaus.groovy.control.customizers.ASTTransformationCustomizer;
import org.codehaus.groovy.control.customizers.ImportCustomizer;
import org.codehaus.groovy.control.messages.ExceptionMessage;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import org.codehaus.groovy.syntax.SyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import systems.grebe.devtools.mcp.core.FieldType;

/**
 * Übersetzt ein Groovy-Skript und wertet es aus: Der Code auf oberster Ebene läuft genau einmal und legt über die DSL
 * ({@link DevToolsScript}) Modul, Einstellungen und Tools fest. Jedes Skript bekommt einen eigenen
 * {@link GroovyClassLoader} (Elternteil: der ClassLoader der App – Skripte sehen also auch deren Bibliotheken wie
 * Jackson oder JGit), der mit {@link Compiled#close()} freigegeben wird.
 *
 * <p>Fehler kommen als {@link IllegalArgumentException} mit Zeilenangabe, formuliert für Mensch und LLM.
 */
public final class ScriptCompiler {

    /** Höchstdauer für das Auswerten der Definition (Code auf oberster Ebene). */
    static final Duration DEFINE_TIMEOUT = Duration.ofSeconds(10);

    private static final Logger LOG = LoggerFactory.getLogger(ScriptCompiler.class);

    /** Übersetztes Skript; hält den ClassLoader, solange das Modul registriert ist. */
    public record Compiled(ScriptDefinition definition, GroovyClassLoader loader) implements AutoCloseable {

        @Override
        public void close() {
            try {
                loader.clearCache();
                loader.close();
            } catch (IOException e) {
                LOG.debug("ClassLoader des Skripts nicht geschlossen: {}", e.toString());
            }
        }
    }

    /** Dateiname, unter dem das Skript übersetzt wird – erscheint in Stacktraces (siehe {@link #describe}). */
    static String fileName(String scriptName) {
        return "script_" + scriptName + ".groovy";
    }

    /**
     * Übersetzt und wertet aus.
     *
     * @throws IllegalArgumentException bei Syntax-, DSL- oder Laufzeitfehlern in der Definition
     */
    public Compiled compile(String scriptName, String source) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("Der Quelltext darf nicht leer sein.");
        }
        CompilerConfiguration cc = new CompilerConfiguration();
        cc.setScriptBaseClass(DevToolsScript.class.getName());
        ImportCustomizer imports = new ImportCustomizer();
        imports.addStaticStars(FieldType.class.getName());
        // Endlosschleifen in Tools lassen sich so per Interrupt (Zeitlimit) beenden
        cc.addCompilationCustomizers(imports, new ASTTransformationCustomizer(ThreadInterrupt.class));
        GroovyShell shell = new GroovyShell(ScriptCompiler.class.getClassLoader(), new Binding(), cc);
        GroovyClassLoader loader = shell.getClassLoader();
        try {
            Script script;
            try {
                script = shell.parse(source, fileName(scriptName));
            } catch (CompilationFailedException e) {
                throw new IllegalArgumentException("Skript '" + scriptName + "' lässt sich nicht übersetzen: "
                        + compileErrors(e));
            }
            if (!(script instanceof DevToolsScript dsl)) {
                throw new IllegalArgumentException("Skript '" + scriptName + "' hat eine eigene Basisklasse – das ist "
                        + "nicht erlaubt.");
            }
            try {
                ScriptTimeout.run("Die Definition von Skript '" + scriptName + "'", DEFINE_TIMEOUT, dsl::run);
                return new Compiled(dsl.collector().build(scriptName), loader);
            } catch (ScriptTimeout.Exceeded e) {
                throw new IllegalArgumentException(e.getMessage() + " Lang laufender Code gehört in run { … }.");
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Skript '" + scriptName + "': " + describe(e, scriptName));
            }
        } catch (RuntimeException e) {
            try {
                loader.close();
            } catch (IOException ignored) {
                // egal
            }
            throw e;
        }
    }

    /** Meldung mit Zeile im Skript (aus dem Stacktrace), ohne Groovy-Interna. */
    static String describe(Throwable e, String scriptName) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String msg = root.getMessage() == null || root.getMessage().isBlank()
                ? root.getClass().getSimpleName() : root.getMessage();
        if (root instanceof groovy.lang.MissingMethodException mme) {
            int hint = msg.indexOf("Possible solutions: ");
            msg = "Unbekannte Methode '" + mme.getMethod() + "' – Tippfehler in der DSL?"
                    + (hint < 0 ? "" : " Gemeint vielleicht: " + msg.substring(hint + 20).strip());
        } else if (root instanceof groovy.lang.MissingPropertyException mpe) {
            msg = "Unbekannte Variable/Eigenschaft '" + mpe.getProperty() + "'";
        }
        String file = fileName(scriptName);
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            for (StackTraceElement el : t.getStackTrace()) {
                if (file.equals(el.getFileName()) && el.getLineNumber() > 0) {
                    return msg + " (Zeile " + el.getLineNumber() + ")";
                }
            }
        }
        return msg;
    }

    private static String compileErrors(CompilationFailedException e) {
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
}
