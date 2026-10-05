package systems.grebe.devtools.mcp.modules.window.cursor.macos;

import java.awt.Color;
import java.awt.Point;
import java.util.Set;

import systems.grebe.devtools.mcp.modules.window.cursor.AbstractCursorController;
import systems.grebe.devtools.mcp.modules.window.cursor.MouseButton;

/**
 * macOS: jeder Zeiger ist ein mausdurchlässiges {@code NSWindow} ({@link MacCursorWindow}); Klicks, Ziehen und
 * Mausrad gehen als {@code CGEvent} an den Prozess des Fensters darunter ({@link MacInput}). Wird eine Taste gehalten,
 * bekommt der Prozess, in dem sie gedrückt wurde, alle weiteren Ereignisse.
 */
final class MacCursorController extends AbstractCursorController<MacCursorController.MacCursor> {

    static final class MacCursor {
        final MacCursorWindow window;
        final long windowNumber;
        /** Ziel der gehaltenen Tasten; 0, wenn keine gehalten wird. */
        int capturePid;
        /** Tastatur-Ziel dieses Zeigers: der Prozess, in den er zuletzt geklickt hat; 0 vor dem ersten Klick. */
        int focusPid;

        MacCursor(MacCursorWindow window) {
            this.window = window;
            this.windowNumber = window.windowNumber();
        }
    }

    private final MacInput input = new MacInput();

    @Override
    public String name() {
        return "macOS (NSWindow, CGEventPostToPid)";
    }

    @Override
    protected MacCursor open(Point at, Color color) {
        return new MacCursor(new MacCursorWindow(at.x, at.y, color));
    }

    @Override
    protected void dispose(MacCursor cursor) {
        cursor.window.close();
    }

    @Override
    protected void moveTo(MacCursor cursor, Point to, Set<MouseButton> held) {
        cursor.window.moveTo(to.x, to.y);
        if (cursor.capturePid != 0) {
            input.move(cursor.capturePid, to, held);
            return;
        }
        try {
            input.move(input.pidAt(to, cursor.windowNumber), to, held); // Hover-Effekte
        } catch (IllegalStateException e) {
            // kein Fenster dort – Bewegen allein darf nicht scheitern
        }
    }

    @Override
    protected void button(MacCursor cursor, Point at, MouseButton button, boolean down, int clickCount,
                          Set<MouseButton> held) {
        input.requireTrusted();
        int pid = cursor.capturePid != 0 ? cursor.capturePid : input.pidAt(at, cursor.windowNumber);
        if (down) {
            cursor.capturePid = pid;
            cursor.focusPid = pid;
        } else if (held.isEmpty()) {
            cursor.capturePid = 0;
        }
        input.button(pid, at, button, down, clickCount);
    }

    @Override
    protected void typeText(MacCursor cursor, Point at, String text) {
        input.requireTrusted();
        input.type(keyPid(cursor, at), text);
    }

    @Override
    protected void key(MacCursor cursor, Point at, int keyCode, boolean down, Set<Integer> heldKeys) {
        input.requireTrusted();
        input.key(keyPid(cursor, at), keyCode, down, heldKeys);
    }

    /** Das Element mit Tastatur-Fokus bestimmt die Anwendung selbst – hier zählt nur der Prozess. */
    private int keyPid(MacCursor cursor, Point at) {
        return cursor.focusPid != 0 ? cursor.focusPid : input.pidAt(at, cursor.windowNumber);
    }

    @Override
    protected void wheel(MacCursor cursor, Point at, int notches, Set<MouseButton> held) {
        input.requireTrusted();
        input.wheel(cursor.capturePid != 0 ? cursor.capturePid : input.pidAt(at, cursor.windowNumber), at, notches);
    }
}
