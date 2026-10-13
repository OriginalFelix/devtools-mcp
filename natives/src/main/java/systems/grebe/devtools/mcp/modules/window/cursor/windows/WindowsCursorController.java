package systems.grebe.devtools.mcp.modules.window.cursor.windows;

import java.awt.Color;
import java.awt.Point;
import java.util.BitSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;

import systems.grebe.devtools.mcp.modules.window.cursor.AbstractCursorController;
import systems.grebe.devtools.mcp.modules.window.cursor.MouseButton;
import systems.grebe.devtools.mcp.modules.window.cursor.VirtualCursor;
import systems.grebe.devtools.mcp.modules.window.platform.WindowSystem;

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
 *
 * <p>Java/AWT (auch Swing) verwirft Tasten, solange es keines seiner Fenster für aktiv hält – und das erfährt es nur
 * über {@code WM_ACTIVATE}. Vor Klicks und Tastatur bekommt ein solches Fenster deshalb {@code WM_ACTIVATE}, ohne
 * wirklich aktiviert zu werden (Vordergrund, Tastatur und Titelleiste des Nutzers bleiben); mit dem Zeiger endet das.
 * Holt sich Java daraufhin selbst den Vordergrund (das lässt Windows zu, wenn der Nutzer länger nichts eingegeben hat),
 * gibt ihn {@link ForegroundKeeper} sofort zurück. Gehaltene Maustasten liest AWT aus dem Tastaturzustand seines Threads statt aus der Nachricht – ohne sie hält Swing
 * einen Klick für Ziehen –, sie werden dort für die Dauer der Nachricht gesetzt wie Strg/Umschalt.
 */
final class WindowsCursorController extends AbstractCursorController<WindowsCursorController.WinCursor> {

    private static final int VK_LBUTTON = 0x01;
    private static final int VK_RBUTTON = 0x02;
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
        /** Java/AWT-Fenster, das sich für diesen Zeiger aktiv glaubt; {@code null}, wenn keins. */
        HWND javaActive;

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
    private final Function<Point, HWND> hit;
    private final ForegroundKeeper keeper = ForegroundKeeper.start(
            () -> id(User32.INSTANCE.GetForegroundWindow()),
            id -> Win32Input.pid(new HWND(new Pointer(id))),
            id -> WindowSystem.current().returnForeground(id),
            () -> (User32.INSTANCE.GetAsyncKeyState(VK_LBUTTON) & 0x8000) != 0
                    || (User32.INSTANCE.GetAsyncKeyState(VK_RBUTTON) & 0x8000) != 0);

    WindowsCursorController() {
        this(null);
    }

    /** @param hit Fenster unter einem Punkt; {@code null} = wirklich suchen ({@link Win32Input#hit}) – für Tests */
    WindowsCursorController(Function<Point, HWND> hit) {
        this.hit = hit != null ? hit : input::hit;
    }

    private static long id(HWND hwnd) {
        return hwnd == null ? 0 : Pointer.nativeValue(hwnd.getPointer());
    }

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
            deactivateJava(cursor);
            contacts.clear(cursor.contact);
            cursor.window.close();
        }
    }

    /**
     * Lässt ein Java/AWT-Fenster glauben, es sei aktiv – sonst nimmt es keine Tasten an und ein angeklicktes Feld bekommt
     * keinen Fokus. Wirklich aktive Fenster bleiben unberührt.
     *
     * @return {@code false}, wenn der Nutzer gerade in einem anderen Fenster desselben Programms arbeitet: Java kennt
     *         nur ein aktives Fenster je Programm, seine Tastatureingaben landeten dann im Fenster der KI
     */
    private boolean activateJava(WinCursor cursor, HWND target) {
        if (!input.isAwt(target)) {
            return true;
        }
        HWND root = input.root(target);
        HWND foreground = User32.INSTANCE.GetForegroundWindow();
        if (foreground != null && input.root(foreground).equals(root)) {
            return true; // wirklich aktiv
        }
        if (foreground != null && Win32Input.sameProcess(foreground, root)) {
            return false;
        }
        // AWT holt sich bei Fokuswechseln selbst nach vorn, wenn Windows es zulässt – dann sofort zurück
        keeper.watch(id(root));
        if (keeper.takeRestored()) {
            cursor.javaActive = null; // war wirklich aktiv und ist es nicht mehr: hält sich nicht mehr für aktiv
        }
        if (!root.equals(cursor.javaActive)) {
            deactivateJava(cursor);
            input.activation(root, true);
            cursor.javaActive = root;
        }
        return true;
    }

    /** Nimmt {@link #activateJava} zurück – außer der Nutzer arbeitet inzwischen selbst in diesem Programm. */
    private void deactivateJava(WinCursor cursor) {
        HWND root = cursor.javaActive;
        cursor.javaActive = null;
        if (root == null || !User32.INSTANCE.IsWindow(root)) {
            return;
        }
        HWND foreground = User32.INSTANCE.GetForegroundWindow();
        if (foreground != null && Win32Input.sameProcess(foreground, root)) {
            return; // sein Fenster ist wirklich aktiv – ein WM_ACTIVATE nähme ihm den Fokus
        }
        try {
            input.activation(root, false);
        } catch (IllegalStateException e) {
            // Fenster inzwischen geschlossen
        }
    }

    /** Java/AWT-Fenster vor Tastatureingaben aktiv melden; scheitert mit Grund, wenn das den Nutzer stören würde. */
    private void requireJavaActive(WinCursor cursor, HWND target) {
        if (!activateJava(cursor, target)) {
            throw new IllegalStateException("Der Nutzer arbeitet gerade in einem anderen Fenster dieses Java-Programms. "
                    + "Java nimmt Tasten nur im Fenster an, das es für aktiv hält – sie landeten dann bei ihm. Warten, "
                    + "bis er zu einem anderen Programm wechselt, oder ihn bitten.");
        }
    }

    @Override
    protected void moveTo(WinCursor cursor, Point to, Set<MouseButton> held) {
        cursor.window.moveTo(to.x, to.y);
        if (cursor.capture != null) {
            if (!cursor.modern) {
                HWND target = cursor.capture;
                if (input.isAwt(target)) { // Ziehen: AWT liest die gehaltenen Tasten aus dem Tastaturzustand
                    keyboard.withModifiersAndButtons(target, cursor.keys, held, () -> input.move(target, to, held));
                } else {
                    input.move(target, to, held);
                }
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
            HWND under = hit.apply(to);
            if (!input.isModernUi(under)) {
                input.move(under, to, held); // Hover-Effekte, Tooltips
            }
        } catch (IllegalStateException e) {
            // kein Fenster dort oder nicht erreichbar – Bewegen allein darf nicht scheitern
        }
    }

    @Override
    protected void button(WinCursor cursor, Point at, MouseButton button, boolean down, int clickCount,
                          Set<MouseButton> held) {
        HWND target = cursor.capture != null ? cursor.capture : hit.apply(at);
        if (down && cursor.capture == null) {
            cursor.capture = target;
            cursor.modern = input.isModernUi(target);
            cursor.focus = target; // wie ein echter Klick: das angeklickte Element bekommt den Tastatur-Fokus
            activateJava(cursor, target); // sonst bekommt ein angeklicktes Feld keinen Fokus; Klicks gehen auch so
        }
        if (cursor.modern) {
            modernButton(cursor, at, button, down, held);
            return;
        }
        if (!down && held.isEmpty()) {
            cursor.capture = null;
        }
        Set<Integer> keys = cursor.keys;
        Runnable send = () -> input.button(target, at, button, down, clickCount > 1, held, keys);
        if (input.isAwt(target)) { // AWT liest die gehaltenen Maustasten aus dem Tastaturzustand, nicht aus der Nachricht
            keyboard.withModifiersAndButtons(target, keys, held, send);
        } else {
            keyboard.withModifiers(target, keys, send);
        }
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
        HWND target = cursor.capture != null ? cursor.capture : hit.apply(at);
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
        HWND target = keyTarget(cursor, at);
        requireJavaActive(cursor, target);
        keyboard.type(target, text);
        return false;
    }

    @Override
    protected void key(WinCursor cursor, Point at, int keyCode, boolean down, Set<Integer> heldKeys) {
        HWND target = keyTarget(cursor, at);
        if (down) {
            requireJavaActive(cursor, target);
        }
        keyboard.key(target, keyCode, down, heldKeys);
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
