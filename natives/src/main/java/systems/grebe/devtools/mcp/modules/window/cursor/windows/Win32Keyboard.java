package systems.grebe.devtools.mcp.modules.window.cursor.windows;

import java.awt.event.KeyEvent;
import java.util.Set;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

/**
 * Die Tastatur eines zweiten Zeigers: Tastatur-Nachrichten direkt an ein Fenster – ohne es zu aktivieren und ohne die
 * Tastatur des Nutzers. Text geht als {@code WM_CHAR} (beliebige Zeichen, unabhängig vom Tastaturlayout, auch
 * Zeilenumbruch als {@code \r} und Tab), Tasten als {@code WM_KEYDOWN}/{@code WM_KEYUP} (bzw. {@code WM_SYS*} mit Alt).
 * Umbrüche im Text bewusst als Zeichen: Word fügt bei einer Enter-Tastennachricht im Hintergrund keinen Absatz ein, bei
 * {@code WM_CHAR '\r'} schon (geprüft).
 *
 * <p>Tastenkombinationen: Anwendungen fragen gehaltene Strg/Umschalt/Alt über den Tastaturzustand ihres Threads ab.
 * Der wird für die Dauer der Kombination nur im Ziel-Thread gesetzt ({@code AttachThreadInput} +
 * {@code SetKeyboardState}) – der Tastaturzustand des Nutzers bleibt unberührt.
 *
 * <p>Grenzen: UWP/WinUI-Fenster verarbeiten Zeichen, aber reine Steuertasten (Escape, Enter …) oft nicht; Spiele und
 * Programme, die die Tastatur direkt abfragen ({@code GetAsyncKeyState}, Raw Input), reagieren nicht.
 */
final class Win32Keyboard {

    private static final int WM_KEYDOWN = 0x100;
    private static final int WM_KEYUP = 0x101;
    private static final int WM_CHAR = 0x102;
    private static final int WM_SYSKEYDOWN = 0x104;
    private static final int WM_SYSKEYUP = 0x105;
    private static final int MAPVK_VK_TO_VSC = 0;
    private static final int VK_RETURN = 0x0D;
    private static final int VK_SHIFT = 0x10;
    private static final int VK_CONTROL = 0x11;
    private static final int VK_MENU = 0x12;
    private static final int VK_LSHIFT = 0xA0;
    private static final int VK_LCONTROL = 0xA2;
    private static final int VK_LMENU = 0xA4;
    private static final int VK_F10 = 0x79;
    private static final int DOWN = 0x80;
    /** So lange bleibt der Tastaturzustand im Ziel-Thread gesetzt, damit es die Nachrichten damit verarbeitet. */
    private static final int STATE_HOLD_MILLIS = 100;
    /** Tasten mit Extended-Bit im lParam (Pfeile, Navigationsblock, rechte Strg/Alt, Ziffernblock-Teilung). */
    private static final Set<Integer> EXTENDED = Set.of(0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28, 0x2D, 0x2E,
            0x6F, 0xA3, 0xA5, 0x5B, 0x5C, 0x5D);

    interface KeyboardUser32 extends StdCallLibrary {
        KeyboardUser32 INSTANCE = Native.load("user32", KeyboardUser32.class, W32APIOptions.DEFAULT_OPTIONS);

        boolean AttachThreadInput(int from, int to, boolean attach);

        boolean GetKeyboardState(byte[] state);

        boolean SetKeyboardState(byte[] state);

        int MapVirtualKey(int code, int type);

        short VkKeyScan(char c);

        boolean PostMessage(HWND hwnd, int msg, WPARAM wParam, LPARAM lParam);
    }

    private final KeyboardUser32 user32 = KeyboardUser32.INSTANCE;

    /** Tippt Text als Zeichen (UTF-16, auch Ersatzpaare); {@code \n} und {@code \r\n} werden zu {@code \r}. */
    void type(HWND target, String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                continue; // \r\n zählt als ein Umbruch
            }
            post(target, WM_CHAR, c == '\n' ? '\r' : c, 1L);
        }
    }

    /**
     * Drückt oder lässt eine Taste los.
     *
     * @param heldKeys gehaltene Tasten nach diesem Schritt ({@link KeyEvent}{@code .VK_*})
     */
    void key(HWND target, int javaKeyCode, boolean down, Set<Integer> heldKeys) {
        int vk = virtualKey(javaKeyCode);
        if (vk == 0) {
            throw new IllegalArgumentException("Taste " + KeyEvent.getKeyText(javaKeyCode) + " lässt sich nicht senden.");
        }
        boolean shift = heldKeys.contains(KeyEvent.VK_SHIFT);
        boolean ctrl = heldKeys.contains(KeyEvent.VK_CONTROL);
        boolean alt = heldKeys.contains(KeyEvent.VK_ALT) || vk == VK_MENU; // die Alt-Taste selbst: WM_SYS*
        boolean sys = alt || vk == VK_F10;
        int msg = down ? (sys ? WM_SYSKEYDOWN : WM_KEYDOWN) : (sys ? WM_SYSKEYUP : WM_KEYUP);
        long lParam = lParam(vk, down, alt);
        if (!shift && !ctrl && !alt && !isModifier(vk)) {
            post(target, msg, vk, lParam); // ohne Kombination: der Tastaturzustand spielt keine Rolle
            return;
        }
        withKeyState(target, shift, ctrl, alt, down ? vk : 0, () -> post(target, msg, vk, lParam));
    }

    /** Führt {@code body} (z.B. einen Klick) mit den gehaltenen Strg/Umschalt/Alt im Tastaturzustand des Ziels aus. */
    void withModifiers(HWND target, Set<Integer> heldKeys, Runnable body) {
        boolean shift = heldKeys.contains(KeyEvent.VK_SHIFT);
        boolean ctrl = heldKeys.contains(KeyEvent.VK_CONTROL);
        boolean alt = heldKeys.contains(KeyEvent.VK_ALT);
        if (!shift && !ctrl && !alt) {
            body.run();
            return;
        }
        withKeyState(target, shift, ctrl, alt, 0, body);
    }

    /** Wiederholung 1, Scancode, Extended-Bit, Kontext (Alt), vorheriger Zustand und Übergang beim Loslassen. */
    private long lParam(int vk, boolean down, boolean altContext) {
        long scan = user32.MapVirtualKey(vk, MAPVK_VK_TO_VSC) & 0xFF;
        long l = 1L | (scan << 16);
        if (EXTENDED.contains(vk)) {
            l |= 1L << 24;
        }
        if (altContext) {
            l |= 1L << 29;
        }
        if (!down) {
            l |= (1L << 30) | (1L << 31);
        }
        return l;
    }

    /** Setzt Strg/Umschalt/Alt (und die gedrückte Taste) im Tastaturzustand des Ziel-Threads, solange {@code body} wirkt. */
    private void withKeyState(HWND target, boolean shift, boolean ctrl, boolean alt, int pressedVk, Runnable body) {
        int targetThread = User32.INSTANCE.GetWindowThreadProcessId(target, new IntByReference());
        int self = Kernel32.INSTANCE.GetCurrentThreadId();
        boolean attached = targetThread != 0 && targetThread != self && user32.AttachThreadInput(self, targetThread, true);
        if (!attached) {
            body.run(); // ohne Zustand – einfache Kombinationen gehen trotzdem oft
            return;
        }
        byte[] before = new byte[256];
        user32.GetKeyboardState(before);
        byte[] state = before.clone();
        set(state, VK_SHIFT, VK_LSHIFT, shift);
        set(state, VK_CONTROL, VK_LCONTROL, ctrl);
        set(state, VK_MENU, VK_LMENU, alt);
        if (pressedVk != 0) {
            state[pressedVk] = (byte) DOWN;
        }
        try {
            user32.SetKeyboardState(state);
            body.run();
            sleep(STATE_HOLD_MILLIS);
        } finally {
            user32.SetKeyboardState(before);
            user32.AttachThreadInput(self, targetThread, false);
        }
    }

    private static void set(byte[] state, int generic, int left, boolean down) {
        byte v = down ? (byte) DOWN : 0;
        state[generic] = v;
        state[left] = v;
    }

    private static boolean isModifier(int vk) {
        return vk == VK_SHIFT || vk == VK_CONTROL || vk == VK_MENU;
    }

    private void post(HWND target, int msg, long wParam, long lParam) {
        if (!user32.PostMessage(target, msg, new WPARAM(wParam), new LPARAM(lParam))) {
            throw new IllegalStateException("Tastatur-Nachricht an Fenster " + target + " nicht zustellbar (geschlossen "
                    + "oder höhere Rechte).");
        }
    }

    /** {@link KeyEvent}-Code → Windows-Virtual-Key; Satzzeichen über das aktuelle Tastaturlayout. */
    int virtualKey(int javaKeyCode) {
        return switch (javaKeyCode) {
            case KeyEvent.VK_ENTER -> VK_RETURN;
            case KeyEvent.VK_DELETE -> 0x2E;
            case KeyEvent.VK_INSERT -> 0x2D;
            case KeyEvent.VK_PRINTSCREEN -> 0x2C;
            case KeyEvent.VK_META, KeyEvent.VK_WINDOWS -> 0x5B;
            case KeyEvent.VK_CONTEXT_MENU -> 0x5D;
            case KeyEvent.VK_ALT_GRAPH -> 0xA5;
            case KeyEvent.VK_COMMA -> scan(',');
            case KeyEvent.VK_MINUS -> scan('-');
            case KeyEvent.VK_PERIOD -> scan('.');
            case KeyEvent.VK_SLASH -> scan('/');
            case KeyEvent.VK_SEMICOLON -> scan(';');
            case KeyEvent.VK_EQUALS -> scan('=');
            case KeyEvent.VK_OPEN_BRACKET -> scan('[');
            case KeyEvent.VK_CLOSE_BRACKET -> scan(']');
            case KeyEvent.VK_BACK_SLASH -> scan('\\');
            case KeyEvent.VK_QUOTE -> scan('\'');
            case KeyEvent.VK_BACK_QUOTE -> scan('`');
            case KeyEvent.VK_PLUS -> scan('+');
            case KeyEvent.VK_NUMBER_SIGN -> scan('#');
            case KeyEvent.VK_LESS -> scan('<');
            default -> {
                if (javaKeyCode >= 0x01000000) { // erweiterter Code eines Zeichens
                    yield scan((char) (javaKeyCode - 0x01000000));
                }
                yield javaKeyCode; // Buchstaben, Ziffern, F-Tasten, Pfeile, Ziffernblock: gleiche Codes
            }
        };
    }

    private int scan(char c) {
        short s = user32.VkKeyScan(c);
        return s == -1 ? 0 : s & 0xFF;
    }

    private static void sleep(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Unterbrochen", e);
        }
    }
}
