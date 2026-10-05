package systems.grebe.devtools.mcp.modules.window.platform;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinUser;

/**
 * Legt ein Anzeige-Fenster (Rahmen, Hinweis) in der Z-Reihenfolge direkt über ein fremdes Fenster – auf dieselbe
 * Ebene statt über alle Fenster: liegt ein anderes Fenster über dem Ziel, verdeckt es auch dessen Rahmen. Muss
 * nachgeführt werden, wenn sich die Reihenfolge ändert (der Rahmen folgt dem Fenster ohnehin regelmäßig).
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

    /** @return {@code false}, wenn eines der Fenster nicht (mehr) existiert */
    static boolean stackAbove(long overlay, long target) {
        User32 u = User32.INSTANCE;
        HWND own = new HWND(new Pointer(overlay));
        HWND t = new HWND(new Pointer(target));
        if (!u.IsWindow(own) || !u.IsWindow(t)) {
            return false;
        }
        HWND prev = u.GetWindow(t, new DWORD(WinUser.GW_HWNDPREV));
        if (own.equals(prev)) {
            return true; // liegt schon direkt darüber
        }
        boolean targetTopmost = topmost(t);
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
        return u.SetWindowPos(own, after, 0, 0, 0, 0, FLAGS);
    }

    private static boolean topmost(HWND h) {
        return (User32.INSTANCE.GetWindowLong(h, WinUser.GWL_EXSTYLE) & WS_EX_TOPMOST) != 0;
    }
}
