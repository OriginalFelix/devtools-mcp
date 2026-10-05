package systems.grebe.devtools.mcp.modules.window.cursor.windows;

import java.awt.Point;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HINSTANCE;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.LRESULT;
import com.sun.jna.platform.win32.WinDef.POINT;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.platform.win32.WinUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import systems.grebe.devtools.mcp.modules.window.cursor.CursorController;
import systems.grebe.devtools.mcp.modules.window.cursor.CursorProvider;
import systems.grebe.devtools.mcp.modules.window.cursor.MouseButton;
import systems.grebe.devtools.mcp.modules.window.cursor.VirtualCursor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Echte user32-Aufrufe: Zeiger erzeugen, bewegen, zerstören und in ein eigenes Zielfenster klicken. Beides ist klein,
 * immer oben und wird nie aktiviert – der Nutzer wird nicht gestört, sein Mauszeiger bleibt, wo er ist.
 */
@EnabledOnOs(OS.WINDOWS)
class WindowsCursorControllerTest {

    private static final int GWL_EXSTYLE = -20;
    private static final int TARGET_X = 200;
    private static final int TARGET_Y = 220;

    private final CursorController controller = CursorProvider.current().controller();
    private TargetWindow target;

    @BeforeEach
    void dpiAware() {
        // wie die Threads des Zeigers: physische Pixel, sonst rechnet Windows Fenstergrenzen um
        User32Ext.INSTANCE.SetThreadDpiAwarenessContext(User32Ext.PER_MONITOR_AWARE_V2);
    }

    @AfterEach
    void cleanUp() {
        controller.close();
        if (target != null) {
            target.close();
        }
    }

    @Test
    void providerForWindows() {
        assertThat(controller).isInstanceOf(WindowsCursorController.class);
    }

    @Test
    void createsMovesAndDestroysClickThroughWindow() throws Exception {
        VirtualCursor cursor = controller.create(120, 140);
        HWND hwnd = ((WindowsCursorController) controller).hwnd(cursor);
        assertThat(User32.INSTANCE.IsWindowVisible(hwnd)).isTrue();
        int ex = User32.INSTANCE.GetWindowLong(hwnd, GWL_EXSTYLE);
        assertThat(ex & WinUser.WS_EX_LAYERED).isNotZero();
        assertThat(ex & WinUser.WS_EX_TRANSPARENT).isNotZero();
        Point start = topLeft(hwnd);

        controller.move(cursor, 420, 260);

        assertThat(controller.position(cursor)).isEqualTo(new Point(420, 260));
        Point moved = start;
        for (int i = 0; i < 50 && moved.equals(start); i++) { // SetWindowPos läuft asynchron
            Thread.sleep(20);
            moved = topLeft(hwnd);
        }
        assertThat(new Point(moved.x - start.x, moved.y - start.y)).isEqualTo(new Point(300, 120));

        controller.destroy(cursor);

        assertThat(cursor.isOpen()).isFalse();
        assertThat(User32.INSTANCE.IsWindow(hwnd)).isFalse();
        assertThatThrownBy(() -> controller.move(cursor, 1, 1)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void postsMouseMessagesInClientCoordinates() throws Exception {
        target = new TargetWindow(TARGET_X, TARGET_Y, 240, 160);
        Win32Input input = new Win32Input();
        Point at = new Point(TARGET_X + 50, TARGET_Y + 60);

        input.button(target.hwnd(), at, MouseButton.LEFT, true, false, java.util.Set.of(MouseButton.LEFT));
        input.button(target.hwnd(), at, MouseButton.LEFT, false, false, java.util.Set.of());
        input.button(target.hwnd(), at, MouseButton.LEFT, true, true, java.util.Set.of(MouseButton.LEFT));
        input.wheel(target.hwnd(), at, -1, java.util.Set.of());

        target.awaitCount(4);
        assertThat(target.messages).containsSubsequence("WM_LBUTTONDOWN 50,60 mk=1", "WM_LBUTTONUP 50,60 mk=0",
                "WM_LBUTTONDBLCLK 50,60 mk=1", "WM_MOUSEWHEEL delta=120");
    }

    @Test
    void clicksIntoWindowBelowWithoutMovingUserPointer() throws Exception {
        target = new TargetWindow(TARGET_X, TARGET_Y, 240, 160);
        assumeOnTop(new Point(TARGET_X + 50, TARGET_Y + 60));
        VirtualCursor cursor = controller.create(0, 0);

        // Zeiger liegt über dem Ziel – sein eigenes Fenster darf den Klick nicht abfangen
        controller.clickAt(cursor, TARGET_X + 50, TARGET_Y + 60, MouseButton.LEFT, 1);
        controller.move(cursor, TARGET_X + 70, TARGET_Y + 30);
        controller.click(cursor, MouseButton.RIGHT);
        controller.scroll(cursor, 2);

        target.awaitCount(5);
        assertThat(target.messages).containsSubsequence(
                "WM_LBUTTONDOWN 50,60 mk=1",
                "WM_LBUTTONUP 50,60 mk=0",
                "WM_RBUTTONDOWN 70,30 mk=2",
                "WM_RBUTTONUP 70,30 mk=0",
                "WM_MOUSEWHEEL delta=-240");
        POINT after = new POINT();
        User32.INSTANCE.GetCursorPos(after);
        // der Nutzer darf seine Maus währenddessen bewegen – nur an die Klickpunkte darf sie nicht gesprungen sein
        assertThat(new Point(after.x, after.y)).isNotIn(new Point(TARGET_X + 50, TARGET_Y + 60),
                new Point(TARGET_X + 70, TARGET_Y + 30));
    }

    @Test
    void ownKeyboardTypesIntoTheClickedElementInTheBackground() throws Exception {
        target = new TargetWindow(TARGET_X, TARGET_Y, 240, 160);
        assumeOnTop(new Point(TARGET_X + 20, TARGET_Y + 20));
        VirtualCursor cursor = controller.create(TARGET_X + 20, TARGET_Y + 20);

        controller.click(cursor, MouseButton.LEFT); // Fokus des Zeigers = Testfenster
        controller.type(cursor, "H\ni"); // Umbruch als Zeichen \r
        controller.keyPress(cursor, java.awt.event.KeyEvent.VK_CONTROL);
        controller.keyPress(cursor, java.awt.event.KeyEvent.VK_A);
        controller.keyRelease(cursor, java.awt.event.KeyEvent.VK_A);
        controller.keyRelease(cursor, java.awt.event.KeyEvent.VK_CONTROL);

        target.awaitCount(7);
        // Strg gilt im Ziel-Thread (GetKeyState dort), nicht beim Nutzer
        assertThat(target.messages).containsSubsequence("WM_CHAR H", "WM_CHAR \r", "WM_CHAR i",
                "WM_KEYDOWN 0x41 ctrl");
        assertThat(User32.INSTANCE.GetAsyncKeyState(0x11) & 0x8000).isZero();
        assertThat(User32.INSTANCE.GetForegroundWindow()).isNotEqualTo(target.hwnd());
    }

    @Test
    void doubleClickBecomesDblClkMessage() throws Exception {
        target = new TargetWindow(TARGET_X, TARGET_Y, 240, 160);
        assumeOnTop(new Point(TARGET_X + 10, TARGET_Y + 10));
        VirtualCursor cursor = controller.create(TARGET_X + 10, TARGET_Y + 10);

        controller.click(cursor, MouseButton.LEFT, 2);

        target.awaitCount(4);
        assertThat(target.messages).containsSubsequence("WM_LBUTTONDOWN 10,10 mk=1", "WM_LBUTTONUP 10,10 mk=0",
                "WM_LBUTTONDBLCLK 10,10 mk=1", "WM_LBUTTONUP 10,10 mk=0");
    }

    /** Liegt ein anderes Fenster darüber (z.B. der Sperrbildschirm), kommt der Klick dort an – dann überspringen. */
    private void assumeOnTop(Point at) {
        HWND hit = new Win32Input().hit(at);
        assumeTrue(hit.equals(target.hwnd()), () -> "Anderes Fenster über dem Ziel: " + className(hit));
    }

    private static String className(HWND hwnd) {
        char[] buf = new char[256];
        User32.INSTANCE.GetClassName(hwnd, buf, buf.length);
        return com.sun.jna.Native.toString(buf);
    }

    private static Point topLeft(HWND hwnd) {
        RECT r = new RECT();
        User32.INSTANCE.GetWindowRect(hwnd, r);
        return new Point(r.left, r.top);
    }

    /** Tastaturzustand des aufrufenden Threads – im Testfenster also der des Ziel-Threads. */
    interface KeyState extends com.sun.jna.win32.StdCallLibrary {
        KeyState INSTANCE = com.sun.jna.Native.load("user32", KeyState.class);

        short GetKeyState(int vk);
    }

    /** Ein Popup-Fenster (immer oben, nie aktiv) mit eigener Nachrichtenschleife, das Mausnachrichten mitschreibt. */
    static final class TargetWindow {
        private static final int CS_DBLCLKS = 0x8;
        private static final int EX_STYLE = 0x8 /* TOPMOST */ | 0x80 /* TOOLWINDOW */ | 0x08000000 /* NOACTIVATE */;

        final List<String> messages = new CopyOnWriteArrayList<>();
        private final String className = "DevToolsCursorTestTarget" + System.nanoTime();
        private final WinUser.WindowProc proc = this::proc;
        private final CompletableFuture<HWND> created = new CompletableFuture<>();
        private final Thread thread;

        TargetWindow(int x, int y, int w, int h) throws Exception {
            thread = new Thread(() -> run(x, y, w, h), "cursor-test-target");
            thread.setDaemon(true);
            thread.start();
            created.get(5, TimeUnit.SECONDS);
        }

        HWND hwnd() {
            return created.join();
        }

        /** Wartet, bis so viele Nachrichten da sind – die letzten kommen asynchron über die Nachrichtenschleife. */
        void awaitCount(int count) throws InterruptedException {
            for (int i = 0; i < 100 && messages.size() < count; i++) {
                Thread.sleep(20);
            }
        }

        void close() {
            created.thenAccept(h -> User32.INSTANCE.PostMessage(h, WinUser.WM_CLOSE, new WPARAM(0), new LPARAM(0)));
            try {
                thread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void run(int x, int y, int w, int h) {
            User32Ext.INSTANCE.SetThreadDpiAwarenessContext(User32Ext.PER_MONITOR_AWARE_V2);
            User32 u = User32.INSTANCE;
            HINSTANCE instance = Kernel32.INSTANCE.GetModuleHandle(null);
            WinUser.WNDCLASSEX wc = new WinUser.WNDCLASSEX();
            wc.hInstance = instance;
            wc.lpfnWndProc = proc;
            wc.lpszClassName = className;
            wc.style = CS_DBLCLKS;
            u.RegisterClassEx(wc);
            HWND hwnd = u.CreateWindowEx(EX_STYLE, className, "Zeiger-Test", WinUser.WS_POPUP | WinUser.WS_VISIBLE,
                    x, y, w, h, null, null, instance, null);
            created.complete(hwnd);
            WinUser.MSG msg = new WinUser.MSG();
            while (u.GetMessage(msg, null, 0, 0) > 0) {
                u.TranslateMessage(msg);
                u.DispatchMessage(msg);
            }
            u.UnregisterClass(className, instance);
        }

        private LRESULT proc(HWND hwnd, int msg, WPARAM wParam, LPARAM lParam) {
            long lp = lParam.longValue();
            String at = (short) (lp & 0xFFFF) + "," + (short) ((lp >> 16) & 0xFFFF) + " mk=" + (wParam.longValue() & 0xFFFF);
            switch (msg) {
                case 0x201 -> messages.add("WM_LBUTTONDOWN " + at);
                case 0x202 -> messages.add("WM_LBUTTONUP " + at);
                case 0x203 -> messages.add("WM_LBUTTONDBLCLK " + at);
                case 0x204 -> messages.add("WM_RBUTTONDOWN " + at);
                case 0x205 -> messages.add("WM_RBUTTONUP " + at);
                case 0x20A -> messages.add("WM_MOUSEWHEEL delta=" + (short) ((wParam.longValue() >> 16) & 0xFFFF));
                case 0x100 -> messages.add("WM_KEYDOWN 0x" + Long.toHexString(wParam.longValue())
                        + ((KeyState.INSTANCE.GetKeyState(0x11) & 0x8000) != 0 ? " ctrl" : ""));
                case 0x102 -> messages.add("WM_CHAR " + (char) wParam.intValue());
                case WinUser.WM_DESTROY -> User32.INSTANCE.PostQuitMessage(0);
                default -> {
                }
            }
            return User32.INSTANCE.DefWindowProc(hwnd, msg, wParam, lParam);
        }
    }
}
