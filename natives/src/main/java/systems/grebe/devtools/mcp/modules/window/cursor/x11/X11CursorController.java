package systems.grebe.devtools.mcp.modules.window.cursor.x11;

import java.awt.Color;
import java.awt.Point;
import java.util.Set;

import systems.grebe.devtools.mcp.modules.window.cursor.AbstractCursorController;
import systems.grebe.devtools.mcp.modules.window.cursor.MouseButton;

/**
 * Linux/X11: jeder Zeiger ist ein eigener MPX-Master ({@link X11MasterPointer}). Der X-Server stellt Ereignisse selbst
 * dem Fenster unter diesem Zeiger zu, hält bei gedrückter Taste das Ziel fest und erkennt Doppelklicks über die
 * Zeitabstände – deshalb braucht es hier weder Fenstersuche noch Klickzähler. Die Tastatur ist die Master-Tastatur
 * desselben Masters; gehaltene Strg/Umschalt sind echte gedrückte Tasten dieser Tastatur.
 */
final class X11CursorController extends AbstractCursorController<X11MasterPointer> {

    private static final int WHEEL_UP = 4;
    private static final int WHEEL_DOWN = 5;

    @Override
    public String name() {
        return "X11 (XInput2-Multi-Pointer, XTest)";
    }

    @Override
    protected X11MasterPointer open(Point at, Color color) {
        // den Master-Zeiger zeichnet der X-Server – die Farbe bleibt hier ohne Wirkung
        return new X11MasterPointer(at.x, at.y);
    }

    @Override
    protected void dispose(X11MasterPointer cursor) {
        cursor.close();
    }

    @Override
    protected void moveTo(X11MasterPointer cursor, Point to, Set<MouseButton> held) {
        cursor.moveTo(to.x, to.y);
    }

    @Override
    protected void button(X11MasterPointer cursor, Point at, MouseButton button, boolean down, int clickCount,
                          Set<MouseButton> held) {
        cursor.button(switch (button) {
            case LEFT -> 1;
            case MIDDLE -> 2;
            case RIGHT -> 3;
        }, down);
    }

    @Override
    protected void typeText(X11MasterPointer cursor, Point at, String text) {
        cursor.type(text);
    }

    @Override
    protected void key(X11MasterPointer cursor, Point at, int keyCode, boolean down, Set<Integer> heldKeys) {
        int keysym = X11Keysyms.ofKey(keyCode);
        if (keysym < 0) {
            throw new IllegalArgumentException("Taste " + java.awt.event.KeyEvent.getKeyText(keyCode)
                    + " lässt sich unter X11 nicht senden.");
        }
        cursor.key(keysym, down);
    }

    @Override
    protected void wheel(X11MasterPointer cursor, Point at, int notches, Set<MouseButton> held) {
        int button = notches > 0 ? WHEEL_DOWN : WHEEL_UP;
        for (int i = 0; i < Math.abs(notches); i++) {
            cursor.button(button, true);
            cursor.button(button, false);
        }
    }
}
