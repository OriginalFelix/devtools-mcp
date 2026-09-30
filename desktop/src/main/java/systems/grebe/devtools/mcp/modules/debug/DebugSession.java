package systems.grebe.devtools.mcp.modules.debug;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.ArrayReference;
import com.sun.jdi.Bootstrap;
import com.sun.jdi.ClassType;
import com.sun.jdi.Field;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.Location;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.PrimitiveValue;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.IllegalConnectorArgumentsException;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.LocatableEvent;
import com.sun.jdi.event.StepEvent;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.StepRequest;

/**
 * Eine Debug-Sitzung über JDWP (JDI). Nur lesend: Breakpoints, Stack, Variablen, Fortsetzen, Schritte.
 * Ein Hintergrund-Thread nimmt JDI-Ereignisse entgegen; angehaltene Threads werden in einer Warteschlange abgelegt.
 */
public final class DebugSession implements AutoCloseable {

    public record Breakpoint(int id, String className, int line, List<BreakpointRequest> requests, ClassPrepareRequest pending) { }

    public record Stop(ThreadReference thread, Location location, String reason) { }

    private static final AtomicInteger IDS = new AtomicInteger();
    private static final int MAX_STRING = 200;

    private final String id;
    private final String address;
    private final VirtualMachine vm;
    private final List<Breakpoint> breakpoints = new CopyOnWriteArrayList<>();
    private final BlockingQueue<Stop> stops = new LinkedBlockingQueue<>();
    private final AtomicInteger bpIds = new AtomicInteger();
    private final Thread eventThread;
    private volatile Stop current;
    private volatile String disconnected;

    private DebugSession(String address, VirtualMachine vm) {
        this.id = "s" + IDS.incrementAndGet();
        this.address = address;
        this.vm = vm;
        this.eventThread = Thread.ofPlatform().daemon().name("jdi-events-" + id).start(this::eventLoop);
    }

    public static DebugSession attach(String host, int port, int timeoutMs) {
        AttachingConnector socket = Bootstrap.virtualMachineManager().attachingConnectors().stream()
                .filter(c -> c.transport().name().equals("dt_socket")).findFirst()
                .orElseThrow(() -> new IllegalStateException("JDI-Socket-Connector nicht verfügbar (Modul jdk.jdi?)"));
        Map<String, Connector.Argument> args = socket.defaultArguments();
        args.get("hostname").setValue(host);
        args.get("port").setValue(String.valueOf(port));
        if (args.containsKey("timeout")) {
            args.get("timeout").setValue(String.valueOf(timeoutMs));
        }
        try {
            return new DebugSession(host + ":" + port, socket.attach(args));
        } catch (IOException e) {
            throw new IllegalStateException("Keine Verbindung zu JDWP " + host + ":" + port + " (" + e.getMessage() + "). Läuft die JVM mit "
                    + "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:" + port + " ?", e);
        } catch (IllegalConnectorArgumentsException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    public String id() {
        return id;
    }

    public String address() {
        return address;
    }

    public String vmDescription() {
        return vm.name() + " " + vm.version();
    }

    public List<Breakpoint> breakpoints() {
        return breakpoints;
    }

    public Optional<String> disconnected() {
        return Optional.ofNullable(disconnected);
    }

    // ------------------------------------------------------------------ Breakpoints

    public Breakpoint addBreakpoint(String className, int line) {
        checkAlive();
        EventRequestManager erm = vm.eventRequestManager();
        List<BreakpointRequest> reqs = new ArrayList<>();
        for (ReferenceType t : vm.classesByName(className)) {
            reqs.addAll(requestsFor(t, line));
        }
        ClassPrepareRequest pending = null;
        if (reqs.isEmpty()) {
            // Klasse noch nicht geladen: beim Laden setzen
            pending = erm.createClassPrepareRequest();
            pending.addClassFilter(className);
            pending.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
            pending.enable();
        }
        Breakpoint bp = new Breakpoint(bpIds.incrementAndGet(), className, line, new CopyOnWriteArrayList<>(reqs), pending);
        breakpoints.add(bp);
        return bp;
    }

    private List<BreakpointRequest> requestsFor(ReferenceType t, int line) {
        List<BreakpointRequest> out = new ArrayList<>();
        try {
            List<Location> locs = t.locationsOfLine(line);
            if (locs.isEmpty()) {
                throw new IllegalArgumentException("Zeile " + line + " in " + t.name() + " enthält keinen ausführbaren Code.");
            }
            BreakpointRequest r = vm.eventRequestManager().createBreakpointRequest(locs.getFirst());
            r.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
            r.enable();
            out.add(r);
        } catch (AbsentInformationException e) {
            throw new IllegalStateException(t.name() + " wurde ohne Zeilennummern kompiliert (-g).");
        }
        return out;
    }

    public boolean removeBreakpoint(int bpId) {
        for (Breakpoint bp : breakpoints) {
            if (bp.id() == bpId) {
                bp.requests().forEach(r -> vm.eventRequestManager().deleteEventRequest(r));
                if (bp.pending() != null) {
                    vm.eventRequestManager().deleteEventRequest(bp.pending());
                }
                breakpoints.remove(bp);
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ Anhalten / Fortsetzen

    /** Wartet auf den nächsten angehaltenen Thread (Breakpoint/Schritt). */
    public Optional<Stop> waitForStop(long timeoutMs) throws InterruptedException {
        Stop s = stops.poll(timeoutMs, TimeUnit.MILLISECONDS);
        if (s != null) {
            current = s;
        }
        return Optional.ofNullable(s);
    }

    public Optional<Stop> current() {
        return Optional.ofNullable(current);
    }

    public ThreadReference thread(String name) {
        checkAlive();
        if (name == null || name.isBlank()) {
            if (current == null) {
                throw new IllegalStateException("Kein angehaltener Thread – zuerst debug_wait_for_break.");
            }
            return current.thread();
        }
        return vm.allThreads().stream().filter(t -> t.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Thread '" + name + "' nicht gefunden."));
    }

    public List<ThreadReference> threads() {
        checkAlive();
        return vm.allThreads();
    }

    /** Setzt den angehaltenen Thread (oder alle) fort. */
    public void resume(ThreadReference t) {
        checkAlive();
        if (t == null) {
            vm.resume();
        } else {
            // Anzahl vorher festhalten: eine Schleife auf suspendCount() würde einen Thread, der durch einen
            // Schritt/Breakpoint sofort wieder anhält, gleich erneut fortsetzen.
            int count = t.suspendCount();
            for (int i = 0; i < count; i++) {
                t.resume();
            }
        }
        if (current != null && (t == null || current.thread().equals(t))) {
            current = null;
        }
    }

    public void step(ThreadReference t, String kind) {
        checkAlive();
        int depth = switch (kind == null ? "over" : kind.toLowerCase()) {
            case "into" -> StepRequest.STEP_INTO;
            case "out" -> StepRequest.STEP_OUT;
            default -> StepRequest.STEP_OVER;
        };
        EventRequestManager erm = vm.eventRequestManager();
        erm.stepRequests().stream().filter(r -> r.thread().equals(t)).toList().forEach(erm::deleteEventRequest);
        StepRequest r = erm.createStepRequest(t, StepRequest.STEP_LINE, depth);
        for (String ex : List.of("java.*", "javax.*", "jdk.*", "sun.*", "com.sun.*")) {
            r.addClassExclusionFilter(ex);
        }
        r.addCountFilter(1);
        r.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
        r.enable();
        resume(t);
    }

    // ------------------------------------------------------------------ Lesen

    public List<String> stack(ThreadReference t, int max) {
        try {
            List<String> out = new ArrayList<>();
            List<StackFrame> frames = t.frames();
            for (int i = 0; i < Math.min(max, frames.size()); i++) {
                out.add("#" + i + " " + formatLocation(frames.get(i).location()));
            }
            if (frames.size() > max) {
                out.add("… " + (frames.size() - max) + " weitere Frames");
            }
            return out;
        } catch (IncompatibleThreadStateException e) {
            throw new IllegalStateException("Thread " + t.name() + " ist nicht angehalten – Stack nur an Breakpoints lesbar.");
        }
    }

    /** Lokale Variablen und {@code this} eines Frames, Objekte bis zur angegebenen Tiefe aufgeklappt. */
    public String variables(ThreadReference t, int frameIndex, int depth) {
        try {
            StackFrame f = t.frame(frameIndex);
            StringBuilder sb = new StringBuilder(formatLocation(f.location())).append('\n');
            ObjectReference self = f.thisObject();
            if (self != null) {
                sb.append("this = ").append(render(self, depth, "  ")).append('\n');
            }
            try {
                for (LocalVariable v : f.visibleVariables()) {
                    sb.append(v.name()).append(" = ").append(render(f.getValue(v), depth, "  ")).append('\n');
                }
            } catch (AbsentInformationException e) {
                sb.append("(keine Variableninformationen – mit -g kompilieren)\n");
            }
            return sb.toString();
        } catch (IncompatibleThreadStateException e) {
            throw new IllegalStateException("Thread " + t.name() + " ist nicht angehalten.");
        } catch (IndexOutOfBoundsException e) {
            throw new IllegalArgumentException("Frame " + frameIndex + " existiert nicht.");
        }
    }

    private String render(Value v, int depth, String indent) {
        if (v == null) {
            return "null";
        }
        if (v instanceof PrimitiveValue p) {
            return p.toString();
        }
        if (v instanceof StringReference s) {
            String str = s.value();
            return "\"" + (str.length() > MAX_STRING ? str.substring(0, MAX_STRING) + "…" : str) + "\"";
        }
        ObjectReference o = (ObjectReference) v;
        String type = o.referenceType().name();
        if (o instanceof ArrayReference a) {
            StringBuilder sb = new StringBuilder(type.replace("[]", "[" + a.length() + "]"));
            if (depth > 0) {
                sb.append(" {");
                int n = Math.min(a.length(), 20);
                for (int i = 0; i < n; i++) {
                    sb.append(i == 0 ? "" : ", ").append(render(a.getValue(i), depth - 1, indent + "  "));
                }
                sb.append(a.length() > n ? ", …}" : "}");
            }
            return sb.toString();
        }
        String boxed = boxedValue(o);
        if (boxed != null) {
            return boxed;
        }
        StringBuilder sb = new StringBuilder(type).append("@").append(o.uniqueID());
        if (depth <= 0) {
            return sb.toString();
        }
        if (o.referenceType() instanceof ClassType ct) {
            int n = 0;
            for (Field fld : ct.allFields()) {
                if (fld.isStatic()) {
                    continue;
                }
                if (n++ >= 25) {
                    sb.append('\n').append(indent).append("…");
                    break;
                }
                sb.append('\n').append(indent).append(fld.name()).append(" = ")
                        .append(render(o.getValue(fld), depth - 1, indent + "  "));
            }
        }
        return sb.toString();
    }

    private static String boxedValue(ObjectReference o) {
        String n = o.referenceType().name();
        if (n.startsWith("java.lang.") && List.of("Integer", "Long", "Short", "Byte", "Double", "Float", "Boolean", "Character")
                .contains(n.substring(10))) {
            Field f = o.referenceType().fieldByName("value");
            return f == null ? null : n.substring(10) + "(" + o.getValue(f) + ")";
        }
        return null;
    }

    static String formatLocation(Location l) {
        String src;
        try {
            src = l.sourceName();
        } catch (AbsentInformationException e) {
            src = "?";
        }
        return l.declaringType().name() + "." + l.method().name() + "(" + src + ":" + l.lineNumber() + ")";
    }

    // ------------------------------------------------------------------ Ereignisse

    private void eventLoop() {
        try {
            while (true) {
                EventSet set = vm.eventQueue().remove();
                boolean resume = true;
                for (Event ev : set) {
                    switch (ev) {
                        case ClassPrepareEvent cpe -> onClassPrepare(cpe);
                        case BreakpointEvent be -> {
                            stops.add(new Stop(be.thread(), be.location(), "Breakpoint"));
                            resume = false;
                        }
                        case StepEvent se -> {
                            vm.eventRequestManager().deleteEventRequest(se.request());
                            stops.add(new Stop(se.thread(), se.location(), "Schritt"));
                            resume = false;
                        }
                        case VMDeathEvent d -> disconnected = "Ziel-JVM beendet";
                        case VMDisconnectEvent d -> {
                            disconnected = disconnected == null ? "Verbindung getrennt" : disconnected;
                            return;
                        }
                        default -> {
                            if (ev instanceof LocatableEvent) {
                                resume = true;
                            }
                        }
                    }
                }
                if (resume) {
                    set.resume();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (com.sun.jdi.VMDisconnectedException e) {
            disconnected = disconnected == null ? "Verbindung getrennt" : disconnected;
        }
    }

    private void onClassPrepare(ClassPrepareEvent cpe) {
        for (Breakpoint bp : breakpoints) {
            if (bp.pending() != null && bp.className().equals(cpe.referenceType().name())) {
                try {
                    bp.requests().addAll(requestsFor(cpe.referenceType(), bp.line()));
                } catch (RuntimeException ignored) {
                    // Zeile ungültig -> bleibt ohne Request
                }
            }
        }
    }

    private void checkAlive() {
        if (disconnected != null) {
            throw new IllegalStateException("Sitzung " + id + " ist beendet: " + disconnected);
        }
    }

    /** Trennt die Verbindung; die Ziel-JVM läuft weiter (Breakpoints werden entfernt, Threads fortgesetzt). */
    @Override
    public void close() {
        if (disconnected == null) {
            try {
                vm.eventRequestManager().deleteAllBreakpoints();
                vm.resume();
                vm.dispose();
            } catch (com.sun.jdi.VMDisconnectedException ignored) {
                // bereits weg
            }
        }
        disconnected = "getrennt";
        eventThread.interrupt();
    }
}
