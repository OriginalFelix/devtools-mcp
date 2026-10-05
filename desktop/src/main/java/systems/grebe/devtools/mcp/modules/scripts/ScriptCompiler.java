package systems.grebe.devtools.mcp.modules.scripts;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import groovy.lang.Binding;
import groovy.lang.GroovyClassLoader;
import groovy.lang.GroovyShell;
import groovy.lang.Script;
import groovy.transform.CompileStatic;
import groovy.transform.ThreadInterrupt;
import groovy.transform.TypeChecked;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.customizers.ASTTransformationCustomizer;
import org.codehaus.groovy.control.customizers.ImportCustomizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import systems.grebe.devtools.mcp.backend.scripts.ScriptSyntax;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolBeans;

/**
 * Übersetzt ein Groovy-Skript und wertet es aus: Der Code auf oberster Ebene läuft genau einmal und legt über die DSL
 * ({@link DevToolsScript}) Modul, Einstellungen und Tools fest. Jedes Skript bekommt einen eigenen
 * {@link GroovyClassLoader} (Elternteil: der ClassLoader der App – Skripte sehen also auch deren Bibliotheken wie
 * Jackson oder JGit), der mit {@link Compiled#close()} freigegeben wird.
 *
 * <p>Fehler kommen als {@link IllegalArgumentException} mit Zeilenangabe, formuliert für Mensch und LLM.
 *
 * <p><b>Typprüfung:</b> Standard ist dynamisches Groovy. Eine Zeile {@code // devtools: compileStatic} (oder
 * {@code typeChecked}) im Skript prüft das ganze Skript – eigene Klassen, DSL und {@code execute}-Blöcke – beim Übersetzen
 * wie Java: unbekannte Methoden, falsche Typen und Tippfehler fallen schon beim Speichern auf (siehe {@link Mode}).
 */
public final class ScriptCompiler {

    /** Höchstdauer für das Auswerten der Definition (Code auf oberster Ebene). */
    static final Duration DEFINE_TIMEOUT = Duration.ofSeconds(10);

    private static final Logger LOG = LoggerFactory.getLogger(ScriptCompiler.class);
    /** {@code run { … }} – statisch übersetzt bindet Groovy das an {@code Closure.run()} (Endlosrekursion). */
    private static final Pattern RUN_BLOCK = Pattern.compile("(?<![\\w.])run\\s*\\{");
    private static final Pattern DIRECTIVE = Pattern.compile("(?m)^\\s*//\\s*devtools:\\s*(\\w+)\\s*$");

    /** Wie streng übersetzt wird – gewählt mit {@code // devtools: <modus>} im Skript. */
    public enum Mode {
        /** Dynamisches Groovy (Standard): Methoden und Eigenschaften werden erst zur Laufzeit aufgelöst. */
        DYNAMIC,
        /** {@code typeChecked}: Typen wie in Java prüfen, Ausführung bleibt dynamisch. */
        TYPE_CHECKED,
        /** {@code compileStatic}: prüfen und statisch übersetzen – Verhalten und Geschwindigkeit wie Java. */
        COMPILE_STATIC;

        /** Modus aus der Anweisung im Quelltext. */
        static Mode of(String source) {
            Matcher m = DIRECTIVE.matcher(source);
            if (!m.find()) {
                return DYNAMIC;
            }
            return switch (m.group(1).toLowerCase(Locale.ROOT)) {
                case "compilestatic", "static" -> COMPILE_STATIC;
                case "typechecked" -> TYPE_CHECKED;
                case "dynamic" -> DYNAMIC;
                default -> throw new IllegalArgumentException("Unbekannte Anweisung '// devtools: " + m.group(1)
                        + "' – erlaubt sind compileStatic, typeChecked und dynamic.");
            };
        }
    }

    /** Übersetztes Skript; hält den ClassLoader, solange das Modul registriert ist. */
    public record Compiled(ScriptDefinition definition, GroovyClassLoader loader, String scriptName)
            implements CompiledScript {

        @Override
        public String displayName() {
            return definition.displayName();
        }

        @Override
        public String description() {
            return definition.description();
        }

        @Override
        public String instructions() {
            return definition.instructions();
        }

        @Override
        public List<ConfigField> settings() {
            return definition.settings();
        }

        @Override
        public boolean enabledByDefault() {
            return definition.enabledByDefault();
        }

        @Override
        public List<String> toolNames() {
            return definition.tools().stream().map(ScriptDefinition.Tool::name).toList();
        }

        @Override
        public List<ToolCallback> tools(ModuleConfig config, Supplier<Duration> timeout) {
            Map<String, Object> values = typedSettings(definition.settings(), config);
            return definition.tools().stream()
                    .map(t -> ToolBeans.withAnnotations(new ScriptToolCallback(scriptName, t, values, timeout),
                            t.annotations()))
                    .toList();
        }

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
        Mode mode = Mode.of(source);
        if (mode != Mode.DYNAMIC && RUN_BLOCK.matcher(source).find()) {
            throw new IllegalArgumentException("Skript '" + scriptName + "': Mit Typprüfung heißt der Tool-Block "
                    + "execute { … } – run { … } kollidiert in Groovy mit Closure.run().");
        }
        switch (mode) {
            case COMPILE_STATIC -> cc.addCompilationCustomizers(new ASTTransformationCustomizer(CompileStatic.class));
            case TYPE_CHECKED -> cc.addCompilationCustomizers(new ASTTransformationCustomizer(TypeChecked.class));
            case DYNAMIC -> {
                // nichts
            }
        }
        GroovyShell shell = new GroovyShell(ScriptCompiler.class.getClassLoader(), new Binding(), cc);
        GroovyClassLoader loader = shell.getClassLoader();
        try {
            Script script;
            try {
                script = shell.parse(source, fileName(scriptName));
            } catch (CompilationFailedException e) {
                throw new IllegalArgumentException("Skript '" + scriptName + "' lässt sich nicht übersetzen: "
                        + ScriptSyntax.describe(e));
            }
            if (!(script instanceof DevToolsScript dsl)) {
                throw new IllegalArgumentException("Skript '" + scriptName + "' hat eine eigene Basisklasse – das ist "
                        + "nicht erlaubt.");
            }
            try {
                ScriptTimeout.run("Die Definition von Skript '" + scriptName + "'", DEFINE_TIMEOUT, dsl::run);
                return new Compiled(dsl.collector().build(scriptName), loader, scriptName);
            } catch (ScriptTimeout.Exceeded e) {
                throw new IllegalArgumentException(e.getMessage() + " Lang laufender Code gehört in execute { … }.");
            } catch (StackOverflowError e) {
                throw new IllegalArgumentException("Skript '" + scriptName + "': endlose Rekursion in der Definition"
                        + describe(e, scriptName).replaceFirst("^StackOverflowError", "") + ".");
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

    /** Einstellungen typgerecht für {@code cfg} in den Closures: Zahlen, Wahrheitswerte, Listen, Texte. */
    static Map<String, Object> typedSettings(List<ConfigField> fields, ModuleConfig config) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (ConfigField f : fields) {
            Object value = switch (f.type()) {
                case BOOLEAN -> config.getBoolean(f.key());
                case INT -> config.get(f.key()).map(v -> {
                    try {
                        return (Object) Long.parseLong(v);
                    } catch (NumberFormatException e) {
                        return null;
                    }
                }).orElse(null);
                case DIRECTORY_LIST, STRING_LIST -> config.getList(f.key());
                case RECORD_LIST -> config.getRecords(f.key());
                default -> config.get(f.key()).orElse(null);
            };
            out.put(f.key(), value);
        }
        return Collections.unmodifiableMap(out);
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
}
