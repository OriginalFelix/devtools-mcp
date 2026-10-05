package systems.grebe.devtools.mcp.modules.window.cursor.windows;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.POINT;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

/**
 * user32-Funktionen, die {@link com.sun.jna.platform.win32.User32} nicht (passend) kennt. Die DPI-Funktionen gibt es
 * erst ab Windows 10 1607; fehlen sie, werfen sie {@link UnsatisfiedLinkError} und es gilt System-DPI.
 */
interface User32Ext extends StdCallLibrary {

    User32Ext INSTANCE = Native.load("user32", User32Ext.class, W32APIOptions.DEFAULT_OPTIONS);

    /** {@code DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2} – als 64-Bit-Wert, {@code createConstant(int)} wäre 32 Bit. */
    Pointer PER_MONITOR_AWARE_V2 = Pointer.createConstant(-4L);

    Pointer SetThreadDpiAwarenessContext(Pointer context);

    Pointer GetWindowDpiAwarenessContext(HWND hwnd);

    int GetDpiForWindow(HWND hwnd);

    boolean PhysicalToLogicalPointForPerMonitorDPI(HWND hwnd, POINT point);

    boolean ScreenToClient(HWND hwnd, POINT point);

    HWND WindowFromPoint(POINT.ByValue point);

    HWND ChildWindowFromPointEx(HWND parent, POINT.ByValue point, int flags);

    HWND GetAncestor(HWND hwnd, int flags);

    /** Wie {@code User32.PostMessage}, aber mit Ergebnis. */
    boolean PostMessage(HWND hwnd, int msg, WPARAM wParam, LPARAM lParam);

    /** {@code WDA_EXCLUDEFROMCAPTURE} (ab Windows 10 2004): Fenster fehlt in Bildschirmaufnahmen. */
    int WDA_EXCLUDEFROMCAPTURE = 0x11;

    boolean SetWindowDisplayAffinity(HWND hwnd, int affinity);

    boolean IsChild(HWND parent, HWND child);

    HWND FindWindowEx(HWND parent, HWND childAfter, String className, String windowName);

    /** {@code GetClassLongPtrW} – Ergebnis als Zeigerwert. */
    Pointer GetClassLongPtr(HWND hwnd, int index);
}
