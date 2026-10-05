package systems.grebe.devtools.mcp.modules.window.platform;

import java.util.ArrayList;
import java.util.List;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledOnOs(OS.WINDOWS)
class Win32ZOrderTest {

    private static final int WS_POPUP = 0x80000000;
    private static final int WS_EX_TOOLWINDOW = 0x80;
    private static final int WS_EX_NOACTIVATE = 0x08000000;
    private static final int SWP_NOSIZE_NOMOVE_NOACTIVATE = 0x1 | 0x2 | 0x10;

    private final List<HWND> created = new ArrayList<>();

    /** Unsichtbares Fenster auf diesem Thread – ein neues Fenster liegt oben in der Z-Reihenfolge. */
    private HWND window() {
        HWND h = User32.INSTANCE.CreateWindowEx(WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE, "STATIC", "z-order-test",
                WS_POPUP, 0, 0, 50, 50, null, null, null, null);
        assertThat(h).isNotNull();
        created.add(h);
        return h;
    }

    private static long id(HWND h) {
        return Pointer.nativeValue(h.getPointer());
    }

    /** Das Fenster direkt darüber. */
    private static HWND prev(HWND h) {
        return User32.INSTANCE.GetWindow(h, new DWORD(WinUser.GW_HWNDPREV));
    }

    private static boolean isAbove(HWND upper, HWND lower) {
        for (HWND h = prev(lower); h != null; h = prev(h)) {
            if (h.equals(upper)) {
                return true;
            }
        }
        return false;
    }

    @AfterEach
    void destroy() {
        created.forEach(User32.INSTANCE::DestroyWindow);
    }

    @Test
    void placesTheOverlayDirectlyAboveItsWindowAndBelowOthers() {
        HWND target = window();
        HWND overlay = window();
        HWND other = window(); // liegt über beiden

        assertThat(Win32ZOrder.stackAbove(id(overlay), id(target))).isTrue();

        assertThat(prev(target)).isEqualTo(overlay);
        assertThat(isAbove(other, overlay)).isTrue();
    }

    @Test
    void followsWhenAnotherWindowMovesOverTheTarget() {
        HWND target = window();
        HWND overlay = window();
        Win32ZOrder.stackAbove(id(overlay), id(target));
        HWND later = window();
        // „later“ schiebt sich zwischen Rahmen und Ziel
        User32.INSTANCE.SetWindowPos(later, overlay, 0, 0, 0, 0, SWP_NOSIZE_NOMOVE_NOACTIVATE);
        assertThat(prev(target)).isEqualTo(later);

        Win32ZOrder.stackAbove(id(overlay), id(target));

        assertThat(prev(target)).isEqualTo(overlay);
        assertThat(isAbove(later, overlay)).isTrue();
    }
}
