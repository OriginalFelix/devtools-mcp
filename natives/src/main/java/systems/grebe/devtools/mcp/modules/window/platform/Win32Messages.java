package systems.grebe.devtools.mcp.modules.window.platform;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Locale;
import java.util.function.Supplier;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * Windows: Eingaben als Fenster-Nachrichten direkt an ein Fenster ({@code PostMessageW}) und Bilder per
 * {@code PrintWindow} – ohne den Mauszeiger des Nutzers zu bewegen und ohne das Fenster zu aktivieren. Die KI hat
 * damit ihren eigenen, nur gezeichneten Zeiger.
 *
 * <p>Grenzen: Anwendungen, die Eingaben nicht über Fenster-Nachrichten lesen (UWP/WinUI wie der Windows-Rechner, viele
 * Spiele), reagieren nicht; Tastenkombinationen mit Strg/Alt sehen Anwendungen oft nicht, weil sie den echten
 * Tastaturzustand abfragen.
 *
 * <p>Koordinaten sind physische Bildschirmpixel; sie werden je Zielfenster in dessen eigenes DPI-Koordinatensystem
 * umgerechnet (DPI-unabhängige Anwendungen erwarten logische Koordinaten).
 */
public final class Win32Messages {

    public static final int WM_KEYDOWN = 0x100;
    public static final int WM_KEYUP = 0x101;
    private static final int WM_CHAR = 0x102;
    private static final int WM_SYSKEYDOWN = 0x104;
    private static final int WM_SYSKEYUP = 0x105;
    private static final int WM_MOUSEMOVE = 0x200;
    private static final int WM_LBUTTONDOWN = 0x201;
    private static final int WM_LBUTTONUP = 0x202;
    private static final int WM_LBUTTONDBLCLK = 0x203;
    private static final int WM_RBUTTONDOWN = 0x204;
    private static final int WM_RBUTTONUP = 0x205;
    private static final int WM_RBUTTONDBLCLK = 0x206;
    private static final int WM_MBUTTONDOWN = 0x207;
    private static final int WM_MBUTTONUP = 0x208;
    private static final int WM_MBUTTONDBLCLK = 0x209;
    private static final int WM_MOUSEWHEEL = 0x20A;
    private static final int CWP_SKIPINVISIBLE = 0x1;
    private static final int CWP_SKIPTRANSPARENT = 0x4;
    private static final int GA_ROOT = 2;
    private static final int SW_SHOWNOACTIVATE = 4;
    private static final int PW_RENDERFULLCONTENT = 2;
    private static final int DWMWA_EXTENDED_FRAME_BOUNDS = 9;
    private static final int MAPVK_VK_TO_VSC = 0;
    private static final long DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2 = -4;

    /** Maustaste: 1 links, 2 Mitte, 3 rechts (wie {@link java.awt.event.MouseEvent#getButton}). */
    public static final int LEFT = 1;
    public static final int MIDDLE = 2;
    public static final int RIGHT = 3;

    private static final MemoryLayout POINT = MemoryLayout.structLayout(JAVA_INT.withName("x"), JAVA_INT.withName("y"));
    private static final MemoryLayout RECT = MemoryLayout.sequenceLayout(4, JAVA_INT);
    private static final int GUITHREADINFO_SIZE = 72;
    private static final int GUITHREADINFO_FOCUS = 16;

    private final MethodHandle postMessage;
    private final MethodHandle childWindowFromPointEx;
    private final MethodHandle screenToClient;
    private final MethodHandle physicalToLogical;
    private final MethodHandle getWindowDpiAwarenessContext;
    private final MethodHandle setThreadDpiAwarenessContext;
    private final MethodHandle getWindowThreadProcessId;
    private final MethodHandle getGuiThreadInfo;
    private final MethodHandle getAncestor;
    private final MethodHandle isIconic;
    private final MethodHandle showWindow;
    private final MethodHandle mapVirtualKey;
    private final MethodHandle vkKeyScan;
    private final MethodHandle getAsyncKeyState;
    private final MethodHandle getCursorPos;
    private final MethodHandle getWindowRect;
    private final MethodHandle printWindow;
    private final MethodHandle getDc;
    private final MethodHandle releaseDc;
    private final MethodHandle createCompatibleDc;
    private final MethodHandle createCompatibleBitmap;
    private final MethodHandle selectObject;
    private final MethodHandle getDiBits;
    private final MethodHandle deleteObject;
    private final MethodHandle deleteDc;
    private final MethodHandle dwmGetWindowAttribute;

    private Win32Messages() {
        Linker linker = Linker.nativeLinker();
        Arena global = Arena.global();
        SymbolLookup user32 = SymbolLookup.libraryLookup("user32", global);
        SymbolLookup gdi32 = SymbolLookup.libraryLookup("gdi32", global);
        SymbolLookup dwmapi = SymbolLookup.libraryLookup("dwmapi", global);
        postMessage = Natives.bind(linker, user32, "PostMessageW",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG));
        childWindowFromPointEx = Natives.bind(linker, user32, "ChildWindowFromPointEx",
                FunctionDescriptor.of(ADDRESS, ADDRESS, POINT, JAVA_INT));
        screenToClient = Natives.bind(linker, user32, "ScreenToClient", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        physicalToLogical = Natives.bindOptional(linker, user32, "PhysicalToLogicalPointForPerMonitorDPI",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        getWindowDpiAwarenessContext = Natives.bindOptional(linker, user32, "GetWindowDpiAwarenessContext",
                FunctionDescriptor.of(ADDRESS, ADDRESS));
        setThreadDpiAwarenessContext = Natives.bindOptional(linker, user32, "SetThreadDpiAwarenessContext",
                FunctionDescriptor.of(ADDRESS, ADDRESS));
        getWindowThreadProcessId = Natives.bind(linker, user32, "GetWindowThreadProcessId",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        getGuiThreadInfo = Natives.bind(linker, user32, "GetGUIThreadInfo", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS));
        getAncestor = Natives.bind(linker, user32, "GetAncestor", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
        isIconic = Natives.bind(linker, user32, "IsIconic", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        showWindow = Natives.bind(linker, user32, "ShowWindow", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        mapVirtualKey = Natives.bind(linker, user32, "MapVirtualKeyW", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));
        vkKeyScan = Natives.bind(linker, user32, "VkKeyScanW", FunctionDescriptor.of(JAVA_SHORT, JAVA_SHORT));
        getAsyncKeyState = Natives.bind(linker, user32, "GetAsyncKeyState", FunctionDescriptor.of(JAVA_SHORT, JAVA_INT));
        getCursorPos = Natives.bind(linker, user32, "GetCursorPos", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        getWindowRect = Natives.bind(linker, user32, "GetWindowRect", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        printWindow = Natives.bind(linker, user32, "PrintWindow", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
        getDc = Natives.bind(linker, user32, "GetDC", FunctionDescriptor.of(ADDRESS, ADDRESS));
        releaseDc = Natives.bind(linker, user32, "ReleaseDC", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        createCompatibleDc = Natives.bind(linker, gdi32, "CreateCompatibleDC", FunctionDescriptor.of(ADDRESS, ADDRESS));
        createCompatibleBitmap = Natives.bind(linker, gdi32, "CreateCompatibleBitmap",
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));
        selectObject = Natives.bind(linker, gdi32, "SelectObject", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
        getDiBits = Natives.bind(linker, gdi32, "GetDIBits", FunctionDescriptor.of(JAVA_INT,
                ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
        deleteObject = Natives.bind(linker, gdi32, "DeleteObject", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        deleteDc = Natives.bind(linker, gdi32, "DeleteDC", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        dwmGetWindowAttribute = Natives.bindOptional(linker, dwmapi, "DwmGetWindowAttribute",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
    }

    /** Nur unter Windows; sonst mit verständlichem Grund. */
    public static Win32Messages create() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            throw new IllegalStateException("Der eigene KI-Zeiger (Steuerungsmodus „eigener-zeiger“) ist nur unter "
                    + "Windows möglich – unter macOS und Linux in der App den Modus „maus“ wählen.");
        }
        return new Win32Messages();
    }

    // ------------------------------------------------------------------ Maus

    /** Das Fenster unter dem Punkt (tiefstes sichtbares Kind von {@code root}) samt Prozess. */
    public record Hit(long hwnd, long pid) {
    }

    /** Sucht das Kindfenster von {@code root} unter dem Punkt (physische Pixel). */
    public Hit hit(long root, Point physical) {
        return dpiAware(() -> {
            MemorySegment current = MemorySegment.ofAddress(root);
            for (int depth = 0; depth < 32; depth++) {
                MemorySegment parent = current;
                // Umrechnung und Suche im DPI-Kontext des Fensters, sonst passen die Koordinaten nicht zusammen
                MemorySegment child = withContext(context(parent), () -> {
                    Point client = clientInOwnContext(parent, logical(parent, physical));
                    try (Arena arena = Arena.ofConfined()) {
                        MemorySegment pt = arena.allocate(POINT);
                        pt.set(JAVA_INT, 0, client.x);
                        pt.set(JAVA_INT, 4, client.y);
                        return (MemorySegment) Natives.call(childWindowFromPointEx, "ChildWindowFromPointEx", parent, pt,
                                CWP_SKIPINVISIBLE | CWP_SKIPTRANSPARENT);
                    }
                });
                if (child.address() == 0 || child.address() == current.address()) {
                    break;
                }
                current = child;
            }
            return new Hit(current.address(), pid(current));
        });
    }

    /** Mausbewegung über dem Fenster (ohne den Systemzeiger zu bewegen). */
    public void mouseMove(Hit hit, Point physical, int buttonsHeld) {
        post(hit.hwnd(), WM_MOUSEMOVE, mk(buttonsHeld), clientParam(hit.hwnd(), physical));
    }

    /** Maustaste drücken/loslassen; {@code doubleClick} sendet beim zweiten Drücken die Doppelklick-Nachricht. */
    public void mouseButton(Hit hit, Point physical, int button, boolean down, boolean doubleClick, int buttonsHeld) {
        int msg = switch (button) {
            case RIGHT -> down ? (doubleClick ? WM_RBUTTONDBLCLK : WM_RBUTTONDOWN) : WM_RBUTTONUP;
            case MIDDLE -> down ? (doubleClick ? WM_MBUTTONDBLCLK : WM_MBUTTONDOWN) : WM_MBUTTONUP;
            default -> down ? (doubleClick ? WM_LBUTTONDBLCLK : WM_LBUTTONDOWN) : WM_LBUTTONUP;
        };
        post(hit.hwnd(), msg, mk(buttonsHeld), clientParam(hit.hwnd(), physical));
    }

    /** Mausrad; positiv = nach unten. */
    public void wheel(Hit hit, Point physical, int notches, int buttonsHeld) {
        Point logical = dpiAware(() -> logical(MemorySegment.ofAddress(hit.hwnd()), physical));
        long wParam = ((long) (short) (-120 * notches) << 16 & 0xFFFF0000L) | mk(buttonsHeld);
        post(hit.hwnd(), WM_MOUSEWHEEL, wParam, lParam(logical.x, logical.y));
    }

    private static long mk(int buttonsHeld) {
        long mk = 0;
        if ((buttonsHeld & (1 << LEFT)) != 0) {
            mk |= 0x1;
        }
        if ((buttonsHeld & (1 << RIGHT)) != 0) {
            mk |= 0x2;
        }
        if ((buttonsHeld & (1 << MIDDLE)) != 0) {
            mk |= 0x10;
        }
        return mk;
    }

    // ------------------------------------------------------------------ Tastatur

    /** Fenster mit dem Tastaturfokus im UI-Thread von {@code root} (auch wenn es nicht im Vordergrund ist). */
    public long focus(long root) {
        MemorySegment hwnd = MemorySegment.ofAddress(root);
        int thread = (int) Natives.call(getWindowThreadProcessId, "GetWindowThreadProcessId", hwnd, MemorySegment.NULL);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment info = arena.allocate(GUITHREADINFO_SIZE, 8);
            info.set(JAVA_INT, 0, GUITHREADINFO_SIZE);
            if ((int) Natives.call(getGuiThreadInfo, "GetGUIThreadInfo", thread, info) != 0) {
                long focus = info.get(JAVA_LONG, GUITHREADINFO_FOCUS);
                if (focus != 0) {
                    return focus;
                }
            }
        }
        return root;
    }

    /** Taste drücken/loslassen (Java-Tastencode); {@code altHeld} kennzeichnet Alt-Kombinationen. */
    public void key(long target, int javaKeyCode, boolean down, boolean altHeld) {
        int vk = virtualKey(javaKeyCode);
        int scan = (int) Natives.call(mapVirtualKey, "MapVirtualKeyW", vk, MAPVK_VK_TO_VSC);
        long lParam = 1L | ((long) (scan & 0xFF) << 16) | (altHeld ? 1L << 29 : 0) | (down ? 0 : (1L << 30) | (1L << 31));
        boolean sys = altHeld || vk == 0x12;
        post(target, down ? (sys ? WM_SYSKEYDOWN : WM_KEYDOWN) : (sys ? WM_SYSKEYUP : WM_KEYUP), vk, lParam);
    }

    /** Ein Zeichen als {@code WM_CHAR} – unabhängig von Tastaturlayout und Zwischenablage. */
    public void typeChar(long target, char c) {
        post(target, WM_CHAR, c, 1L);
    }

    /** Ob die Taste gerade physisch gedrückt ist (für die Abbruch-Tastenkombination). */
    public boolean keyDown(int javaKeyCode) {
        return ((short) Natives.call(getAsyncKeyState, "GetAsyncKeyState", virtualKey(javaKeyCode)) & 0x8000) != 0;
    }

    /** Java-Tastencode → Windows-Virtual-Key (für die meisten Tasten identisch). */
    int virtualKey(int javaKeyCode) {
        return switch (javaKeyCode) {
            case KeyEvent.VK_ENTER -> 0x0D;
            case KeyEvent.VK_DELETE -> 0x2E;
            case KeyEvent.VK_INSERT -> 0x2D;
            case KeyEvent.VK_META, KeyEvent.VK_WINDOWS -> 0x5B;
            case KeyEvent.VK_CONTEXT_MENU -> 0x5D;
            case KeyEvent.VK_PRINTSCREEN -> 0x2C;
            case KeyEvent.VK_PLUS -> 0xBB;
            case KeyEvent.VK_ALT_GRAPH -> 0xA5;
            default -> {
                if (javaKeyCode >= 0x01000000) { // erweiterter Code eines Zeichens
                    short scan = (short) Natives.call(vkKeyScan, "VkKeyScanW", (short) (javaKeyCode - 0x01000000));
                    yield scan == -1 ? 0 : scan & 0xFF;
                }
                yield javaKeyCode;
            }
        };
    }

    // ------------------------------------------------------------------ Fenster, Bild, Zeiger

    /** Stellt ein minimiertes Fenster wieder her, ohne es zu aktivieren. */
    public void restoreWithoutActivating(long root) {
        MemorySegment hwnd = MemorySegment.ofAddress(root);
        if ((int) Natives.call(isIconic, "IsIconic", hwnd) != 0) {
            Natives.call(showWindow, "ShowWindow", hwnd, SW_SHOWNOACTIVATE);
        }
    }

    /** Oberstes Fenster der Hierarchie. */
    public long root(long hwnd) {
        return ((MemorySegment) Natives.call(getAncestor, "GetAncestor", MemorySegment.ofAddress(hwnd), GA_ROOT)).address();
    }

    /**
     * Bild des Fensters über {@code PrintWindow} – auch wenn es verdeckt ist. Ausschnitt: die sichtbaren Grenzen
     * (ohne unsichtbare Ränder), in physischen Pixeln.
     */
    public BufferedImage capture(long root) {
        return dpiAware(() -> {
            MemorySegment hwnd = MemorySegment.ofAddress(root);
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment rect = arena.allocate(RECT);
                if ((int) Natives.call(getWindowRect, "GetWindowRect", hwnd, rect) == 0) {
                    throw new IllegalStateException("Fenstergrenzen nicht lesbar.");
                }
                Rectangle outer = rect(rect);
                Rectangle visible = outer;
                if (dwmGetWindowAttribute != null && (int) Natives.call(dwmGetWindowAttribute, "DwmGetWindowAttribute",
                        hwnd, DWMWA_EXTENDED_FRAME_BOUNDS, rect, (int) RECT.byteSize()) == 0) {
                    visible = rect(rect).intersection(outer);
                }
                BufferedImage full = print(hwnd, outer.width, outer.height, arena);
                return full.getSubimage(visible.x - outer.x, visible.y - outer.y, visible.width, visible.height);
            }
        });
    }

    private BufferedImage print(MemorySegment hwnd, int width, int height, Arena arena) {
        MemorySegment screen = (MemorySegment) Natives.call(getDc, "GetDC", MemorySegment.NULL);
        MemorySegment mem = (MemorySegment) Natives.call(createCompatibleDc, "CreateCompatibleDC", screen);
        MemorySegment bmp = (MemorySegment) Natives.call(createCompatibleBitmap, "CreateCompatibleBitmap", screen, width, height);
        try {
            MemorySegment old = (MemorySegment) Natives.call(selectObject, "SelectObject", mem, bmp);
            boolean ok = (int) Natives.call(printWindow, "PrintWindow", hwnd, mem, PW_RENDERFULLCONTENT) != 0;
            Natives.call(selectObject, "SelectObject", mem, old); // GetDIBits verlangt ein nicht ausgewähltes Bitmap
            if (!ok) {
                throw new IllegalStateException("PrintWindow lieferte kein Bild dieses Fensters.");
            }
            MemorySegment info = arena.allocate(44, 4); // BITMAPINFOHEADER + 1 Farbeintrag
            info.set(JAVA_INT, 0, 40);
            info.set(JAVA_INT, 4, width);
            info.set(JAVA_INT, 8, -height); // von oben nach unten
            info.set(JAVA_SHORT, 12, (short) 1);
            info.set(JAVA_SHORT, 14, (short) 32);
            MemorySegment pixels = arena.allocate((long) width * height * 4, 4);
            if ((int) Natives.call(getDiBits, "GetDIBits", mem, bmp, 0, height, pixels, info, 0) == 0) {
                throw new IllegalStateException("Bilddaten des Fensters nicht lesbar.");
            }
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            int[] row = new int[width];
            for (int y = 0; y < height; y++) {
                MemorySegment.copy(pixels, ValueLayout.JAVA_INT, (long) y * width * 4, row, 0, width);
                image.setRGB(0, y, width, 1, row, 0, width);
            }
            return image;
        } finally {
            Natives.call(deleteObject, "DeleteObject", bmp);
            Natives.call(deleteDc, "DeleteDC", mem);
            Natives.call(releaseDc, "ReleaseDC", MemorySegment.NULL, screen);
        }
    }

    /** Position des Systemzeigers (physische Pixel) – z.B. um zu prüfen, dass er unberührt bleibt. */
    public Point cursor() {
        return dpiAware(() -> {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment pt = arena.allocate(POINT);
                Natives.call(getCursorPos, "GetCursorPos", pt);
                return new Point(pt.get(JAVA_INT, 0), pt.get(JAVA_INT, 4));
            }
        });
    }

    // ------------------------------------------------------------------ Hilfen

    private long pid(MemorySegment hwnd) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pid = arena.allocate(JAVA_INT);
            Natives.call(getWindowThreadProcessId, "GetWindowThreadProcessId", hwnd, pid);
            return Integer.toUnsignedLong(pid.get(JAVA_INT, 0));
        }
    }

    private long clientParam(long hwnd, Point physical) {
        Point c = dpiAware(() -> client(MemorySegment.ofAddress(hwnd), physical));
        return lParam(c.x, c.y);
    }

    private static long lParam(int x, int y) {
        return (x & 0xFFFFL) | ((y & 0xFFFFL) << 16);
    }

    /** Physischer Bildschirmpunkt → logischer Bildschirmpunkt des Fensters (bei DPI-unabhängigen Anwendungen skaliert). */
    private Point logical(MemorySegment hwnd, Point physical) {
        if (physicalToLogical == null) {
            return new Point(physical);
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pt = arena.allocate(POINT);
            pt.set(JAVA_INT, 0, physical.x);
            pt.set(JAVA_INT, 4, physical.y);
            Natives.call(physicalToLogical, "PhysicalToLogicalPointForPerMonitorDPI", hwnd, pt);
            return new Point(pt.get(JAVA_INT, 0), pt.get(JAVA_INT, 4));
        }
    }

    /** Physischer Bildschirmpunkt → Client-Koordinaten des Fensters in dessen eigenem DPI-Kontext. */
    private Point client(MemorySegment hwnd, Point physical) {
        Point logical = logical(hwnd, physical);
        return withContext(context(hwnd), () -> clientInOwnContext(hwnd, logical));
    }

    /** {@code ScreenToClient}; der Thread muss im DPI-Kontext des Fensters laufen. */
    private Point clientInOwnContext(MemorySegment hwnd, Point logical) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pt = arena.allocate(POINT);
            pt.set(JAVA_INT, 0, logical.x);
            pt.set(JAVA_INT, 4, logical.y);
            Natives.call(screenToClient, "ScreenToClient", hwnd, pt);
            return new Point(pt.get(JAVA_INT, 0), pt.get(JAVA_INT, 4));
        }
    }

    private MemorySegment context(MemorySegment hwnd) {
        return getWindowDpiAwarenessContext == null ? null
                : (MemorySegment) Natives.call(getWindowDpiAwarenessContext, "GetWindowDpiAwarenessContext", hwnd);
    }

    private void post(long hwnd, int msg, long wParam, long lParam) {
        if ((int) Natives.call(postMessage, "PostMessageW", MemorySegment.ofAddress(hwnd), msg, wParam, lParam) == 0) {
            throw new IllegalStateException("Nachricht an Fenster 0x" + Long.toHexString(hwnd).toUpperCase(Locale.ROOT)
                    + " nicht zustellbar (geschlossen oder höhere Rechte).");
        }
    }

    private static Rectangle rect(MemorySegment r) {
        int left = r.getAtIndex(JAVA_INT, 0);
        int top = r.getAtIndex(JAVA_INT, 1);
        return new Rectangle(left, top, r.getAtIndex(JAVA_INT, 2) - left, r.getAtIndex(JAVA_INT, 3) - top);
    }

    private <T> T dpiAware(Supplier<T> body) {
        return withContext(MemorySegment.ofAddress(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2), body);
    }

    private <T> T withContext(MemorySegment ctx, Supplier<T> body) {
        if (setThreadDpiAwarenessContext == null || ctx == null || ctx.address() == 0) {
            return body.get();
        }
        MemorySegment previous = (MemorySegment) Natives.call(setThreadDpiAwarenessContext,
                "SetThreadDpiAwarenessContext", ctx);
        try {
            return body.get();
        } finally {
            if (previous.address() != 0) {
                Natives.call(setThreadDpiAwarenessContext, "SetThreadDpiAwarenessContext", previous);
            }
        }
    }
}
