package systems.grebe.devtools.mcp.modules.window.cursor.windows;

import java.awt.Point;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.GDI32;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HBITMAP;
import com.sun.jna.platform.win32.WinDef.HDC;
import com.sun.jna.platform.win32.WinDef.HINSTANCE;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.LRESULT;
import com.sun.jna.platform.win32.WinDef.POINT;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.platform.win32.WinGDI;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.PointerByReference;
import systems.grebe.devtools.mcp.modules.window.cursor.CursorImage;

/**
 * Fenster eines Windows-Zeigers: der Zeiger ist ein kleines Layered-Fenster ({@code WS_EX_LAYERED | WS_EX_TRANSPARENT}), immer oben,
 * ohne Taskleisteneintrag und ohne je den Fokus zu nehmen; Klicks gehen hindurch. Das Bild kommt per
 * {@code UpdateLayeredWindow} mit Alphakanal.
 *
 * <p>Das Fenster gehört einem eigenen Thread mit Nachrichtenschleife; der Thread ist Per-Monitor-DPI-aware (v2), damit
 * Koordinaten physische Pixel sind. Bewegt wird asynchron per {@code SetWindowPos}, zerstört per {@code WM_CLOSE}.
 */
final class Win32CursorWindow {

    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static final HWND HWND_TOPMOST = new HWND(Pointer.createConstant(-1L));
    private static final int EX_STYLE = WinUser.WS_EX_LAYERED | WinUser.WS_EX_TRANSPARENT | 0x8 /* TOPMOST */
            | 0x80 /* TOOLWINDOW */ | 0x08000000 /* NOACTIVATE */;
    private static final int MOVE_FLAGS = WinUser.SWP_NOSIZE | WinUser.SWP_NOACTIVATE | WinUser.SWP_ASYNCWINDOWPOS
            | WinUser.SWP_NOOWNERZORDER;
    private static final long TIMEOUT_SECONDS = 5;

    private final User32 user32 = User32.INSTANCE;
    private final String className = "DevToolsVirtualCursor" + ProcessHandle.current().pid() + "_"
            + COUNTER.incrementAndGet();
    private final Thread thread;
    private final CompletableFuture<HWND> created = new CompletableFuture<>();
    /** Fest referenziert: JNA gibt Callbacks frei, sobald der Java-Gegenpart eingesammelt wird. */
    private final WinUser.WindowProc windowProc = this::windowProc;
    private volatile HWND hwnd;
    private volatile Point hotspot = new Point();
    private Point position;

    Win32CursorWindow(int x, int y) {
        position = new Point(x, y);
        thread = new Thread(this::run, "virtual-cursor-win32");
        thread.setDaemon(true);
        thread.start();
        try {
            hwnd = created.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Zweiter Zeiger nicht erzeugt: " + e.getCause().getMessage(), e.getCause());
        } catch (TimeoutException e) {
            // kommt das Fenster doch noch, gleich wieder schließen
            created.thenAccept(h -> user32.PostMessage(h, WinUser.WM_CLOSE, new WPARAM(0), new LPARAM(0)));
            throw new IllegalStateException("Zweiter Zeiger nicht erzeugt: Zeitüberschreitung", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Unterbrochen", e);
        }
    }

    synchronized void moveTo(int x, int y) {
        HWND h = requireOpen();
        position = new Point(x, y);
        Point hs = hotspot;
        if (!user32.SetWindowPos(h, HWND_TOPMOST, x - hs.x, y - hs.y, 0, 0, MOVE_FLAGS)) {
            throw new IllegalStateException("SetWindowPos: Fehler " + Native.getLastError());
        }
    }

    boolean isOpen() {
        HWND h = hwnd;
        return h != null && thread.isAlive();
    }

    void close() {
        HWND h;
        synchronized (this) {
            h = hwnd;
            hwnd = null;
        }
        if (h == null) {
            return;
        }
        user32.PostMessage(h, WinUser.WM_CLOSE, new WPARAM(0), new LPARAM(0));
        try {
            thread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Natives Handle; {@code null} nach {@link #close()}. */
    HWND hwnd() {
        return hwnd;
    }

    private HWND requireOpen() {
        HWND h = hwnd;
        if (h == null) {
            throw new IllegalStateException("Der zweite Zeiger ist bereits zerstört.");
        }
        return h;
    }

    private void run() {
        HINSTANCE instance = Kernel32.INSTANCE.GetModuleHandle(null);
        HWND h = null;
        boolean registered = false;
        try {
            setDpiAware();
            WinUser.WNDCLASSEX wc = new WinUser.WNDCLASSEX();
            wc.hInstance = instance;
            wc.lpfnWndProc = windowProc;
            wc.lpszClassName = className;
            if (user32.RegisterClassEx(wc) == null) {
                throw new IllegalStateException("RegisterClassEx: Fehler " + Native.getLastError());
            }
            registered = true;
            Point start = position;
            h = user32.CreateWindowEx(EX_STYLE, className, "KI-Zeiger", WinUser.WS_POPUP, start.x, start.y, 1, 1,
                    null, null, instance, null);
            if (h == null) {
                throw new IllegalStateException("CreateWindowEx: Fehler " + Native.getLastError());
            }
            // nicht in Screenshots: die KI soll das Fenster sehen, nicht ihren eigenen Zeiger (ältere Systeme: egal)
            User32Ext.INSTANCE.SetWindowDisplayAffinity(h, User32Ext.WDA_EXCLUDEFROMCAPTURE);
            paint(h, start);
            user32.ShowWindow(h, WinUser.SW_SHOWNOACTIVATE);
            created.complete(h);
        } catch (RuntimeException | LinkageError e) {
            if (h != null) {
                user32.DestroyWindow(h);
            }
            if (registered) {
                user32.UnregisterClass(className, instance);
            }
            created.completeExceptionally(e);
            return;
        }
        try {
            WinUser.MSG msg = new WinUser.MSG();
            while (user32.GetMessage(msg, null, 0, 0) > 0) {
                user32.TranslateMessage(msg);
                user32.DispatchMessage(msg);
            }
        } finally {
            if (user32.IsWindow(h)) {
                user32.DestroyWindow(h);
            }
            user32.UnregisterClass(className, instance);
            hwnd = null;
        }
    }

    private LRESULT windowProc(HWND h, int msg, WPARAM wParam, LPARAM lParam) {
        if (msg == WinUser.WM_DESTROY) {
            user32.PostQuitMessage(0);
            return new LRESULT(0);
        }
        return user32.DefWindowProc(h, msg, wParam, lParam);
    }

    private static void setDpiAware() {
        try {
            User32Ext.INSTANCE.SetThreadDpiAwarenessContext(User32Ext.PER_MONITOR_AWARE_V2);
        } catch (UnsatisfiedLinkError e) {
            // ältere Windows-Versionen: Koordinaten und Bild in System-DPI
        }
    }

    private static double scale(HWND h) {
        try {
            int dpi = User32Ext.INSTANCE.GetDpiForWindow(h);
            return dpi > 0 ? dpi / 96.0 : 1;
        } catch (UnsatisfiedLinkError e) {
            return 1;
        }
    }

    /** Überträgt das Zeigerbild (vormultipliziertes BGRA, von oben nach unten) in das Layered-Fenster. */
    private void paint(HWND h, Point tip) {
        CursorImage img = CursorImage.render(scale(h));
        hotspot = img.hotspot();
        int w = img.width();
        int hgt = img.height();
        GDI32 gdi = GDI32.INSTANCE;
        HDC screen = user32.GetDC(null);
        HDC mem = gdi.CreateCompatibleDC(screen);
        HBITMAP bitmap = null;
        HANDLE previous = null;
        try {
            WinGDI.BITMAPINFO bmi = new WinGDI.BITMAPINFO();
            bmi.bmiHeader.biWidth = w;
            bmi.bmiHeader.biHeight = -hgt; // negativ = Zeilen von oben nach unten
            bmi.bmiHeader.biPlanes = 1;
            bmi.bmiHeader.biBitCount = 32;
            bmi.bmiHeader.biCompression = WinGDI.BI_RGB;
            PointerByReference bits = new PointerByReference();
            bitmap = gdi.CreateDIBSection(screen, bmi, WinGDI.DIB_RGB_COLORS, bits, null, 0);
            if (bitmap == null || bits.getValue() == null) {
                throw new IllegalStateException("CreateDIBSection: Fehler " + Native.getLastError());
            }
            bits.getValue().write(0, img.premultipliedArgb(), 0, w * hgt);
            previous = gdi.SelectObject(mem, bitmap);
            WinUser.BLENDFUNCTION blend = new WinUser.BLENDFUNCTION();
            blend.BlendOp = WinUser.AC_SRC_OVER;
            blend.SourceConstantAlpha = (byte) 255;
            blend.AlphaFormat = WinUser.AC_SRC_ALPHA;
            POINT dst = new POINT(tip.x - hotspot.x, tip.y - hotspot.y);
            if (!user32.UpdateLayeredWindow(h, screen, dst, new WinUser.SIZE(w, hgt), mem, new POINT(0, 0), 0, blend,
                    WinUser.ULW_ALPHA)) {
                throw new IllegalStateException("UpdateLayeredWindow: Fehler " + Native.getLastError());
            }
        } finally {
            if (previous != null) {
                gdi.SelectObject(mem, previous);
            }
            if (bitmap != null) {
                gdi.DeleteObject(bitmap);
            }
            gdi.DeleteDC(mem);
            user32.ReleaseDC(null, screen);
        }
    }
}
