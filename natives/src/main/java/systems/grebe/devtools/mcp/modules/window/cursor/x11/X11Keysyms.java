package systems.grebe.devtools.mcp.modules.window.cursor.x11;

import java.awt.event.KeyEvent;
import java.util.Map;

import static java.util.Map.entry;

/** {@link KeyEvent}-Codes und Zeichen → X11-Keysyms ({@code keysymdef.h}). */
final class X11Keysyms {

    static final int RETURN = 0xff0d;
    static final int TAB = 0xff09;
    static final int SHIFT_L = 0xffe1;

    private static final Map<Integer, Integer> SPECIAL = Map.ofEntries(
            entry(KeyEvent.VK_ENTER, RETURN), entry(KeyEvent.VK_BACK_SPACE, 0xff08), entry(KeyEvent.VK_TAB, TAB),
            entry(KeyEvent.VK_ESCAPE, 0xff1b), entry(KeyEvent.VK_DELETE, 0xffff), entry(KeyEvent.VK_INSERT, 0xff63),
            entry(KeyEvent.VK_HOME, 0xff50), entry(KeyEvent.VK_LEFT, 0xff51), entry(KeyEvent.VK_UP, 0xff52),
            entry(KeyEvent.VK_RIGHT, 0xff53), entry(KeyEvent.VK_DOWN, 0xff54), entry(KeyEvent.VK_PAGE_UP, 0xff55),
            entry(KeyEvent.VK_PAGE_DOWN, 0xff56), entry(KeyEvent.VK_END, 0xff57), entry(KeyEvent.VK_SHIFT, SHIFT_L),
            entry(KeyEvent.VK_CONTROL, 0xffe3), entry(KeyEvent.VK_ALT, 0xffe9), entry(KeyEvent.VK_META, 0xffeb),
            entry(KeyEvent.VK_WINDOWS, 0xffeb), entry(KeyEvent.VK_CAPS_LOCK, 0xffe5), entry(KeyEvent.VK_SPACE, 0x20),
            entry(KeyEvent.VK_CONTEXT_MENU, 0xff67), entry(KeyEvent.VK_ALT_GRAPH, 0xfe03),
            entry(KeyEvent.VK_MULTIPLY, 0xffaa), entry(KeyEvent.VK_ADD, 0xffab), entry(KeyEvent.VK_SUBTRACT, 0xffad),
            entry(KeyEvent.VK_DECIMAL, 0xffae), entry(KeyEvent.VK_DIVIDE, 0xffaf),
            entry(KeyEvent.VK_COMMA, (int) ','), entry(KeyEvent.VK_MINUS, (int) '-'), entry(KeyEvent.VK_PERIOD, (int) '.'),
            entry(KeyEvent.VK_SLASH, (int) '/'), entry(KeyEvent.VK_SEMICOLON, (int) ';'),
            entry(KeyEvent.VK_EQUALS, (int) '='), entry(KeyEvent.VK_OPEN_BRACKET, (int) '['),
            entry(KeyEvent.VK_CLOSE_BRACKET, (int) ']'), entry(KeyEvent.VK_BACK_SLASH, (int) '\\'),
            entry(KeyEvent.VK_QUOTE, (int) '\''), entry(KeyEvent.VK_BACK_QUOTE, (int) '`'),
            entry(KeyEvent.VK_PLUS, (int) '+'), entry(KeyEvent.VK_NUMBER_SIGN, (int) '#'),
            entry(KeyEvent.VK_LESS, (int) '<'));

    private X11Keysyms() {
    }

    /** Keysym zu einer Taste; -1, wenn es keins gibt. */
    static int ofKey(int javaKeyCode) {
        Integer special = SPECIAL.get(javaKeyCode);
        if (special != null) {
            return special;
        }
        if (javaKeyCode >= KeyEvent.VK_A && javaKeyCode <= KeyEvent.VK_Z) {
            return Character.toLowerCase(javaKeyCode);
        }
        if (javaKeyCode >= KeyEvent.VK_0 && javaKeyCode <= KeyEvent.VK_9) {
            return javaKeyCode;
        }
        if (javaKeyCode >= KeyEvent.VK_NUMPAD0 && javaKeyCode <= KeyEvent.VK_NUMPAD9) {
            return 0xffb0 + javaKeyCode - KeyEvent.VK_NUMPAD0;
        }
        if (javaKeyCode >= KeyEvent.VK_F1 && javaKeyCode <= KeyEvent.VK_F12) {
            return 0xffbe + javaKeyCode - KeyEvent.VK_F1;
        }
        if (javaKeyCode >= 0x01000000) { // erweiterter Code eines Zeichens
            return ofChar(javaKeyCode - 0x01000000);
        }
        return -1;
    }

    /** Keysym zu einem Zeichen: Latin-1 direkt, sonst Unicode-Keysym ({@code 0x01000000 | Codepunkt}). */
    static int ofChar(int codePoint) {
        return switch (codePoint) {
            case '\n' -> RETURN;
            case '\t' -> TAB;
            default -> codePoint < 0x100 ? codePoint : 0x01000000 | codePoint;
        };
    }
}
