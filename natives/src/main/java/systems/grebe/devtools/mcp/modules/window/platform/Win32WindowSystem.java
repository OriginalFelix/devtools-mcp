package systems.grebe.devtools.mcp.modules.window.platform;

import java.awt.Rectangle;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Windows: Fenster über {@code user32} ({@code EnumWindows}, {@code GetWindowThreadProcessId}, …) und die sichtbaren
 * Grenzen über {@code dwmapi} ({@code DWMWA_EXTENDED_FRAME_BOUNDS}, ohne die unsichtbaren Ränder von Windows 10/11).
 *
 * <p>UWP-Apps (z.B. der Windows-Rechner) zeichnen in ein Kindfenster {@code Windows.UI.Core.CoreWindow}; das
 * sichtbare Top-Level-Fenster {@code ApplicationFrameWindow} gehört aber {@code ApplicationFrameHost.exe}. Solche
 * Rahmen werden dem Prozess (und UI-Thread) der gehosteten App zugeordnet, sonst hätte die App „keine Fenster“.
 *
 * <p>Abfragen laufen mit {@code DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2} für den aufrufenden Thread, damit Windows
 * physische Pixel liefert statt virtualisierter Werte; {@link ScreenMapper} rechnet sie in User-Space um.
 */
final class Win32WindowSystem implements WindowSystem {

    private static final int GWL_EXSTYLE = -20;
    private static final long WS_EX_TOOLWINDOW = 0x80;
    private static final int WS_EX_TRANSPARENT = 0x20;
    private static final int WS_EX_LAYERED = 0x80000;
    private static final int LWA_ALPHA = 0x2;
    private static final int SW_RESTORE = 9;
    private static final int DWMWA_EXTENDED_FRAME_BOUNDS = 9;
    private static final int DWMWA_CLOAKED = 14;
    private static final byte VK_MENU = 0x12;
    private static final int KEYEVENTF_KEYUP = 0x2;
    private static final long DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2 = -4;
    private static final String UWP_FRAME = "ApplicationFrameWindow";
    private static final String UWP_CORE = "Windows.UI.Core.CoreWindow";
    private static final MemoryLayout RECT = MemoryLayout.sequenceLayout(4, JAVA_INT);

    private final MethodHandle enumWindows;
    private final MethodHandle getWindowThreadProcessId;
    private final MethodHandle isWindow;
    private final MethodHandle isWindowVisible;
    private final MethodHandle isIconic;
    private final MethodHandle getWindowTextLength;
    private final MethodHandle getWindowText;
    private final MethodHandle getWindowRect;
    private final MethodHandle getWindowLongPtr;
    private final MethodHandle getForegroundWindow;
    private final MethodHandle setForegroundWindow;
    private final MethodHandle bringWindowToTop;
    private final MethodHandle showWindow;
    private final MethodHandle attachThreadInput;
    private final MethodHandle keybdEvent;
    private final MethodHandle getCurrentThreadId;
    private final MethodHandle setThreadDpiAwarenessContext;
    private final MethodHandle dwmGetWindowAttribute;
    private final MethodHandle getClassName;
    private final MethodHandle findWindowEx;
    private final MethodHandle enumCallback;

    Win32WindowSystem() {
        Linker linker = Linker.nativeLinker();
        SymbolLookup user32 = SymbolLookup.libraryLookup("user32", Arena.global());
        SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
        SymbolLookup dwmapi = SymbolLookup.libraryLookup("dwmapi", Arena.global());
        enumWindows = Natives.bind(linker, user32, "EnumWindows", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG));
        getWindowThreadProcessId = Natives.bind(linker, user32, "GetWindowThreadProcessId",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        isWindow = Natives.bind(linker, user32, "IsWindow", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        isWindowVisible = Natives.bind(linker, user32, "IsWindowVisible", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        isIconic = Natives.bind(linker, user32, "IsIconic", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        getWindowTextLength = Natives.bind(linker, user32, "GetWindowTextLengthW", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        getWindowText = Natives.bind(linker, user32, "GetWindowTextW",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
        getWindowRect = Natives.bind(linker, user32, "GetWindowRect", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        getWindowLongPtr = Natives.bind(linker, user32, "GetWindowLongPtrW",
                FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT));
        getForegroundWindow = Natives.bind(linker, user32, "GetForegroundWindow", FunctionDescriptor.of(ADDRESS));
        setForegroundWindow = Natives.bind(linker, user32, "SetForegroundWindow", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        bringWindowToTop = Natives.bind(linker, user32, "BringWindowToTop", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        showWindow = Natives.bind(linker, user32, "ShowWindow", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        attachThreadInput = Natives.bind(linker, user32, "AttachThreadInput",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
        keybdEvent = Natives.bind(linker, user32, "keybd_event",
                FunctionDescriptor.ofVoid(JAVA_BYTE, JAVA_BYTE, JAVA_INT, JAVA_LONG));
        getCurrentThreadId = Natives.bind(linker, kernel32, "GetCurrentThreadId", FunctionDescriptor.of(JAVA_INT));
        // erst ab Windows 10 1607 – fehlt die Funktion, liefert Windows bei DPI-bewusster JVM trotzdem physische Pixel
        setThreadDpiAwarenessContext = Natives.bindOptional(linker, user32, "SetThreadDpiAwarenessContext",
                FunctionDescriptor.of(ADDRESS, ADDRESS));
        dwmGetWindowAttribute = Natives.bindOptional(linker, dwmapi, "DwmGetWindowAttribute",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
        getClassName = Natives.bind(linker, user32, "GetClassNameW", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
        findWindowEx = Natives.bind(linker, user32, "FindWindowExW",
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        try {
            enumCallback = MethodHandles.lookup().findVirtual(Collector.class, "accept",
                    MethodType.methodType(int.class, MemorySegment.class, long.class));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public String name() {
        return "Windows (user32)";
    }

    @Override
    public Optional<String> unsupportedReason() {
        return Optional.empty();
    }

    /** Sammelt die HWNDs aus dem {@code EnumWindows}-Callback. */
    static final class Collector {
        final List<Long> handles = new ArrayList<>();

        int accept(MemorySegment hwnd, long lParam) {
            handles.add(hwnd.address());
            return 1; // weiter aufzählen
        }
    }

    @Override
    public List<NativeWindow> windows() {
        return dpiAware(() -> {
            Collector collector = new Collector();
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment stub = Linker.nativeLinker().upcallStub(enumCallback.bindTo(collector),
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG), arena);
                Natives.call(enumWindows, "EnumWindows", stub, 0L);
            }
            ScreenMapper mapper = ScreenMapper.current(ScreenMapper.Mode.ANCHORED);
            List<NativeWindow> out = new ArrayList<>();
            for (long h : collector.handles) {
                describe(h, mapper).ifPresent(out::add);
            }
            return out;
        });
    }

    @Override
    public Optional<NativeWindow> window(long id) {
        return dpiAware(() -> describe(id, ScreenMapper.current(ScreenMapper.Mode.ANCHORED)));
    }

    private Optional<NativeWindow> describe(long handle, ScreenMapper mapper) {
        MemorySegment hwnd = MemorySegment.ofAddress(handle);
        if ((int) Natives.call(isWindow, "IsWindow", hwnd) == 0
                || (int) Natives.call(isWindowVisible, "IsWindowVisible", hwnd) == 0 || cloaked(hwnd)) {
            return Optional.empty();
        }
        Rectangle bounds = bounds(hwnd);
        boolean minimized = (int) Natives.call(isIconic, "IsIconic", hwnd) != 0;
        if (bounds == null || (!minimized && (bounds.width <= 0 || bounds.height <= 0))) {
            return Optional.empty();
        }
        String title = title(hwnd);
        long exStyle = (long) Natives.call(getWindowLongPtr, "GetWindowLongPtrW", hwnd, GWL_EXSTYLE);
        if (title.isEmpty() && (exStyle & WS_EX_TOOLWINDOW) != 0) {
            return Optional.empty();
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pid = arena.allocate(JAVA_INT);
            int thread = (int) Natives.call(getWindowThreadProcessId, "GetWindowThreadProcessId", uwpApp(hwnd, arena), pid);
            if (thread == 0) {
                return Optional.empty();
            }
            return Optional.of(new NativeWindow(handle, Integer.toUnsignedLong(pid.get(JAVA_INT, 0)),
                    Integer.toUnsignedLong(thread), title, mapper.toUser(bounds), minimized));
        }
    }

    /** Bei einem UWP-Rahmen dessen {@code CoreWindow} (gehört der App), sonst das Fenster selbst. */
    private MemorySegment uwpApp(MemorySegment hwnd, Arena arena) {
        if (!UWP_FRAME.equals(className(hwnd, arena))) {
            return hwnd;
        }
        MemorySegment core = (MemorySegment) Natives.call(findWindowEx, "FindWindowExW", hwnd, MemorySegment.NULL,
                wide(UWP_CORE, arena), MemorySegment.NULL);
        return core.address() == 0 ? hwnd : core; // minimiert hängt das CoreWindow nicht im Rahmen
    }

    private String className(MemorySegment hwnd, Arena arena) {
        int max = 256;
        MemorySegment buf = arena.allocate(JAVA_CHAR, max);
        int n = (int) Natives.call(getClassName, "GetClassNameW", hwnd, buf, max);
        char[] chars = new char[Math.max(0, n)];
        for (int i = 0; i < chars.length; i++) {
            chars[i] = buf.getAtIndex(JAVA_CHAR, i);
        }
        return new String(chars);
    }

    private static MemorySegment wide(String text, Arena arena) {
        MemorySegment buf = arena.allocate(JAVA_CHAR, text.length() + 1L);
        for (int i = 0; i < text.length(); i++) {
            buf.setAtIndex(JAVA_CHAR, i, text.charAt(i));
        }
        buf.setAtIndex(JAVA_CHAR, text.length(), (char) 0);
        return buf;
    }

    private boolean cloaked(MemorySegment hwnd) {
        if (dwmGetWindowAttribute == null) {
            return false;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment value = arena.allocate(JAVA_INT);
            int hr = (int) Natives.call(dwmGetWindowAttribute, "DwmGetWindowAttribute", hwnd, DWMWA_CLOAKED, value,
                    (int) JAVA_INT.byteSize());
            return hr == 0 && value.get(JAVA_INT, 0) != 0;
        }
    }

    private Rectangle bounds(MemorySegment hwnd) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment rect = arena.allocate(RECT);
            boolean ok = dwmGetWindowAttribute != null && (int) Natives.call(dwmGetWindowAttribute,
                    "DwmGetWindowAttribute", hwnd, DWMWA_EXTENDED_FRAME_BOUNDS, rect, (int) RECT.byteSize()) == 0;
            if (!ok && (int) Natives.call(getWindowRect, "GetWindowRect", hwnd, rect) == 0) {
                return null;
            }
            int left = rect.getAtIndex(JAVA_INT, 0);
            int top = rect.getAtIndex(JAVA_INT, 1);
            return new Rectangle(left, top, rect.getAtIndex(JAVA_INT, 2) - left, rect.getAtIndex(JAVA_INT, 3) - top);
        }
    }

    private String title(MemorySegment hwnd) {
        int len = (int) Natives.call(getWindowTextLength, "GetWindowTextLengthW", hwnd);
        if (len <= 0) {
            return "";
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(JAVA_CHAR, len + 1L);
            int n = (int) Natives.call(getWindowText, "GetWindowTextW", hwnd, buf, len + 1);
            char[] chars = new char[Math.max(0, n)];
            for (int i = 0; i < chars.length; i++) {
                chars[i] = buf.getAtIndex(JAVA_CHAR, i);
            }
            return new String(chars);
        }
    }

    private volatile Win32Messages messages;

    @Override
    public boolean canStackAbove() {
        return true;
    }

    @Override
    public boolean stackAbove(List<java.awt.Window> overlays, NativeWindow target) {
        // geht schon, bevor die Fenster sichtbar sind (natives Fenster nach addNotify) – nichts blitzt vorn auf
        long[] own = overlays.stream().map(com.sun.jna.Native::getWindowPointer).filter(java.util.Objects::nonNull)
                .mapToLong(com.sun.jna.Pointer::nativeValue).toArray();
        return own.length == overlays.size() && Win32ZOrder.stackAbove(own, target.id());
    }

    /**
     * {@code WS_EX_TRANSPARENT} nimmt das Fenster aus der Treffersuche, wirkt bei Top-Level-Fenstern aber nur zusammen
     * mit {@code WS_EX_LAYERED}. AWT entfernt {@code WS_EX_LAYERED} wieder, wenn die Deckkraft auf 1 geht – deshalb bei
     * jedem Aufruf prüfen und nachziehen.
     */
    @Override
    public void passThrough(java.awt.Window overlay) {
        com.sun.jna.Pointer p = com.sun.jna.Native.getWindowPointer(overlay);
        if (p == null) {
            return;
        }
        com.sun.jna.platform.win32.WinDef.HWND h = new com.sun.jna.platform.win32.WinDef.HWND(p);
        com.sun.jna.platform.win32.User32 u = com.sun.jna.platform.win32.User32.INSTANCE;
        int ex = u.GetWindowLong(h, GWL_EXSTYLE);
        if ((ex & WS_EX_LAYERED) != 0 && (ex & WS_EX_TRANSPARENT) != 0) {
            return;
        }
        u.SetWindowLong(h, GWL_EXSTYLE, ex | WS_EX_LAYERED | WS_EX_TRANSPARENT);
        if ((ex & WS_EX_LAYERED) == 0) {
            u.SetLayeredWindowAttributes(h, 0, (byte) 0xFF, LWA_ALPHA); // sonst bliebe ein Layered-Fenster unsichtbar
        }
    }

    /** {@code PrintWindow} über {@link Win32Messages} – das Fenster bleibt, wo es ist. */
    @Override
    public Optional<java.awt.image.BufferedImage> captureInBackground(NativeWindow window) {
        Win32Messages m = messages;
        if (m == null) {
            m = Win32Messages.create();
            messages = m;
        }
        try {
            return Optional.of(m.capture(window.id()));
        } catch (IllegalStateException e) {
            return Optional.empty();
        }
    }

    @Override
    public OptionalLong foreground() {
        MemorySegment hwnd = (MemorySegment) Natives.call(getForegroundWindow, "GetForegroundWindow");
        return hwnd.address() == 0 ? OptionalLong.empty() : OptionalLong.of(hwnd.address());
    }

    /**
     * {@code SetForegroundWindow} gelingt nur, wenn der Aufrufer selbst Eingaben bekommen hat. Deshalb vorher die
     * Eingabe-Queue an den Thread des aktuellen Vordergrundfensters hängen; reicht das nicht, zusätzlich kurz Alt
     * drücken (der übliche Weg, die Vordergrundsperre zu lösen).
     */
    @Override
    public void activate(long id) {
        MemorySegment hwnd = MemorySegment.ofAddress(id);
        if ((int) Natives.call(isIconic, "IsIconic", hwnd) != 0) {
            Natives.call(showWindow, "ShowWindow", hwnd, SW_RESTORE);
        }
        MemorySegment fg = (MemorySegment) Natives.call(getForegroundWindow, "GetForegroundWindow");
        if (fg.address() == id) {
            return;
        }
        int self = (int) Natives.call(getCurrentThreadId, "GetCurrentThreadId");
        int fgThread = fg.address() == 0 ? 0
                : (int) Natives.call(getWindowThreadProcessId, "GetWindowThreadProcessId", fg, MemorySegment.NULL);
        boolean attached = fgThread != 0 && fgThread != self
                && (int) Natives.call(attachThreadInput, "AttachThreadInput", self, fgThread, 1) != 0;
        try {
            Natives.call(setForegroundWindow, "SetForegroundWindow", hwnd);
            Natives.call(bringWindowToTop, "BringWindowToTop", hwnd);
            if (foreground().orElse(0) != id) {
                Natives.call(keybdEvent, "keybd_event", VK_MENU, (byte) 0, 0, 0L);
                try {
                    Natives.call(setForegroundWindow, "SetForegroundWindow", hwnd);
                } finally {
                    Natives.call(keybdEvent, "keybd_event", VK_MENU, (byte) 0, KEYEVENTF_KEYUP, 0L);
                }
            }
        } finally {
            if (attached) {
                Natives.call(attachThreadInput, "AttachThreadInput", self, fgThread, 0);
            }
        }
    }

    private <T> T dpiAware(java.util.function.Supplier<T> body) {
        if (setThreadDpiAwarenessContext == null) {
            return body.get();
        }
        MemorySegment previous = (MemorySegment) Natives.call(setThreadDpiAwarenessContext,
                "SetThreadDpiAwarenessContext", MemorySegment.ofAddress(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2));
        try {
            return body.get();
        } finally {
            if (previous.address() != 0) {
                Natives.call(setThreadDpiAwarenessContext, "SetThreadDpiAwarenessContext", previous);
            }
        }
    }
}
