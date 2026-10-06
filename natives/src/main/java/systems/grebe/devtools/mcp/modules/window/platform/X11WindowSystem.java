package systems.grebe.devtools.mcp.modules.window.platform;

import java.awt.Rectangle;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Linux/X11: Fenster über die EWMH-Eigenschaften des Fenstermanagers ({@code _NET_CLIENT_LIST_STACKING},
 * {@code _NET_WM_PID}, {@code _NET_WM_NAME}, {@code _NET_ACTIVE_WINDOW}) per libX11. Unter Wayland sieht X11 nur
 * XWayland-Fenster.
 *
 * <p>Xlib beendet den Prozess bei X-Fehlern (etwa einem gerade geschlossenen Fenster), solange kein eigener
 * Fehler-Handler gesetzt ist. Jeder Zugriff setzt deshalb kurz einen eigenen Handler und stellt danach den vorherigen
 * (von AWT) wieder her.
 */
final class X11WindowSystem implements WindowSystem {

    private static final long ANY_PROPERTY_TYPE = 0;
    private static final long CLIENT_MESSAGE = 33;
    /** Stapelmodus {@code Above} aus X.h. */
    private static final long ABOVE = 0;
    private static final long SUBSTRUCTURE_MASK = (1L << 19) | (1L << 20);
    private static final int XEVENT_SIZE = 192;
    private static final int SHAPE_INPUT = 2;
    private static final int SHAPE_SET = 0;
    private static final int UNSORTED = 0;

    private final MethodHandle xOpenDisplay;
    private final MethodHandle xDefaultRootWindow;
    private final MethodHandle xInternAtom;
    private final MethodHandle xGetWindowProperty;
    private final MethodHandle xFree;
    private final MethodHandle xGetGeometry;
    private final MethodHandle xTranslateCoordinates;
    private final MethodHandle xSendEvent;
    private final MethodHandle xMapRaised;
    private final MethodHandle xSync;
    private final MethodHandle xSetErrorHandler;
    /** {@code XShapeCombineRectangles} aus libXext; {@code null}, wenn die Bibliothek fehlt. */
    private final MethodHandle xShapeCombineRectangles;
    private final java.util.Set<Long> passingThrough = new java.util.HashSet<>();
    private final MemorySegment errorHandler;

    private final MemorySegment display;
    private final long root;
    private final long netClientList;
    private final long netWmPid;
    private final long netWmName;
    private final long netActiveWindow;
    private final long netRestackWindow;
    private final long netWmStateHidden;
    private final long netWmState;
    private final long wmName;
    private final long utf8String;

    X11WindowSystem() {
        Linker linker = Linker.nativeLinker();
        SymbolLookup x11 = SymbolLookup.libraryLookup("libX11.so.6", Arena.global());
        xOpenDisplay = Natives.bind(linker, x11, "XOpenDisplay", FunctionDescriptor.of(ADDRESS, ADDRESS));
        xDefaultRootWindow = Natives.bind(linker, x11, "XDefaultRootWindow", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
        xInternAtom = Natives.bind(linker, x11, "XInternAtom", FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_INT));
        xGetWindowProperty = Natives.bind(linker, x11, "XGetWindowProperty", FunctionDescriptor.of(JAVA_INT,
                ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG,
                ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        xFree = Natives.bind(linker, x11, "XFree", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        xGetGeometry = Natives.bind(linker, x11, "XGetGeometry", FunctionDescriptor.of(JAVA_INT,
                ADDRESS, JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        xTranslateCoordinates = Natives.bind(linker, x11, "XTranslateCoordinates", FunctionDescriptor.of(JAVA_INT,
                ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        xSendEvent = Natives.bind(linker, x11, "XSendEvent", FunctionDescriptor.of(JAVA_INT,
                ADDRESS, JAVA_LONG, JAVA_INT, JAVA_LONG, ADDRESS));
        xMapRaised = Natives.bind(linker, x11, "XMapRaised", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG));
        xSync = Natives.bind(linker, x11, "XSync", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        xSetErrorHandler = Natives.bind(linker, x11, "XSetErrorHandler", FunctionDescriptor.of(ADDRESS, ADDRESS));
        xShapeCombineRectangles = shapeFunction(linker);
        try {
            MethodHandle ignore = MethodHandles.lookup().findStatic(X11WindowSystem.class, "ignoreError",
                    MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class));
            errorHandler = linker.upcallStub(ignore, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS), Arena.global());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }

        display = (MemorySegment) Natives.call(xOpenDisplay, "XOpenDisplay", MemorySegment.NULL);
        if (display.address() == 0) {
            throw new IllegalStateException("X-Server " + System.getenv("DISPLAY") + " nicht erreichbar");
        }
        root = (long) Natives.call(xDefaultRootWindow, "XDefaultRootWindow", display);
        netClientList = atom("_NET_CLIENT_LIST_STACKING");
        netWmPid = atom("_NET_WM_PID");
        netWmName = atom("_NET_WM_NAME");
        netActiveWindow = atom("_NET_ACTIVE_WINDOW");
        netRestackWindow = atom("_NET_RESTACK_WINDOW");
        netWmState = atom("_NET_WM_STATE");
        netWmStateHidden = atom("_NET_WM_STATE_HIDDEN");
        wmName = atom("WM_NAME");
        utf8String = atom("UTF8_STRING");
    }

    private static MethodHandle shapeFunction(Linker linker) {
        try {
            SymbolLookup xext = SymbolLookup.libraryLookup("libXext.so.6", Arena.global());
            return Natives.bindOptional(linker, xext, "XShapeCombineRectangles", FunctionDescriptor.of(JAVA_INT,
                    ADDRESS, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));
        } catch (IllegalArgumentException e) {
            return null; // ohne libXext bleiben Rahmen und Hinweis anklickbar
        }
    }

    /** Fehler-Handler, der X-Fehler ignoriert statt den Prozess zu beenden. */
    static int ignoreError(MemorySegment display, MemorySegment event) {
        return 0;
    }

    private long atom(String name) {
        try (Arena arena = Arena.ofConfined()) {
            return (long) Natives.call(xInternAtom, "XInternAtom", display, arena.allocateFrom(name), 0);
        }
    }

    @Override
    public String name() {
        return "X11 (libX11)";
    }

    @Override
    public Optional<String> unsupportedReason() {
        return Optional.empty();
    }

    @Override
    public List<String> warnings() {
        return System.getenv("WAYLAND_DISPLAY") != null
                ? List.of("Wayland-Sitzung: sichtbar und bedienbar sind nur Fenster, die über XWayland laufen.")
                : List.of();
    }

    @Override
    public synchronized List<NativeWindow> windows() {
        return guarded(() -> {
            long[] clients = longs(root, netClientList);
            ScreenMapper mapper = ScreenMapper.current(ScreenMapper.Mode.SCALED);
            List<NativeWindow> out = new ArrayList<>();
            // _NET_CLIENT_LIST_STACKING ist von unten nach oben sortiert – vorderstes zuerst ausgeben
            for (int i = clients.length - 1; i >= 0; i--) {
                describe(clients[i], mapper).ifPresent(out::add);
            }
            return out;
        });
    }

    private Optional<NativeWindow> describe(long w, ScreenMapper mapper) {
        long[] pid = longs(w, netWmPid);
        Rectangle bounds = geometry(w);
        if (pid.length == 0 || bounds == null || bounds.width <= 1 || bounds.height <= 1) {
            return Optional.empty();
        }
        String title = text(w, netWmName);
        if (title.isEmpty()) {
            title = text(w, wmName);
        }
        boolean hidden = false;
        for (long s : longs(w, netWmState)) {
            hidden |= s == netWmStateHidden;
        }
        return Optional.of(new NativeWindow(w, pid[0], null, title, mapper.toUser(bounds), hidden));
    }

    private Rectangle geometry(long w) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment rootOut = arena.allocate(JAVA_LONG);
            MemorySegment x = arena.allocate(JAVA_INT);
            MemorySegment y = arena.allocate(JAVA_INT);
            MemorySegment width = arena.allocate(JAVA_INT);
            MemorySegment height = arena.allocate(JAVA_INT);
            MemorySegment border = arena.allocate(JAVA_INT);
            MemorySegment depth = arena.allocate(JAVA_INT);
            if ((int) Natives.call(xGetGeometry, "XGetGeometry", display, w, rootOut, x, y, width, height, border, depth) == 0) {
                return null;
            }
            MemorySegment child = arena.allocate(JAVA_LONG);
            if ((int) Natives.call(xTranslateCoordinates, "XTranslateCoordinates", display, w, root, 0, 0, x, y, child) == 0) {
                return null;
            }
            return new Rectangle(x.get(JAVA_INT, 0), y.get(JAVA_INT, 0), width.get(JAVA_INT, 0), height.get(JAVA_INT, 0));
        }
    }

    /** Eigenschaft mit 32-Bit-Format (Atome, Fenster, Kardinalzahlen) – Xlib liefert sie als {@code long}-Array. */
    private long[] longs(long w, long property) {
        Prop p = property(w, property);
        if (p == null) {
            return new long[0];
        }
        try {
            if (p.format != 32) {
                return new long[0];
            }
            MemorySegment data = p.data.reinterpret(p.items * JAVA_LONG.byteSize());
            long[] out = new long[(int) p.items];
            for (int i = 0; i < out.length; i++) {
                out[i] = data.getAtIndex(JAVA_LONG, i);
            }
            return out;
        } finally {
            Natives.call(xFree, "XFree", p.data);
        }
    }

    private String text(long w, long property) {
        Prop p = property(w, property);
        if (p == null) {
            return "";
        }
        try {
            if (p.format != 8 || p.items == 0) {
                return "";
            }
            byte[] bytes = p.data.reinterpret(p.items).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
            return new String(bytes, p.type == utf8String ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1);
        } finally {
            Natives.call(xFree, "XFree", p.data);
        }
    }

    private record Prop(long type, int format, long items, MemorySegment data) {
    }

    private Prop property(long w, long property) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment type = arena.allocate(JAVA_LONG);
            MemorySegment format = arena.allocate(JAVA_INT);
            MemorySegment items = arena.allocate(JAVA_LONG);
            MemorySegment after = arena.allocate(JAVA_LONG);
            MemorySegment data = arena.allocate(ADDRESS);
            int status = (int) Natives.call(xGetWindowProperty, "XGetWindowProperty", display, w, property, 0L,
                    1L << 16, 0, ANY_PROPERTY_TYPE, type, format, items, after, data);
            MemorySegment ptr = data.get(ADDRESS, 0);
            if (status != 0 || ptr.address() == 0) {
                return null;
            }
            return new Prop(type.get(JAVA_LONG, 0), format.get(JAVA_INT, 0), items.get(JAVA_LONG, 0), ptr);
        }
    }

    @Override
    public synchronized OptionalLong foreground() {
        return guarded(() -> {
            long[] active = longs(root, netActiveWindow);
            return active.length == 0 || active[0] == 0 ? OptionalLong.empty() : OptionalLong.of(active[0]);
        });
    }

    /** Bittet den Fenstermanager per {@code _NET_ACTIVE_WINDOW}, das Fenster zu aktivieren (auch aus minimiert). */
    @Override
    public synchronized void activate(long id) {
        guarded(() -> {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment ev = arena.allocate(XEVENT_SIZE, 8);
                ev.set(JAVA_INT, 0, (int) CLIENT_MESSAGE);
                ev.set(JAVA_INT, 16, 1);                 // send_event
                ev.set(ADDRESS, 24, display);
                ev.set(JAVA_LONG, 32, id);               // window
                ev.set(JAVA_LONG, 40, netActiveWindow);  // message_type
                ev.set(JAVA_INT, 48, 32);                // format
                ev.set(JAVA_LONG, 56, 2);                // Quelle: Pager/Werkzeug (wird nicht abgewiesen)
                ev.set(JAVA_LONG, 64, 0);                // CurrentTime
                Natives.call(xMapRaised, "XMapRaised", display, id);
                Natives.call(xSendEvent, "XSendEvent", display, root, 0, SUBSTRUCTURE_MASK, ev);
            }
            return null;
        });
    }

    @Override
    public boolean canStackAbove() {
        return true;
    }

    /**
     * Bittet den Fenstermanager per {@code _NET_RESTACK_WINDOW} (EWMH), die eigenen Fenster direkt über das Ziel zu
     * legen – der Fenstermanager setzt das für die Rahmen seiner Fenster um. Liegen sie laut
     * {@code _NET_CLIENT_LIST_STACKING} schon dort, bleibt alles, wie es ist.
     *
     * @return {@code true} erst, wenn der Fenstermanager die Reihenfolge bestätigt
     */
    @Override
    public synchronized boolean stackAbove(List<java.awt.Window> overlays, NativeWindow target) {
        java.util.Set<Long> own = new java.util.HashSet<>();
        for (java.awt.Window w : overlays) {
            long id = com.sun.jna.Native.getWindowID(w);
            if (id != 0) {
                own.add(id);
            }
        }
        long[] stack = guarded(() -> longs(root, netClientList)); // von unten nach oben
        for (int i = 0; i < stack.length; i++) {
            if (stack[i] == target.id()) {
                boolean placed = own.size() == overlays.size();
                for (int k = 1; placed && k <= own.size(); k++) {
                    placed = i + k < stack.length && own.contains(stack[i + k]);
                }
                if (placed) {
                    return true;
                }
                break;
            }
        }
        own.forEach(id -> restack(id, target.id()));
        return false;
    }

    /** Leere Eingabe-Form (Shape-Erweiterung): Mausereignisse gehen an das Fenster darunter. */
    @Override
    public synchronized void passThrough(java.awt.Window overlay) {
        long id = com.sun.jna.Native.getWindowID(overlay);
        if (xShapeCombineRectangles == null || id == 0 || !passingThrough.add(id)) {
            return;
        }
        guarded(() -> Natives.call(xShapeCombineRectangles, "XShapeCombineRectangles", display, id, SHAPE_INPUT, 0, 0,
                MemorySegment.NULL, 0, SHAPE_SET, UNSORTED));
    }

    private void restack(long own, long target) {
        guarded(() -> {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment ev = arena.allocate(XEVENT_SIZE, 8);
                ev.set(JAVA_INT, 0, (int) CLIENT_MESSAGE);
                ev.set(JAVA_INT, 16, 1);                  // send_event
                ev.set(ADDRESS, 24, display);
                ev.set(JAVA_LONG, 32, own);               // window: das eigene Fenster
                ev.set(JAVA_LONG, 40, netRestackWindow);  // message_type
                ev.set(JAVA_INT, 48, 32);                 // format
                ev.set(JAVA_LONG, 56, 2);                 // Quelle: Pager/Werkzeug
                ev.set(JAVA_LONG, 64, target);            // Geschwister: das Ziel
                ev.set(JAVA_LONG, 72, ABOVE);             // darüber
                Natives.call(xSendEvent, "XSendEvent", display, root, 0, SUBSTRUCTURE_MASK, ev);
            }
            return null;
        });
    }

    private <T> T guarded(java.util.function.Supplier<T> body) {
        MemorySegment previous = (MemorySegment) Natives.call(xSetErrorHandler, "XSetErrorHandler", errorHandler);
        try {
            T result = body.get();
            Natives.call(xSync, "XSync", display, 0);
            return result;
        } finally {
            Natives.call(xSetErrorHandler, "XSetErrorHandler", previous);
        }
    }
}
