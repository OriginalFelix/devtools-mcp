package systems.grebe.devtools.mcp.modules.window.cursor.windows;

import java.awt.Color;
import java.awt.Point;
import java.util.BitSet;
import java.util.Optional;
import java.util.Set;

import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;

import systems.grebe.devtools.mcp.modules.window.cursor.AbstractCursorController;
import systems.grebe.devtools.mcp.modules.window.cursor.MouseButton;
import systems.grebe.devtools.mcp.modules.window.cursor.VirtualCursor;

/**
 * Windows: jeder Zeiger ist ein durchklickbares Layered-Fenster ({@link Win32CursorWindow}); nichts davon bewegt den
 * Mauszeiger des Nutzers. Eingaben gehen an das Fenster unter der Zeigerspitze:
 *
 * <ul>
 *   <li>klassische Fenster: Maus-Nachrichten ({@link Win32Input}) – Klicks, Doppelklicks, Ziehen, Rad, Hover.</li>
 *   <li>UWP/WinUI-Fenster (lesen keine Maus-Nachrichten): ein Linksklick bedient das Element unter der Spitze per UI
 *       Automation ({@link Win32Automation}), das Rad scrollt dort ebenso. Was UI Automation nicht abdeckt, geht als
 *       Touch mit eigener Kontakt-ID je Zeiger ({@link Win32Touch}): Ziehen, Rechtsklick (langes Drücken) sowie
 *       Klicks und Scrollen, wenn kein bedienbares Element gefunden wird. Die mittlere Taste gibt es dort nicht.</li>
 * </ul>
 *
 * <p>Wird eine Taste gehalten, bekommt das Fenster, in dem sie gedrückt wurde, alle weiteren Eingaben – wie bei
 * {@code SetCapture}.
 *
 * <p>Tastatur: jeder Zeiger hat seinen eigenen Fokus – das Element, das er zuletzt angeklickt hat. Tastatureingaben
 * gehen als Nachrichten direkt dorthin ({@link Win32Keyboard}), unabhängig vom Fokus des Nutzers.
 */
final class WindowsCursorController extends AbstractCursorController<WindowsCursorController.WinCursor> {

    /** So lange hält Windows eine Berührung, bevor sie als Rechtsklick (Kontextmenü) gilt. */
    static final int LONG_PRESS_MILLIS = 900;
    /** Wischweg je Raste des Mausrads, wenn UI Automation nicht scrollen kann (physische Pixel). */
    private static final int PAN_PIXELS_PER_NOTCH = 60;
    private static final int PAN_STEPS = 8;
    private static final int PAN_STEP_MILLIS = 12;

    static final class WinCursor {
        final Win32CursorWindow window;
        final int contact;
        /** Ziel der gehaltenen Tasten; {@code null}, wenn keine gehalten wird. */
        HWND capture;
        /** Tastatur-Fokus dieses Zeigers: das zuletzt angeklickte Element; {@code null} vor dem ersten Klick. */
        HWND focus;
        /** Gehaltene Tastaturtasten (für Strg-/Umschalt-Klicks). */
        Set<Integer> keys = Set.of();
        /** Das Ziel ist ein UWP/WinUI-Fenster. */
        boolean modern;
        /** Linke Taste gedrückt, noch nicht bewegt: beim Loslassen ein Klick per UI Automation. */
        Point pendingClick;
        /** Die gehaltene Taste ist eine Berührung. */
        boolean touching;
        long touchSince;

        WinCursor(Win32CursorWindow window, int contact) {
            this.window = window;
            this.contact = contact;
        }

        void reset() {
            capture = null;
            modern = false;
            pendingClick = null;
            touching = false;
        }
    }

    private final Win32Input input = new Win32Input();
    private final Win32Automation automation = new Win32Automation();
    private final Win32Keyboard keyboard = new Win32Keyboard();
    private final BitSet contacts = new BitSet();

    @Override
    public String name() {
        return "Windows (Layered-Fenster; Maus-Nachrichten, UI Automation/Touch bei UWP)";
    }

    @Override
    protected WinCursor open(Point at, Color color) {
        int contact = contacts.nextClearBit(0);
        if (contact >= Win32Touch.MAX_CONTACTS) {
            throw new IllegalStateException("Höchstens " + Win32Touch.MAX_CONTACTS + " Zeiger gleichzeitig.");
        }
        WinCursor c = new WinCursor(new Win32CursorWindow(at.x, at.y, color), contact);
        contacts.set(contact);
        return c;
    }

    @Override
    protected void dispose(WinCursor cursor) {
        try {
            if (cursor.touching) {
                input.touchUp(cursor.contact, new Point(0, 0));
            }
        } catch (IllegalStateException e) {
            // Kontakt war schon beendet
        } finally {
            contacts.clear(cursor.contact);
            cursor.window.close();
        }
    }

    @Override
    protected void moveTo(WinCursor cursor, Point to, Set<MouseButton> held) {
        cursor.window.moveTo(to.x, to.y);
        if (cursor.capture != null) {
            if (!cursor.modern) {
                input.move(cursor.capture, to, held);
                return;
            }
            if (cursor.pendingClick != null) { // gedrückt und bewegt: kein Klick, sondern Ziehen
                input.touchDown(cursor.contact, cursor.pendingClick);
                cursor.pendingClick = null;
                cursor.touching = true;
            }
            if (cursor.touching) {
                input.touchMove(cursor.contact, to);
            }
            return;
        }
        try {
            HWND hit = input.hit(to);
            if (!input.isModernUi(hit)) {
                input.move(hit, to, held); // Hover-Effekte, Tooltips
            }
        } catch (IllegalStateException e) {
            // kein Fenster dort oder nicht erreichbar – Bewegen allein darf nicht scheitern
        }
    }

    @Override
    protected void button(WinCursor cursor, Point at, MouseButton button, boolean down, int clickCount,
                          Set<MouseButton> held) {
        HWND target = cursor.capture != null ? cursor.capture : input.hit(at);
        if (down && cursor.capture == null) {
            cursor.capture = target;
            cursor.modern = input.isModernUi(target);
            cursor.focus = target; // wie ein echter Klick: das angeklickte Element bekommt den Tastatur-Fokus
        }
        if (cursor.modern) {
            modernButton(cursor, at, button, down, held);
            return;
        }
        if (!down && held.isEmpty()) {
            cursor.capture = null;
        }
        Set<Integer> keys = cursor.keys;
        keyboard.withModifiers(target, keys, () -> input.button(target, at, button, down, clickCount > 1, held, keys));
    }

    private void modernButton(WinCursor cursor, Point at, MouseButton button, boolean down, Set<MouseButton> held) {
        if (down) {
            if (button == MouseButton.MIDDLE || held.size() > 1) {
                if (held.size() == 1) {
                    cursor.reset();
                }
                throw new IllegalArgumentException("Dieses Fenster (UWP/WinUI) nimmt keine Maus-Nachrichten an: dort "
                        + "gibt es nur eine Taste gleichzeitig und keine mittlere Maustaste.");
            }
            if (button == MouseButton.RIGHT) {
                input.touchDown(cursor.contact, at); // Rechtsklick = langes Drücken
                cursor.touching = true;
                cursor.touchSince = System.currentTimeMillis();
            } else {
                cursor.pendingClick = new Point(at);
            }
            return;
        }
        try {
            if (cursor.touching) {
                if (button == MouseButton.RIGHT) {
                    sleep(cursor.touchSince + LONG_PRESS_MILLIS - System.currentTimeMillis());
                }
                input.touchUp(cursor.contact, at);
            } else if (cursor.pendingClick != null) {
                Optional<String> done = automation.click(at, input.pids(cursor.capture));
                if (done.isEmpty()) { // nichts Bedienbares gefunden: antippen
                    input.touchDown(cursor.contact, at);
                    input.touchUp(cursor.contact, at);
                }
            }
        } finally {
            if (held.isEmpty()) {
                cursor.reset();
            }
        }
    }

    @Override
    protected void wheel(WinCursor cursor, Point at, int notches, Set<MouseButton> held) {
        HWND target = cursor.capture != null ? cursor.capture : input.hit(at);
        if (cursor.capture != null || !input.isModernUi(target)) {
            input.wheel(target, at, notches, held);
            return;
        }
        if (automation.scroll(at, notches, input.pids(target))) {
            return;
        }
        // nichts Scrollbares per UI Automation: wischen – nach unten scrollen heißt, den Finger nach oben zu ziehen
        int distance = -notches * PAN_PIXELS_PER_NOTCH;
        input.touchDown(cursor.contact, at);
        try {
            for (int i = 1; i <= PAN_STEPS; i++) {
                sleep(PAN_STEP_MILLIS);
                input.touchMove(cursor.contact, new Point(at.x, at.y + distance * i / PAN_STEPS));
            }
        } finally {
            input.touchUp(cursor.contact, new Point(at.x, at.y + distance));
        }
    }

    @Override
    protected boolean typeText(WinCursor cursor, Point at, String text) {
        keyboard.type(keyTarget(cursor, at), text);
        return false;
    }

    @Override
    protected void key(WinCursor cursor, Point at, int keyCode, boolean down, Set<Integer> heldKeys) {
        keyboard.key(keyTarget(cursor, at), keyCode, down, heldKeys);
        cursor.keys = heldKeys;
    }

    private HWND keyTarget(WinCursor cursor, Point at) {
        if (cursor.focus != null && User32.INSTANCE.IsWindow(cursor.focus)) {
            return cursor.focus;
        }
        cursor.focus = null;
        return input.keyboardTarget(at);
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Unterbrochen", e);
        }
    }

    /** Handle des Zeigerfensters – für Tests. */
    HWND hwnd(VirtualCursor cursor) {
        return nativeCursor(cursor).window.hwnd();
    }
}
