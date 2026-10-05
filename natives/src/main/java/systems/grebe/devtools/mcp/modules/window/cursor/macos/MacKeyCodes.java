package systems.grebe.devtools.mcp.modules.window.cursor.macos;

import java.awt.event.KeyEvent;
import java.util.Map;

import static java.util.Map.entry;

/** {@link KeyEvent}-Codes → macOS-Tastencodes ({@code kVK_*} aus {@code Events.h}, Positionen der US-Tastatur). */
final class MacKeyCodes {

    private static final Map<Integer, Integer> CODES = Map.ofEntries(
            entry(KeyEvent.VK_A, 0x00), entry(KeyEvent.VK_S, 0x01), entry(KeyEvent.VK_D, 0x02),
            entry(KeyEvent.VK_F, 0x03), entry(KeyEvent.VK_H, 0x04), entry(KeyEvent.VK_G, 0x05),
            entry(KeyEvent.VK_Z, 0x06), entry(KeyEvent.VK_X, 0x07), entry(KeyEvent.VK_C, 0x08),
            entry(KeyEvent.VK_V, 0x09), entry(KeyEvent.VK_B, 0x0B), entry(KeyEvent.VK_Q, 0x0C),
            entry(KeyEvent.VK_W, 0x0D), entry(KeyEvent.VK_E, 0x0E), entry(KeyEvent.VK_R, 0x0F),
            entry(KeyEvent.VK_Y, 0x10), entry(KeyEvent.VK_T, 0x11), entry(KeyEvent.VK_1, 0x12),
            entry(KeyEvent.VK_2, 0x13), entry(KeyEvent.VK_3, 0x14), entry(KeyEvent.VK_4, 0x15),
            entry(KeyEvent.VK_6, 0x16), entry(KeyEvent.VK_5, 0x17), entry(KeyEvent.VK_EQUALS, 0x18),
            entry(KeyEvent.VK_9, 0x19), entry(KeyEvent.VK_7, 0x1A), entry(KeyEvent.VK_MINUS, 0x1B),
            entry(KeyEvent.VK_8, 0x1C), entry(KeyEvent.VK_0, 0x1D), entry(KeyEvent.VK_CLOSE_BRACKET, 0x1E),
            entry(KeyEvent.VK_O, 0x1F), entry(KeyEvent.VK_U, 0x20), entry(KeyEvent.VK_OPEN_BRACKET, 0x21),
            entry(KeyEvent.VK_I, 0x22), entry(KeyEvent.VK_P, 0x23), entry(KeyEvent.VK_ENTER, 0x24),
            entry(KeyEvent.VK_L, 0x25), entry(KeyEvent.VK_J, 0x26), entry(KeyEvent.VK_QUOTE, 0x27),
            entry(KeyEvent.VK_K, 0x28), entry(KeyEvent.VK_SEMICOLON, 0x29), entry(KeyEvent.VK_BACK_SLASH, 0x2A),
            entry(KeyEvent.VK_COMMA, 0x2B), entry(KeyEvent.VK_SLASH, 0x2C), entry(KeyEvent.VK_N, 0x2D),
            entry(KeyEvent.VK_M, 0x2E), entry(KeyEvent.VK_PERIOD, 0x2F), entry(KeyEvent.VK_TAB, 0x30),
            entry(KeyEvent.VK_SPACE, 0x31), entry(KeyEvent.VK_BACK_QUOTE, 0x32), entry(KeyEvent.VK_BACK_SPACE, 0x33),
            entry(KeyEvent.VK_ESCAPE, 0x35), entry(KeyEvent.VK_META, 0x37), entry(KeyEvent.VK_SHIFT, 0x38),
            entry(KeyEvent.VK_CAPS_LOCK, 0x39), entry(KeyEvent.VK_ALT, 0x3A), entry(KeyEvent.VK_CONTROL, 0x3B),
            entry(KeyEvent.VK_F5, 0x60), entry(KeyEvent.VK_F6, 0x61), entry(KeyEvent.VK_F7, 0x62),
            entry(KeyEvent.VK_F3, 0x63), entry(KeyEvent.VK_F8, 0x64), entry(KeyEvent.VK_F9, 0x65),
            entry(KeyEvent.VK_F11, 0x67), entry(KeyEvent.VK_F10, 0x6D), entry(KeyEvent.VK_F12, 0x6F),
            entry(KeyEvent.VK_HOME, 0x73), entry(KeyEvent.VK_PAGE_UP, 0x74), entry(KeyEvent.VK_DELETE, 0x75),
            entry(KeyEvent.VK_F4, 0x76), entry(KeyEvent.VK_END, 0x77), entry(KeyEvent.VK_F2, 0x78),
            entry(KeyEvent.VK_PAGE_DOWN, 0x79), entry(KeyEvent.VK_F1, 0x7A), entry(KeyEvent.VK_LEFT, 0x7B),
            entry(KeyEvent.VK_RIGHT, 0x7C), entry(KeyEvent.VK_DOWN, 0x7D), entry(KeyEvent.VK_UP, 0x7E));

    private MacKeyCodes() {
    }

    /** Tastencode oder -1, wenn es keinen gibt. */
    static int of(int javaKeyCode) {
        return CODES.getOrDefault(javaKeyCode, -1);
    }
}
