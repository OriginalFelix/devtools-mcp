package systems.grebe.devtools.mcp.modules.jvm;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.Text;
import systems.grebe.devtools.mcp.modules.java.Containers;
import systems.grebe.devtools.mcp.modules.java.JavaEnvironment;
import systems.grebe.devtools.mcp.modules.java.JvmTarget;
import systems.grebe.devtools.mcp.modules.java.LocalJvms;

/** Lesende JVM-Diagnose. */
public class JvmTools {

    static final String TARGET = "Ziel-JVM: PID, eindeutiger Teil des Hauptklassen-/Jar-Namens, container:<name>[:<pid>] "
            + "oder jmx:<alias>. Leer = die einzige lokale JVM.";

    private final Supplier<JavaEnvironment> env;

    JvmTools(Supplier<JavaEnvironment> env) {
        this.env = env;
    }

    @Tool(name = "processes", description = "Listet erreichbare JVMs: lokale Prozesse (PID, Hauptklasse), laufende Container "
            + "(als container:<name> ansprechbar) und konfigurierte JMX-Ziele (jmx:<alias>).")
    public String processes(@ToolParam(required = false, description = "true = auch Container nach JVMs durchsuchen (langsamer)") Boolean scanContainers) {
        JavaEnvironment e = env.get();
        StringBuilder sb = new StringBuilder("Lokale JVMs:\n");
        List<LocalJvms.Jvm> local = e.processes().list();
        if (local.isEmpty()) {
            sb.append("  (keine)\n");
        }
        for (LocalJvms.Jvm j : local) {
            sb.append(String.format("  %-7d %s\n", j.pid(), Text.limitLines(abbreviate(j.commandLine(), 160), 1)));
        }
        List<Containers.Container> containers = e.containers().running();
        if (!containers.isEmpty()) {
            sb.append("\nContainer (").append(e.containers().cli()).append("):\n");
            for (Containers.Container c : containers) {
                sb.append("  container:").append(c.name()).append("  [").append(c.image()).append("] ").append(c.status());
                if (Boolean.TRUE.equals(scanContainers)) {
                    var r = e.containers().exec(c.name(), java.time.Duration.ofSeconds(20), "jcmd", "-l");
                    sb.append(r.ok() ? "\n" + r.output().lines().filter(l -> !l.contains("JCmd"))
                            .map(l -> "      " + l).reduce((a, b) -> a + "\n" + b).orElse("      (keine JVM)")
                            : "  (kein jcmd im Image)");
                }
                sb.append('\n');
            }
        }
        if (!e.jmxTargets().isEmpty()) {
            sb.append("\nJMX-Ziele:\n");
            e.jmxTargets().values().forEach(t -> sb.append("  jmx:").append(t.alias()).append("  ").append(t.address()).append('\n'));
        }
        return sb.toString().stripTrailing();
    }

    @Tool(name = "info", description = "Überblick über eine JVM: Version, Laufzeit, Kommandozeile, gesetzte VM-Flags, Heap-Belegung.")
    public String info(@ToolParam(required = false, description = TARGET) String target) {
        JvmTarget t = env.get().target(target);
        StringBuilder sb = new StringBuilder(t.describe()).append("\n\n");
        section(sb, "Version", () -> t.dcmd("VM.version"));
        section(sb, "Laufzeit", () -> t.dcmd("VM.uptime"));
        section(sb, "Kommandozeile", () -> t.dcmd("VM.command_line"));
        section(sb, "VM-Flags (nicht Standard)", () -> t.dcmd("VM.flags"));
        section(sb, "Heap", () -> t.dcmd("GC.heap_info"));
        return Text.limitLines(sb.toString().stripTrailing(), env.get().maxLines());
    }

    @Tool(name = "threads", description = "Thread-Dump mit Auswertung: Zustände, Deadlock-Erkennung, Threads mit identischem Stack "
            + "gruppiert (größte Gruppen zuerst). Mehrfach im Abstand aufrufen, um hängende Threads zu erkennen.")
    public String threads(
            @ToolParam(required = false, description = TARGET) String target,
            @ToolParam(required = false, description = "Nur Threads, deren Name diesen Text enthält") String nameFilter,
            @ToolParam(required = false, description = "Nur Threads in diesem Zustand: RUNNABLE, BLOCKED, WAITING, TIMED_WAITING") String state,
            @ToolParam(required = false, description = "Stacktiefe je Gruppe (Standard 15)") Integer depth,
            @ToolParam(required = false, description = "true = zusätzlich den Roh-Dump liefern") Boolean raw) {
        JvmTarget t = env.get().target(target);
        String dump = t.dcmd("Thread.print", "-l");
        ThreadDumpAnalyzer a = new ThreadDumpAnalyzer(dump);
        String out = t.describe() + "\n" + a.summary(nameFilter, state, 25, depth == null ? 15 : Math.max(3, depth));
        if (Boolean.TRUE.equals(raw)) {
            out += "\n\n--- Roh-Dump ---\n" + dump;
        }
        return Text.limitLines(out, env.get().maxLines());
    }

    @Tool(name = "heap", description = "Heap-Belegung und Klassenhistogramm (Top-N Klassen nach belegtem Speicher). "
            + "Mehrfach aufrufen und vergleichen, um wachsende Klassen (Lecks) zu finden.")
    public String heap(
            @ToolParam(required = false, description = TARGET) String target,
            @ToolParam(required = false, description = "Anzahl Klassen (Standard 30)") Integer top,
            @ToolParam(required = false, description = "Nur Klassen, deren Name diesen Text enthält") String classFilter) {
        JvmTarget t = env.get().target(target);
        int n = top == null ? 30 : Math.min(500, Math.max(1, top));
        StringBuilder sb = new StringBuilder(t.describe()).append("\n\n");
        section(sb, "Heap", () -> t.dcmd("GC.heap_info"));
        String histo = t.dcmd("GC.class_histogram");
        List<String> lines = histo.lines().toList();
        List<String> out = new ArrayList<>();
        int count = 0;
        for (String l : lines) {
            if (l.trim().startsWith("num") || l.trim().startsWith("---")) {
                out.add(l);
                continue;
            }
            if (l.trim().startsWith("Total")) {
                out.add(l);
                continue;
            }
            if (classFilter != null && !classFilter.isBlank() && !l.contains(classFilter)) {
                continue;
            }
            if (count++ < n) {
                out.add(l);
            }
        }
        sb.append("Klassenhistogramm (Top ").append(n).append(", Anzahl / Bytes / Klasse):\n").append(String.join("\n", out));
        return Text.limitLines(sb.toString(), env.get().maxLines());
    }

    @Tool(name = "native_memory", description = "Native-Memory-Übersicht (NMT: Heap, Metaspace, Threads, Code, GC …). "
            + "Erfordert Start mit -XX:NativeMemoryTracking=summary.")
    public String nativeMemory(
            @ToolParam(required = false, description = TARGET) String target,
            @ToolParam(required = false, description = "summary (Standard) oder detail") String mode) {
        JvmTarget t = env.get().target(target);
        String out = t.dcmd("VM.native_memory", "detail".equalsIgnoreCase(mode) ? "detail" : "summary");
        if (out.contains("not enabled")) {
            return "Native Memory Tracking ist in " + t.describe() + " nicht aktiv. Die JVM muss mit "
                    + "-XX:NativeMemoryTracking=summary gestartet werden.";
        }
        return Text.limitLines(out, env.get().maxLines());
    }

    private static void section(StringBuilder sb, String title, Supplier<String> s) {
        sb.append("## ").append(title).append('\n');
        try {
            sb.append(s.get().strip()).append("\n\n");
        } catch (RuntimeException e) {
            sb.append("(nicht verfügbar: ").append(e.getMessage()).append(")\n\n");
        }
    }

    private static String abbreviate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
