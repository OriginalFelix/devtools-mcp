package systems.grebe.devtools.mcp.modules.window.platform;

import java.util.HashSet;
import java.util.Set;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinUser;

/**
 * Legt Anzeige-Fenster (Rahmen, Hinweis) in der Z-Reihenfolge direkt über ein fremdes Fenster – auf dieselbe Ebene
 * statt über alle Fenster: liegt ein anderes Fenster über dem Ziel, verdeckt es auch dessen Rahmen. Wird regelmäßig
 * nachgeführt; liegen die Fenster schon richtig, bleibt alles unverändert (kein Umsortieren, kein Flackern).
 */
final class Win32ZOrder {

    private static final HWND HWND_TOP = new HWND(Pointer.createConstant(0L));
    private static final HWND HWND_TOPMOST = new HWND(Pointer.createConstant(-1L));
    private static final HWND HWND_NOTOPMOST = new HWND(Pointer.createConstant(-2L));
    private static final int SWP_NOSIZE = 0x1;
    private static final int SWP_NOMOVE = 0x2;
    private static final int SWP_NOACTIVATE = 0x10;
    private static final int SWP_NOOWNERZORDER = 0x200;
    private static final int FLAGS = SWP_NOSIZE | SWP_NOMOVE | SWP_NOACTIVATE | SWP_NOOWNERZORDER;
    private static final int WS_EX_TOPMOST = 0x8;

    private Win32ZOrder() {
    }

    /** @return {@code false}, wenn das Ziel nicht (mehr) existiert */
    static boolean stackAbove(long overlay, long target) {
        return stackAbove(new long[] {overlay}, target);
    }

    /**
     * Legt alle {@code overlays} direkt über {@code target}. Liegen sie dort schon zusammenhängend (in beliebiger
     * Reihenfolge), passiert nichts.
     *
     * @return {@code false}, wenn das Ziel nicht (mehr) existiert
     */
    static boolean stackAbove(long[] overlays, long target) {
        User32 u = User32.INSTANCE;
        HWND t = hwnd(target);
        if (!u.IsWindow(t)) {
            return false;
        }
        Set<HWND> own = new HashSet<>();
        for (long o : overlays) {
            HWND h = hwnd(o);
            if (u.IsWindow(h)) {
                own.add(h);
            }
        }
        if (own.isEmpty() || directlyAbove(t, own)) {
            return true;
        }
        for (HWND h : own) {
            place(h, t);
        }
        return true;
    }

    /** Ob genau {@code own} ohne Lücke direkt über dem Ziel liegt. */
    private static boolean directlyAbove(HWND target, Set<HWND> own) {
        int found = 0;
        for (HWND h = prev(target); h != null && own.contains(h); h = prev(h)) {
            found++;
        }
        return found == own.size();
    }

    private static void place(HWND own, HWND target) {
        User32 u = User32.INSTANCE;
        HWND prev = prev(target);
        if (own.equals(prev)) {
            return;
        }
        boolean targetTopmost = topmost(target);
        if (topmost(own) && !targetTopmost) {
            u.SetWindowPos(own, HWND_NOTOPMOST, 0, 0, 0, 0, FLAGS); // sonst bliebe der Rahmen über allen Fenstern
        }
        HWND after;
        if (prev == null) {
            after = targetTopmost ? HWND_TOPMOST : HWND_TOP; // Ziel liegt ganz oben
        } else if (!targetTopmost && topmost(prev)) {
            after = HWND_TOP; // Ziel ist das oberste normale Fenster: an den Anfang der normalen Ebene
        } else {
            after = prev; // zwischen das Fenster darüber und das Ziel
        }
        u.SetWindowPos(own, after, 0, 0, 0, 0, FLAGS);
    }

    private static HWND prev(HWND h) {
        return User32.INSTANCE.GetWindow(h, new DWORD(WinUser.GW_HWNDPREV));
    }

    private static HWND hwnd(long id) {
        return new HWND(new Pointer(id));
    }

    private static boolean topmost(HWND h) {
        return (User32.INSTANCE.GetWindowLong(h, WinUser.GWL_EXSTYLE) & WS_EX_TOPMOST) != 0;
    }
}
