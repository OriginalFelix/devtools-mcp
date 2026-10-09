package systems.grebe.devtools.mcp.modules.scripts;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import groovy.json.JsonSlurper;
import groovy.lang.Binding;
import groovy.lang.GroovyClassLoader;
import groovy.lang.GroovyObjectSupport;
import groovy.lang.GroovyShell;
import groovy.lang.Script;
import groovy.transform.ThreadInterrupt;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.customizers.ASTTransformationCustomizer;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.backend.scripts.ScriptSyntax;
import systems.grebe.devtools.mcp.core.ToolHints;

/**
 * {@code scripts_run}: führt ein kurzes Groovy-Programm einmalig aus, das andere Tools aufruft und ihre Ergebnisse
 * verarbeitet („Code Mode“). In den Kontext des LLM kommt nur, was das Programm ausgibt oder zurückgibt – nicht die
 * Zwischenergebnisse der aufgerufenen Tools. Statt fünf Tool-Aufrufen mit je einem langen Ergebnis also einer mit
 * der verdichteten Antwort.
 */
public class ScriptRunTools {

    static final String NAME = "run";
    /** Dateiname im Stacktrace – für Zeilenangaben in Fehlermeldungen. */
    static final String SCRIPT = "run";

    private final ToolCaller tools;
    private final Supplier<Duration> timeout;

    ScriptRunTools(ToolCaller tools, Supplier<Duration> timeout) {
        this.tools = tools;
        this.timeout = timeout;
    }

    @Tool(name = NAME, description = "Führt ein kurzes Groovy-Programm aus, das DevTools-Tools aufruft und deren "
            + "Ergebnisse filtert, verknüpft oder zählt – nur die Ausgabe (println) und der Rückgabewert kommen "
            + "zurück, nicht die Zwischenergebnisse. Lohnt sich, wenn mehrere Tool-Aufrufe nötig wären oder ein "
            + "Ergebnis lang ist, aber nur wenig daraus gebraucht wird. Im Programm: tools.<tool_name>(param: wert) "
            + "liefert das Ergebnis als Text (z.B. tools.git_status(path: '/repo')), tools.json(text) parst JSON, "
            + "tools.active('name') prüft ein Tool. Läuft mit allen Rechten der App.")
    @ToolHints(destructive = true, openWorld = true)
    public String run(@ToolParam(description = "Groovy-Code; letzter Ausdruck = Rückgabewert") String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Kein Code übergeben.");
        }
        CompilerConfiguration cc = new CompilerConfiguration();
        cc.addCompilationCustomizers(new ASTTransformationCustomizer(ThreadInterrupt.class));
        StringWriter printed = new StringWriter();
        Binding binding = new Binding();
        binding.setVariable("tools", new Tools(tools));
        binding.setVariable("out", new PrintWriter(printed, true));
        GroovyShell shell = new GroovyShell(ScriptRunTools.class.getClassLoader(), binding, cc);
        GroovyClassLoader loader = shell.getClassLoader();
        try {
            Script script;
            try {
                script = shell.parse(code, ScriptCompiler.fileName(SCRIPT));
            } catch (CompilationFailedException e) {
                throw new IllegalArgumentException("Der Code lässt sich nicht übersetzen: " + ScriptSyntax.describe(e));
            }
            Object value;
            try {
                value = ScriptTimeout.run("scripts_run", timeout.get(), script::run);
            } catch (ScriptTimeout.Exceeded e) {
                throw e;
            } catch (StackOverflowError e) {
                throw new IllegalArgumentException("Endlose Rekursion im Code.");
            } catch (RuntimeException e) {
                String out = printed.toString().strip();
                throw new IllegalStateException(ScriptCompiler.describe(e, SCRIPT)
                        + (out.isEmpty() ? "" : "\nAusgabe bis dahin:\n" + out), e);
            }
            return render(printed.toString(), value);
        } finally {
            try {
                loader.clearCache();
                loader.close();
            } catch (IOException ignored) {
                // egal
            }
        }
    }

    /** Ausgabe plus Rückgabewert (Text wie er ist, sonst kompaktes JSON). */
    static String render(String printed, Object value) {
        String out = printed == null ? "" : printed.strip();
        String v = value == null ? "" : ScriptToolCallback.render(value);
        if (out.isEmpty()) {
            return v.isEmpty() ? "(keine Ausgabe)" : v;
        }
        return v.isEmpty() ? out : out + "\n" + v;
    }

    /**
     * {@code tools} im Programm: jeder Methodenaufruf mit einem Tool-Namen ruft das Tool auf. Groovy leitet unbekannte
     * Methoden an {@link #invokeMethod} weiter – keine Reflection.
     */
    public static final class Tools extends GroovyObjectSupport {

        private final ToolCaller caller;

        Tools(ToolCaller caller) {
            this.caller = caller;
        }

        /** Tool mit Argumenten aufrufen, z.B. {@code tools.call('git_log', [limit: 5])}. */
        public String call(String name, Map<String, Object> args) {
            return caller.call(name, args == null ? Map.of() : new LinkedHashMap<>(args));
        }

        public String call(String name) {
            return call(name, Map.of());
        }

        /** JSON-Text als Map/List. */
        public Object json(String text) {
            return new JsonSlurper().parseText(text);
        }

        /** Ob ein Tool aktiv ist. */
        public boolean active(String name) {
            return caller.isActive(name);
        }

        /** Nur für Methoden, die es nicht gibt (call, json, active ruft Groovy direkt auf): Tool-Aufruf. */
        @Override
        @SuppressWarnings("unchecked")
        public Object invokeMethod(String name, Object args) {
            Object[] a = args instanceof Object[] arr ? arr : new Object[]{args};
            if (a.length > 1 || a.length == 1 && !(a[0] instanceof Map)) {
                throw new IllegalArgumentException("tools." + name + "(…): Parameter benannt übergeben, z.B. tools."
                        + name + "(path: '…').");
            }
            return call(name, a.length == 1 ? (Map<String, Object>) a[0] : Map.of());
        }
    }
}
