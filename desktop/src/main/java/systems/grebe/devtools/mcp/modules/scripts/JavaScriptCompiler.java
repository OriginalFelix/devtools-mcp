package systems.grebe.devtools.mcp.modules.scripts;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.McpToolHints;
import systems.grebe.devtools.mcp.core.ToolModule;

/**
 * Übersetzt ein Java-Skript mit dem {@code javac} des JDK im Speicher. Ein Java-Skript ist eine Quelldatei mit einer
 * {@code public class}, die {@link ToolModule} implementiert – dieselbe API wie eingebaute Module und Plugins, Tools
 * also über {@code @Tool}-Methoden und {@link ToolBeans#callbacks}. Die Modul-ID ist immer der Skriptname, egal was
 * {@code id()} liefert.
 *
 * <p>Übersetzt wird gegen den Klassenpfad der App ({@link JavaClasspath}), ohne Annotation-Processing, mit
 * {@code -parameters} (Spring AI braucht die Parameternamen für das Eingabeschema). Jedes Skript bekommt einen eigenen
 * ClassLoader. Ohne JDK (nur JRE) gibt es keinen Compiler – dann meldet das Speichern das.
 */
public final class JavaScriptCompiler {

    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern PUBLIC_CLASS = Pattern.compile(
            "public\\s+(?:(?:final|abstract|sealed|non-sealed|strictfp)\\s+)*(?:class|record)\\s+(\\w+)");

    /** Übersetztes Java-Skript: das Modul des Skripts und sein ClassLoader. */
    public static final class Compiled implements CompiledScript {
        private final ToolModule module;
        private final ClassLoader loader;
        private final String scriptName;
        private final String fileName;

        Compiled(ToolModule module, ClassLoader loader, String scriptName, String fileName) {
            this.module = module;
            this.loader = loader;
            this.scriptName = scriptName;
            this.fileName = fileName;
        }

        @Override
        public String displayName() {
            String n = withLoader(module::displayName);
            return n == null || n.isBlank() ? scriptName : n;
        }

        @Override
        public String description() {
            return withLoader(module::description);
        }

        @Override
        public String instructions() {
            return withLoader(module::instructions);
        }

        @Override
        public List<ConfigField> settings() {
            List<ConfigField> s = withLoader(module::configSchema);
            return s == null ? List.of() : s;
        }

        @Override
        public boolean enabledByDefault() {
            // Wie bei Groovy-Skripten: wer ein Skript speichert, will es nutzen – außer das Modul sagt ausdrücklich nein
            return true;
        }

        @Override
        public List<String> toolNames() {
            try {
                return withLoader(() -> module.createTools(ModuleConfig.of(settings(), Map.of()))).stream()
                        .map(t -> t.getToolDefinition().name()).toList();
            } catch (RuntimeException e) {
                return List.of();
            }
        }

        @Override
        public List<ToolCallback> tools(ModuleConfig config, Supplier<Duration> timeout) {
            List<ToolCallback> tools = withLoader(() -> module.createTools(config));
            return tools.stream().map(cb -> McpToolHints.withAnnotations(
                    new TimedCallback(cb, loader, timeout, scriptName, fileName), McpToolHints.annotations(cb))).toList();
        }

        @Override
        public void close() {
            if (module instanceof AutoCloseable c) {
                try {
                    withLoader(() -> {
                        c.close();
                        return null;
                    });
                } catch (Exception ignored) {
                    // egal
                }
            }
        }

        private <T> T withLoader(ThrowingSupplier<T> call) {
            return JavaScriptCompiler.withLoader(loader, call);
        }
    }

    /**
     * Übersetzt und instanziiert das Modul des Skripts.
     *
     * @throws IllegalArgumentException bei Übersetzungsfehlern (mit Zeile), fehlendem Modul oder ohne JDK
     */
    public Compiled compile(String scriptName, String source) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("Der Quelltext darf nicht leer sein.");
        }
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) {
            throw new IllegalArgumentException("Java-Skripte brauchen ein JDK (javac): Die App läuft mit einer reinen "
                    + "Laufzeitumgebung (JRE). Mit einem JDK starten oder das Skript in Groovy schreiben.");
        }
        Matcher cls = PUBLIC_CLASS.matcher(source);
        if (!cls.find()) {
            throw new IllegalArgumentException("Java-Skript '" + scriptName + "' braucht eine public class, die "
                    + "ToolModule implementiert.");
        }
        Matcher pkg = PACKAGE.matcher(source);
        String packageName = pkg.find() ? pkg.group(1) : "";
        String simpleName = cls.group(1);
        String fileName = simpleName + ".java";
        String path = (packageName.isEmpty() ? "" : packageName.replace('.', '/') + "/") + fileName;

        Map<String, ByteArrayOutputStream> classes = new HashMap<>();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager standard = javac.getStandardFileManager(diagnostics, Locale.GERMAN, null);
        JavaFileManager files = new ForwardingJavaFileManager<>(standard) {
            @Override
            public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind,
                                                       FileObject sibling) {
                return new SimpleJavaFileObject(URI.create("mem:///" + className.replace('.', '/')
                        + kind.extension), kind) {
                    @Override
                    public OutputStream openOutputStream() {
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        classes.put(className, out);
                        return out;
                    }
                };
            }
        };
        JavaFileObject unit = new SimpleJavaFileObject(URI.create("string:///" + path), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        List<String> options = List.of("-classpath", JavaClasspath.get(), "-proc:none", "-parameters", "-g",
                "-encoding", "UTF-8", "-Xlint:-options");
        boolean ok = javac.getTask(null, files, diagnostics, options, null, List.of(unit)).call();
        if (!ok) {
            throw new IllegalArgumentException("Java-Skript '" + scriptName + "' lässt sich nicht übersetzen: "
                    + errors(diagnostics));
        }

        MemoryClassLoader loader = new MemoryClassLoader(classes, JavaScriptCompiler.class.getClassLoader());
        String mainName = packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
        Class<?> main;
        try {
            main = loader.loadClass(mainName);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Klasse " + mainName + " nicht gefunden.");
        } catch (LinkageError e) {
            throw new IllegalArgumentException("Java-Skript '" + scriptName + "': " + describe(e, fileName));
        }
        if (!ToolModule.class.isAssignableFrom(main) || Modifier.isAbstract(main.getModifiers())) {
            throw new IllegalArgumentException("Die Klasse " + simpleName + " muss ToolModule implementieren "
                    + "(import systems.grebe.devtools.mcp.core.ToolModule) und darf nicht abstrakt sein.");
        }
        ToolModule module;
        try {
            module = ScriptTimeout.run("Der Konstruktor von Java-Skript '" + scriptName + "'",
                    ScriptCompiler.DEFINE_TIMEOUT, () -> withLoader(loader, () ->
                            (ToolModule) main.getDeclaredConstructor().newInstance()));
        } catch (ScriptTimeout.Exceeded e) {
            throw new IllegalArgumentException(e.getMessage());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Java-Skript '" + scriptName + "': " + (e.getCause()
                    instanceof NoSuchMethodException ? "Die Klasse braucht einen öffentlichen Konstruktor ohne Parameter."
                    : describe(e, fileName)));
        }
        Compiled compiled = new Compiled(module, loader, scriptName, fileName);
        try {
            String description;
            try {
                description = compiled.description();
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Java-Skript '" + scriptName + "': description() wirft – "
                        + describe(e, fileName));
            }
            if (description == null || description.isBlank()) {
                throw new IllegalArgumentException("Java-Skript '" + scriptName + "': description() muss eine "
                        + "Beschreibung liefern – sie erscheint in der Modulliste und beim LLM.");
            }
        } catch (RuntimeException e) {
            compiled.close(); // das Modul ist schon gebaut und kann Threads oder Clients halten
            throw e;
        }
        return compiled;
    }

    private static String errors(DiagnosticCollector<JavaFileObject> diagnostics) {
        return diagnostics.getDiagnostics().stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR).limit(5)
                .map(d -> "Zeile " + d.getLineNumber() + ", Spalte " + d.getColumnNumber() + ": "
                        + message(d))
                .collect(Collectors.joining("; "));
    }

    /** javac schreibt Details („Symbol: Variable x“) in Folgezeilen – kompakt in eine Zeile. */
    static String message(Diagnostic<?> d) {
        return d.getMessage(Locale.GERMAN).lines().map(String::strip).filter(l -> !l.isEmpty()).limit(3)
                .collect(Collectors.joining(" – "));
    }

    /** Meldung mit Zeile im Skript (aus dem Stacktrace). */
    static String describe(Throwable e, String fileName) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String msg = root.getMessage() == null || root.getMessage().isBlank()
                ? root.getClass().getSimpleName() : root.getClass().getSimpleName() + ": " + root.getMessage();
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            for (StackTraceElement el : t.getStackTrace()) {
                if (fileName.equals(el.getFileName()) && el.getLineNumber() > 0) {
                    return msg + " (Zeile " + el.getLineNumber() + ")";
                }
            }
        }
        return msg;
    }

    @FunctionalInterface
    interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    /** Setzt für Aufrufe in Skript-Code den Context-ClassLoader auf den des Skripts (wie bei Plugins). */
    static <T> T withLoader(ClassLoader loader, ThrowingSupplier<T> call) {
        Thread t = Thread.currentThread();
        ClassLoader previous = t.getContextClassLoader();
        t.setContextClassLoader(loader);
        try {
            return call.get();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        } finally {
            t.setContextClassLoader(previous);
        }
    }

    /** Klassen aus dem Speicher; alles andere vom ClassLoader der App. */
    private static final class MemoryClassLoader extends ClassLoader {
        private final Map<String, ByteArrayOutputStream> classes;

        MemoryClassLoader(Map<String, ByteArrayOutputStream> classes, ClassLoader parent) {
            super("java-script", parent);
            this.classes = new HashMap<>(classes);
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            ByteArrayOutputStream bytes = classes.get(name);
            if (bytes == null) {
                throw new ClassNotFoundException(name);
            }
            byte[] b = bytes.toByteArray();
            return defineClass(name, b, 0, b.length);
        }
    }

    /** Tool-Aufruf unter Zeitlimit und mit dem ClassLoader des Skripts; Fehler mit Zeile im Skript. */
    private record TimedCallback(ToolCallback delegate, ClassLoader loader, Supplier<Duration> timeout,
                                 String scriptName, String fileName) implements ToolCallback {
        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return delegate.getToolMetadata();
        }

        @Override
        public String call(String toolInput) {
            return call(toolInput, null);
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            return ScriptTimeout.run("Tool '" + delegate.getToolDefinition().name() + "' (Skript '" + scriptName + "')",
                    timeout.get(), () -> {
                        try {
                            return withLoader(loader, () -> toolContext == null ? delegate.call(toolInput)
                                    : delegate.call(toolInput, toolContext));
                        } catch (ScriptTimeout.Exceeded e) {
                            throw e;
                        } catch (RuntimeException e) {
                            throw new IllegalStateException("Fehler im Skript '" + scriptName + "': "
                                    + describe(e, fileName), e);
                        }
                    });
        }
    }
}
