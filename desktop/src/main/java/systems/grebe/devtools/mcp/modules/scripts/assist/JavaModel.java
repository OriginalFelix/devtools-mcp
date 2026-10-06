package systems.grebe.devtools.mcp.modules.scripts.assist;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import systems.grebe.devtools.mcp.modules.scripts.JavaClasspath;

/**
 * Typinformationen aus dem {@code javac} des JDK – ohne Klassen zu laden und ohne Reflection: javac liest die
 * Klassendateien des Klassenpfads der App selbst (wie beim Übersetzen der Java-Skripte). Für Groovy ein dauerhaftes
 * Symbolmodell ({@link Elements}/{@link Types}), für Java je Anfrage eine Analyse des ganzen Quelltexts – auch mit
 * Syntaxfehlern, wie sie beim Tippen entstehen. Der Dateimanager (mit seinen geöffneten Jars) wird wiederverwendet;
 * javac ist nicht threadsicher, daher läuft alles unter einer Sperre.
 *
 * <p>Ohne JDK (nur JRE) gibt es kein javac – die Vervollständigung kommt dann ohne Typinformationen aus.
 */
final class JavaModel {

    private static final Logger LOG = LoggerFactory.getLogger(JavaModel.class);
    private static final JavaModel SHARED = new JavaModel();

    /**
     * Analyse auch bei Fehlern fortsetzen (bis einschließlich Attributierung) – sonst gäbe es keine Typen;
     * {@code -parameters} lässt javac die Parameternamen aus den Klassendateien lesen (für die Anzeige).
     */
    private static final List<String> OPTIONS = List.of("-proc:none", "-implicit:none", "-Xlint:none", "-parameters",
            "-XDshould-stop.ifError=FLOW", "-XDshould-stop.ifNoError=FLOW", "-encoding", "UTF-8");

    private final JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
    private StandardJavaFileManager files;
    private JavacTask symbols;

    /** Ergebnis einer Analyse: der übersetzte Quelltext mit Zugriff auf Bäume, Elemente und Typen. */
    record Analysis(JavacTask task, CompilationUnitTree unit) {

        Trees trees() {
            return Trees.instance(task);
        }

        Elements elements() {
            return task.getElements();
        }

        Types types() {
            return task.getTypes();
        }
    }

    /** Zugriff auf das dauerhafte Symbolmodell. */
    @FunctionalInterface
    interface SymbolQuery<T> {
        T apply(Elements elements, Types types);
    }

    static JavaModel shared() {
        return SHARED;
    }

    boolean available() {
        return javac != null;
    }

    /** Fragt das dauerhafte Symbolmodell ab; ohne JDK {@code null}. */
    synchronized <T> T symbols(SymbolQuery<T> query) {
        if (javac == null) {
            return null;
        }
        if (symbols == null) {
            symbols = task(List.of());
        }
        return query.apply(symbols.getElements(), symbols.getTypes());
    }

    /**
     * Analysiert einen Java-Quelltext (Parsen und Attributieren). Fehler im Quelltext bleiben stumm – die Bäume sind
     * trotzdem so weit wie möglich mit Typen versehen.
     */
    synchronized <T> T analyze(String fileName, String source, Function<Analysis, T> body) {
        if (javac == null) {
            return null;
        }
        JavacTask task = task(List.of(new Source(fileName, source)));
        CompilationUnitTree unit;
        try {
            unit = task.parse().iterator().next();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            task.analyze();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (RuntimeException | AssertionError e) {
            // javac stolpert selten über halbfertigen Code – mit dem, was attribuiert ist, weitermachen
            LOG.debug("javac-Analyse für die Vervollständigung abgebrochen: {}", e.toString());
        }
        return body.apply(new Analysis(task, unit));
    }

    private JavacTask task(List<JavaFileObject> units) {
        if (files == null) {
            files = javac.getStandardFileManager(d -> { }, Locale.GERMAN, StandardCharsets.UTF_8);
            try {
                files.setLocationFromPaths(StandardLocation.CLASS_PATH, Arrays.stream(
                        JavaClasspath.get().split(File.pathSeparator)).filter(p -> !p.isBlank()).map(Path::of)
                        .toList());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return (JavacTask) javac.getTask(Writer.nullWriter(), files, d -> { }, OPTIONS, null, units);
    }

    private static final class Source extends SimpleJavaFileObject {
        private final String source;

        Source(String fileName, String source) {
            super(URI.create("string:///" + fileName), Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }
}
