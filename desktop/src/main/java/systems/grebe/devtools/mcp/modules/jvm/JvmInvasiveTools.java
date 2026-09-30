package systems.grebe.devtools.mcp.modules.jvm;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.Text;
import systems.grebe.devtools.mcp.modules.java.ArtifactStore;
import systems.grebe.devtools.mcp.modules.java.JavaEnvironment;
import systems.grebe.devtools.mcp.modules.java.JvmTarget;
import systems.grebe.devtools.mcp.core.ShellHints;

/** Eingreifende Operationen; jede prüft beim Aufruf die Grundeinstellung „Invasive Operationen erlauben“. */
public class JvmInvasiveTools {

    private final Supplier<JavaEnvironment> env;
    private final Set<String> allowed;

    JvmInvasiveTools(Supplier<JavaEnvironment> env, List<String> allowedCommands) {
        this.env = env;
        this.allowed = Set.copyOf(allowedCommands);
    }

    @Tool(name = "heap_dump", description = "Schreibt einen Heap-Dump (.hprof) in den Ablageordner. Auswerten mit "
            + "visualvm_heap_analyze. Hält die JVM kurz an; Datei kann groß sein und vertrauliche Daten enthalten."
            + " Statt `jmap -dump`/`jcmd GC.heap_dump` verwenden." + ShellHints.JVM)
    public String heapDump(
            @ToolParam(required = false, description = JvmTools.TARGET) String target,
            @ToolParam(required = false, description = "true (Standard) = nur erreichbare Objekte (vorher Full GC)") Boolean liveOnly) {
        JavaEnvironment e = env.get();
        e.requireInvasive("Heap-Dump");
        JvmTarget t = e.target(target);
        Path file = t.heapDump(liveOnly == null || liveOnly, e.artifacts());
        return "Heap-Dump von " + t.describe() + " geschrieben:\n" + file + " (" + ArtifactStore.humanSize(size(file)) + ")\n"
                + "Nächster Schritt: visualvm_heap_analyze(file=\"" + file.getFileName() + "\")";
    }

    @Tool(name = "gc_run", description = "Erzwingt eine Garbage Collection (System.gc) und zeigt den Heap davor und danach."
            + " Statt `jcmd GC.run` verwenden." + ShellHints.JVM)
    public String gcRun(@ToolParam(required = false, description = JvmTools.TARGET) String target) {
        JavaEnvironment e = env.get();
        e.requireInvasive("GC erzwingen");
        JvmTarget t = e.target(target);
        String before = t.dcmd("GC.heap_info");
        t.dcmd("GC.run");
        String after = t.dcmd("GC.heap_info");
        return t.describe() + "\n\nVorher:\n" + before + "\n\nNachher:\n" + after;
    }

    @Tool(name = "jcmd", description = "Führt einen freigegebenen jcmd-Diagnosebefehl aus (Liste in der Modulkonfiguration), "
            + "z.B. VM.system_properties, VM.metaspace, Compiler.codecache, VM.classloader_stats."
            + " Statt `jcmd` direkt verwenden." + ShellHints.JVM)
    public String jcmd(
            @ToolParam(required = false, description = JvmTools.TARGET) String target,
            @ToolParam(description = "Befehl, z.B. VM.metaspace") String command,
            @ToolParam(required = false, description = "Argumente, z.B. [\"-all\"]") List<String> args) {
        JavaEnvironment e = env.get();
        e.requireInvasive("Freie jcmd-Befehle");
        if (!allowed.contains(command)) {
            throw new IllegalArgumentException("Befehl '" + command + "' ist nicht freigegeben. Erlaubt: " + allowed);
        }
        List<String> a = args == null ? List.of() : args;
        for (String arg : a) {
            if (!arg.matches("[A-Za-z0-9_.,=:/\\\\+\\- ]*")) {
                throw new IllegalArgumentException("Unzulässiges Argument: " + arg);
            }
        }
        JvmTarget t = e.target(target);
        return Text.limitLines(t.describe() + "\n\n" + t.dcmd(command, a.toArray(String[]::new)), e.maxLines());
    }

    private static long size(Path p) {
        try {
            return Files.size(p);
        } catch (Exception ex) {
            return 0;
        }
    }
}
