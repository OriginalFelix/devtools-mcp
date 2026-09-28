package systems.grebe.devtools.mcp.modules.debug;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import com.sun.jdi.ThreadReference;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.Text;
import systems.grebe.devtools.mcp.modules.java.JavaEnvironment;

/** Debugger-Tools. */
public class DebugTools {

    private static final String SESSION = "Sitzungs-ID aus debug_attach; leer = die einzige offene Sitzung";
    private static final String THREAD = "Threadname; leer = der zuletzt angehaltene Thread";

    private final Supplier<JavaEnvironment> env;
    private final DebugSessions sessions;
    private final int maxWait;
    private final int depth;

    DebugTools(Supplier<JavaEnvironment> env, DebugSessions sessions, int maxWait, int depth) {
        this.env = env;
        this.sessions = sessions;
        this.maxWait = maxWait;
        this.depth = depth;
    }

    @Tool(name = "attach", description = "Verbindet den Debugger mit einer JVM, die mit JDWP gestartet wurde "
            + "(-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005). Liefert eine Sitzungs-ID.")
    public String attach(
            @ToolParam(required = false, description = "Host (Standard localhost)") String host,
            @ToolParam(description = "JDWP-Port, z.B. 5005") Integer port) {
        String h = host == null || host.isBlank() ? "localhost" : host.trim();
        if (!env.get().debugHostAllowed(h)) {
            throw new IllegalArgumentException("Host '" + h + "' ist in den Java-Grundeinstellungen (Erlaubte Debug-Hosts) nicht freigegeben.");
        }
        if (port == null || port <= 0 || port > 65535) {
            throw new IllegalArgumentException("Gültigen JDWP-Port angeben.");
        }
        DebugSession s = sessions.add(DebugSession.attach(h, port, 10_000));
        return "Sitzung " + s.id() + " verbunden mit " + s.address() + " (" + s.vmDescription() + ").\n"
                + "Nächste Schritte: debug_set_breakpoint → Szenario auslösen → debug_wait_for_break.";
    }

    @Tool(name = "sessions", description = "Listet offene Debug-Sitzungen mit Breakpoints und Status.")
    public String sessionsList() {
        List<DebugSession> all = sessions.all();
        if (all.isEmpty()) {
            return "Keine Debug-Sitzung offen.";
        }
        StringBuilder sb = new StringBuilder();
        for (DebugSession s : all) {
            sb.append(s.id()).append("  ").append(s.address()).append("  ").append(s.disconnected().orElse("verbunden")).append('\n');
            s.breakpoints().forEach(b -> sb.append("   Breakpoint ").append(b.id()).append(": ").append(b.className())
                    .append(':').append(b.line()).append(b.requests().isEmpty() ? " (wartet auf Laden der Klasse)" : "").append('\n'));
            s.current().ifPresent(c -> sb.append("   angehalten: ").append(c.thread().name()).append(" @ ")
                    .append(DebugSession.formatLocation(c.location())).append('\n'));
        }
        return sb.toString().stripTrailing();
    }

    @Tool(name = "detach", description = "Trennt eine Debug-Sitzung. Breakpoints werden entfernt, angehaltene Threads laufen weiter.")
    public String detach(@ToolParam(required = false, description = SESSION) String session) {
        DebugSession s = sessions.get(session);
        sessions.close(s.id());
        return "Sitzung " + s.id() + " getrennt, Ziel-JVM läuft weiter.";
    }

    @Tool(name = "set_breakpoint", description = "Setzt einen Zeilen-Breakpoint (voll qualifizierte Klasse + Zeilennummer). "
            + "Ist die Klasse noch nicht geladen, wird er beim Laden aktiv.")
    public String setBreakpoint(
            @ToolParam(required = false, description = SESSION) String session,
            @ToolParam(description = "Voll qualifizierter Klassenname, z.B. com.acme.OrderService") String className,
            @ToolParam(description = "Zeilennummer im Quelltext") Integer line) {
        DebugSession s = sessions.get(session);
        DebugSession.Breakpoint bp = s.addBreakpoint(className.trim(), line);
        return "Breakpoint " + bp.id() + " auf " + bp.className() + ":" + bp.line()
                + (bp.requests().isEmpty() ? " – Klasse noch nicht geladen, wird beim Laden aktiviert." : " aktiv.");
    }

    @Tool(name = "clear_breakpoint", description = "Entfernt einen Breakpoint (ID aus debug_set_breakpoint/debug_sessions).")
    public String clearBreakpoint(
            @ToolParam(required = false, description = SESSION) String session,
            @ToolParam(description = "Breakpoint-ID") Integer id) {
        return sessions.get(session).removeBreakpoint(id) ? "Breakpoint " + id + " entfernt." : "Breakpoint " + id + " nicht gefunden.";
    }

    @Tool(name = "wait_for_break", description = "Wartet, bis ein Thread an einem Breakpoint oder nach einem Schritt anhält, "
            + "und liefert Position, Stack und Variablen des obersten Frames. Das Szenario muss parallel ausgelöst werden.")
    public String waitForBreak(
            @ToolParam(required = false, description = SESSION) String session,
            @ToolParam(required = false, description = "Wartezeit in Sekunden (Standard 30)") Integer timeoutSeconds) {
        DebugSession s = sessions.get(session);
        int secs = timeoutSeconds == null ? 30 : Math.max(1, Math.min(maxWait, timeoutSeconds));
        Optional<DebugSession.Stop> stop;
        try {
            stop = s.waitForStop(secs * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Abgebrochen", e);
        }
        if (stop.isEmpty()) {
            return s.disconnected().map(d -> "Sitzung beendet: " + d)
                    .orElse("Innerhalb von " + secs + " s kein Treffer. Wurde das Szenario ausgelöst? Breakpoints: "
                            + s.breakpoints().stream().map(b -> b.className() + ":" + b.line()).toList());
        }
        DebugSession.Stop st = stop.get();
        return Text.limitLines(st.reason() + " – Thread \"" + st.thread().name() + "\" angehalten bei "
                + DebugSession.formatLocation(st.location()) + "\n\nStack:\n  " + String.join("\n  ", s.stack(st.thread(), 15))
                + "\n\nVariablen (Frame 0):\n" + s.variables(st.thread(), 0, depth)
                + "\nWeiter mit debug_step (over/into/out) oder debug_resume.", env.get().maxLines());
    }

    @Tool(name = "threads", description = "Listet die Threads der Ziel-JVM mit Status (angehaltene markiert).")
    public String threads(@ToolParam(required = false, description = SESSION) String session) {
        DebugSession s = sessions.get(session);
        StringBuilder sb = new StringBuilder();
        for (ThreadReference t : s.threads()) {
            sb.append(t.isSuspended() ? "⏸ " : "  ").append(t.name()).append("  [").append(state(t.status())).append("]\n");
        }
        return Text.limitLines(sb.toString(), env.get().maxLines());
    }

    @Tool(name = "stack", description = "Stack eines angehaltenen Threads.")
    public String stack(
            @ToolParam(required = false, description = SESSION) String session,
            @ToolParam(required = false, description = THREAD) String thread,
            @ToolParam(required = false, description = "Max. Frames (Standard 40)") Integer max) {
        DebugSession s = sessions.get(session);
        ThreadReference t = s.thread(thread);
        return t.name() + ":\n  " + String.join("\n  ", s.stack(t, max == null ? 40 : Math.max(1, max)));
    }

    @Tool(name = "variables", description = "Lokale Variablen und Felder von this in einem Frame eines angehaltenen Threads.")
    public String variables(
            @ToolParam(required = false, description = SESSION) String session,
            @ToolParam(required = false, description = THREAD) String thread,
            @ToolParam(required = false, description = "Frame-Index aus debug_stack (Standard 0)") Integer frame,
            @ToolParam(required = false, description = "Objekttiefe (Standard aus Konfiguration)") Integer objectDepth) {
        DebugSession s = sessions.get(session);
        ThreadReference t = s.thread(thread);
        return Text.limitLines(s.variables(t, frame == null ? 0 : frame, objectDepth == null ? depth : Math.min(5, objectDepth)),
                env.get().maxLines());
    }

    @Tool(name = "resume", description = "Setzt den angehaltenen Thread fort (oder alle mit all=true).")
    public String resume(
            @ToolParam(required = false, description = SESSION) String session,
            @ToolParam(required = false, description = THREAD) String thread,
            @ToolParam(required = false, description = "true = alle Threads fortsetzen") Boolean all) {
        DebugSession s = sessions.get(session);
        if (Boolean.TRUE.equals(all)) {
            s.resume(null);
            return "Alle Threads fortgesetzt.";
        }
        ThreadReference t = s.thread(thread);
        s.resume(t);
        return "Thread " + t.name() + " fortgesetzt.";
    }

    @Tool(name = "step", description = "Führt einen Schritt im angehaltenen Thread aus (over, into, out; JDK-Klassen werden übersprungen) "
            + "und wartet auf das Anhalten.")
    public String step(
            @ToolParam(required = false, description = SESSION) String session,
            @ToolParam(required = false, description = THREAD) String thread,
            @ToolParam(required = false, description = "over (Standard), into oder out") String kind) {
        DebugSession s = sessions.get(session);
        s.step(s.thread(thread), kind);
        return waitForBreak(s.id(), 10);
    }

    private static String state(int status) {
        return switch (status) {
            case ThreadReference.THREAD_STATUS_RUNNING -> "läuft";
            case ThreadReference.THREAD_STATUS_SLEEPING -> "schläft";
            case ThreadReference.THREAD_STATUS_MONITOR -> "blockiert (Monitor)";
            case ThreadReference.THREAD_STATUS_WAIT -> "wartet";
            case ThreadReference.THREAD_STATUS_ZOMBIE -> "beendet";
            case ThreadReference.THREAD_STATUS_NOT_STARTED -> "nicht gestartet";
            default -> "unbekannt";
        };
    }
}
