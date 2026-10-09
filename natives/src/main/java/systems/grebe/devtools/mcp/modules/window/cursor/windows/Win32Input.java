package systems.grebe.devtools.mcp.modules.window.cursor.windows;

import java.awt.Point;
import java.util.Set;
import java.util.function.Supplier;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.POINT;
import com.sun.jna.platform.win32.WinDef.WPARAM;

import systems.grebe.devtools.mcp.modules.window.cursor.MouseButton;

/**
 * Mauseingaben als Fenster-Nachrichten ({@code PostMessageW}) an das Fenster unter einem Punkt – der Mauszeiger des
 * Nutzers bewegt sich nicht, das Fenster wird nicht aktiviert.
 *
 * <p>Punkte sind physische Bildschirmpixel. Gesucht wird im Per-Monitor-DPI-Kontext; Client-Koordinaten rechnet jedes
 * Fenster in seinem eigenen DPI-Kontext um (DPI-unabhängige Anwendungen erwarten logische Koordinaten).
 *
 * <p>Anwendungen, die Eingaben nicht über Fenster-Nachrichten lesen (UWP/WinUI wie der Windows-Rechner), erkennt
 * {@link #isModernUi} an ihren Fensterklassen; sie bedient der Controller per UI Automation ({@link Win32Automation})
 * bzw. Touch ({@link Win32Touch}) – beides bewegt den Mauszeiger ebenfalls nicht. Spiele mit Raw Input reagieren auf
 * nichts davon.
 */
final class Win32Input {

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
    private static final int MK_LBUTTON = 0x1;
    private static final int MK_RBUTTON = 0x2;
    private static final int MK_MBUTTON = 0x10;
    private static final int MK_SHIFT = 0x4;
    private static final int MK_CONTROL = 0x8;
    private static final int WHEEL_DELTA = 120;
    private static final int CWP_SKIPINVISIBLE = 0x1;
    private static final int CWP_SKIPTRANSPARENT = 0x4;
    private static final int GCL_STYLE = -26;
    private static final int CS_DBLCLKS = 0x8;
    private static final int MAX_DEPTH = 32;
    private static final int GA_ROOT = 2;

    private final User32Ext user32 = User32Ext.INSTANCE;
    private final Win32Touch touch = new Win32Touch();

    /**
     * Das innerste Fenster unter dem Punkt. Durchklickbare Fenster ({@code WS_EX_TRANSPARENT}) wie der Zeiger selbst
     * werden übersprungen.
     *
     * @throws IllegalStateException wenn dort kein Fenster liegt
     */
    HWND hit(Point physical) {
        return dpiAware(() -> {
            HWND current = User32.INSTANCE.GetDesktopWindow();
            for (int depth = 0; depth < MAX_DEPTH; depth++) {
                HWND parent = current;
                // Umrechnung und Suche im DPI-Kontext des Fensters, sonst passen die Koordinaten nicht zusammen
                HWND child = withContext(context(parent), () -> {
                    Point client = clientInOwnContext(parent, logical(parent, physical));
                    return user32.ChildWindowFromPointEx(parent, new POINT.ByValue(client.x, client.y),
                            CWP_SKIPINVISIBLE | CWP_SKIPTRANSPARENT);
                });
                if (child == null || child.equals(current)) {
                    break;
                }
                current = child;
            }
            if (current.equals(User32.INSTANCE.GetDesktopWindow())) {
                throw new IllegalStateException("Unter dem Zeiger (" + physical.x + ", " + physical.y + ") liegt kein "
                        + "Fenster.");
            }
            return current;
        });
    }

    /**
     * Ob das Fenster Maus-Nachrichten ignoriert: UWP-Rahmen ({@code ApplicationFrameWindow}) und XAML-Inhalte von UWP
     * und WinUI ({@code Windows.UI.*}, {@code Microsoft.UI.*}).
     */
    boolean isModernUi(HWND hwnd) {
        String own = className(hwnd);
        HWND rootHwnd = user32.GetAncestor(hwnd, GA_ROOT);
        String root = rootHwnd == null ? own : className(rootHwnd);
        return "ApplicationFrameWindow".equals(root) || modernUi(own) || modernUi(root);
    }

    private static boolean modernUi(String className) {
        return className.startsWith("Windows.UI.") || className.startsWith("Microsoft.UI.")
                || className.startsWith("WinUIDesktopWin32WindowClass");
    }

    /**
     * Tastatur-Ziel ohne eigenen Fokus des Zeigers: bei UWP/WinUI das {@code CoreWindow} des Fensters unter dem Punkt,
     * sonst dessen fokussiertes Element, sofern Windows es kennt (bei Fenstern im Hintergrund meist nicht), sonst das
     * Top-Level-Fenster selbst (Dialoge reichen Zeichen dann an ihr Standard-Element weiter).
     */
    HWND keyboardTarget(Point physical) {
        HWND hit = hit(physical);
        HWND root = user32.GetAncestor(hit, GA_ROOT);
        if (root == null) {
            root = hit;
        }
        if (isModernUi(hit)) {
            HWND core = user32.FindWindowEx(root, null, "Windows.UI.Core.CoreWindow", null);
            return core != null ? core : hit;
        }
        int thread = User32.INSTANCE.GetWindowThreadProcessId(root, null);
        WinUser.GUITHREADINFO info = new WinUser.GUITHREADINFO();
        info.cbSize = info.size();
        if (thread != 0 && User32.INSTANCE.GetGUIThreadInfo(thread, info) && info.hwndFocus != null
                && (info.hwndFocus.equals(root) || user32.IsChild(root, info.hwndFocus))) {
            return info.hwndFocus;
        }
        return root;
    }

    /** Prozess des Fensters und seines Top-Level-Fensters (bei UWP: App und ApplicationFrameHost). */
    Set<Long> pids(HWND hwnd) {
        java.util.Set<Long> out = new java.util.HashSet<>();
        out.add(pid(hwnd));
        HWND root = user32.GetAncestor(hwnd, GA_ROOT);
        if (root != null) {
            out.add(pid(root));
        }
        return out;
    }

    private static long pid(HWND hwnd) {
        com.sun.jna.ptr.IntByReference pid = new com.sun.jna.ptr.IntByReference();
        User32.INSTANCE.GetWindowThreadProcessId(hwnd, pid);
        return Integer.toUnsignedLong(pid.getValue());
    }

    static String className(HWND hwnd) {
        char[] buf = new char[256];
        int n = User32.INSTANCE.GetClassName(hwnd, buf, buf.length);
        return n <= 0 ? "" : new String(buf, 0, n);
    }

    void touchDown(int contact, Point physical) {
        dpiAware(() -> {
            touch.down(contact, physical);
            return null;
        });
    }

    void touchMove(int contact, Point physical) {
        dpiAware(() -> {
            touch.move(contact, physical);
            return null;
        });
    }

    void touchUp(int contact, Point physical) {
        dpiAware(() -> {
            touch.up(contact, physical);
            return null;
        });
    }

    void move(HWND target, Point physical, Set<MouseButton> held) {
        post(target, WM_MOUSEMOVE, mk(held), clientParam(target, physical));
    }

    /** @param repeat zweiter oder weiterer Klick in Folge – Doppelklick-Nachricht, wenn das Fenster sie haben will */
    void button(HWND target, Point physical, MouseButton button, boolean down, boolean repeat, Set<MouseButton> held) {
        button(target, physical, button, down, repeat, held, Set.of());
    }

    /** Wie oben, mit gehaltenen Tastaturtasten ({@code MK_CONTROL}, {@code MK_SHIFT} im wParam). */
    void button(HWND target, Point physical, MouseButton button, boolean down, boolean repeat, Set<MouseButton> held,
                Set<Integer> heldKeys) {
        boolean dbl = down && repeat && wantsDoubleClicks(target);
        int msg = switch (button) {
            case LEFT -> down ? (dbl ? WM_LBUTTONDBLCLK : WM_LBUTTONDOWN) : WM_LBUTTONUP;
            case RIGHT -> down ? (dbl ? WM_RBUTTONDBLCLK : WM_RBUTTONDOWN) : WM_RBUTTONUP;
            case MIDDLE -> down ? (dbl ? WM_MBUTTONDBLCLK : WM_MBUTTONDOWN) : WM_MBUTTONUP;
        };
        post(target, msg, mk(held) | mkKeys(heldKeys), clientParam(target, physical));
    }

    private static long mkKeys(Set<Integer> heldKeys) {
        long mk = 0;
        if (heldKeys.contains(java.awt.event.KeyEvent.VK_SHIFT)) {
            mk |= MK_SHIFT;
        }
        if (heldKeys.contains(java.awt.event.KeyEvent.VK_CONTROL)) {
            mk |= MK_CONTROL;
        }
        return mk;
    }

    /** {@code WM_MOUSEWHEEL} trägt Bildschirmkoordinaten (logisch für das Zielfenster); positiv = nach unten. */
    void wheel(HWND target, Point physical, int notches, Set<MouseButton> held) {
        Point screen = dpiAware(() -> logical(target, physical));
        long wParam = ((long) (short) (-WHEEL_DELTA * notches) << 16 & 0xFFFF0000L) | mk(held);
        post(target, WM_MOUSEWHEEL, wParam, lParam(screen.x, screen.y));
    }

    private boolean wantsDoubleClicks(HWND hwnd) {
        Pointer style = user32.GetClassLongPtr(hwnd, GCL_STYLE);
        return (Pointer.nativeValue(style) & CS_DBLCLKS) != 0;
    }

    private static long mk(Set<MouseButton> held) {
        long mk = 0;
        if (held.contains(MouseButton.LEFT)) {
            mk |= MK_LBUTTON;
        }
        if (held.contains(MouseButton.RIGHT)) {
            mk |= MK_RBUTTON;
        }
        if (held.contains(MouseButton.MIDDLE)) {
            mk |= MK_MBUTTON;
        }
        return mk;
    }

    private void post(HWND hwnd, int msg, long wParam, long lParam) {
        if (!user32.PostMessage(hwnd, msg, new WPARAM(wParam), new LPARAM(lParam))) {
            throw new IllegalStateException("Nachricht an Fenster " + hwnd + " nicht zustellbar (geschlossen oder "
                    + "höhere Rechte).");
        }
    }

    private long clientParam(HWND hwnd, Point physical) {
        Point c = dpiAware(() -> {
            Point logical = logical(hwnd, physical);
            return withContext(context(hwnd), () -> clientInOwnContext(hwnd, logical));
        });
        return lParam(c.x, c.y);
    }

    private static long lParam(int x, int y) {
        return (x & 0xFFFFL) | ((y & 0xFFFFL) << 16);
    }

    /** Physische in logische Bildschirmkoordinaten des Fensters; der Thread muss Per-Monitor-aware sein. */
    private Point logical(HWND hwnd, Point physical) {
        POINT pt = new POINT(physical.x, physical.y);
        try {
            user32.PhysicalToLogicalPointForPerMonitorDPI(hwnd, pt);
        } catch (UnsatisfiedLinkError e) {
            return new Point(physical);
        }
        return new Point(pt.x, pt.y);
    }

    /** {@code ScreenToClient}; der Thread muss im DPI-Kontext des Fensters laufen. */
    private Point clientInOwnContext(HWND hwnd, Point logical) {
        POINT pt = new POINT(logical.x, logical.y);
        user32.ScreenToClient(hwnd, pt);
        return new Point(pt.x, pt.y);
    }

    private Pointer context(HWND hwnd) {
        try {
            return user32.GetWindowDpiAwarenessContext(hwnd);
        } catch (UnsatisfiedLinkError e) {
            return null;
        }
    }

    private <T> T dpiAware(Supplier<T> body) {
        return withContext(User32Ext.PER_MONITOR_AWARE_V2, body);
    }

    private <T> T withContext(Pointer context, Supplier<T> body) {
        if (context == null) {
            return body.get();
        }
        Pointer previous;
        try {
            previous = user32.SetThreadDpiAwarenessContext(context);
        } catch (UnsatisfiedLinkError e) {
            return body.get();
        }
        try {
            return body.get();
        } finally {
            if (previous != null) {
                user32.SetThreadDpiAwarenessContext(previous);
            }
        }
    }
}
