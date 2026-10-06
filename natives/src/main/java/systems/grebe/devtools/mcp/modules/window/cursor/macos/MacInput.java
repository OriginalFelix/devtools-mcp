package systems.grebe.devtools.mcp.modules.window.cursor.macos;

import java.awt.Point;
import java.awt.event.KeyEvent;
import java.util.Set;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import systems.grebe.devtools.mcp.modules.window.cursor.MouseButton;
import systems.grebe.devtools.mcp.modules.window.cursor.macos.MacNatives.ApplicationServices;
import systems.grebe.devtools.mcp.modules.window.cursor.macos.MacNatives.CGPoint;
import systems.grebe.devtools.mcp.modules.window.cursor.macos.MacNatives.CGRect;
import systems.grebe.devtools.mcp.modules.window.cursor.macos.MacNatives.CoreFoundation;
import systems.grebe.devtools.mcp.modules.window.cursor.macos.MacNatives.CoreGraphics;

/**
 * Mauseingaben als {@code CGEvent}, per {@code CGEventPostToPid} direkt an den Prozess des Fensters unter dem Punkt –
 * der Systemzeiger bewegt sich nicht. Text geht bevorzugt über die Bedienungshilfen ({@link #insert}). Das Fenster dazu kommt aus {@code CGWindowListCopyWindowInfo} (vorderstes zuerst).
 *
 * <p>Voraussetzung: die App hat die Berechtigung „Bedienungshilfen“. Grenzen: Anwendungen, die Ereignisse nur aus dem
 * HID-Strom lesen, reagieren nicht; Klicks in inaktive Fenster aktivieren diese je nach Anwendung zuerst.
 */
final class MacInput {

    private static final int LEFT_DOWN = 1;
    private static final int LEFT_UP = 2;
    private static final int RIGHT_DOWN = 3;
    private static final int RIGHT_UP = 4;
    private static final int MOVED = 5;
    private static final int LEFT_DRAGGED = 6;
    private static final int RIGHT_DRAGGED = 7;
    private static final int OTHER_DOWN = 25;
    private static final int OTHER_UP = 26;
    private static final int OTHER_DRAGGED = 27;
    private static final int FIELD_CLICK_STATE = 1;
    private static final int SCROLL_UNIT_LINE = 1;

    private final Pointer keyPid = MacNatives.cgConstant("kCGWindowOwnerPID");
    private final Pointer keyNumber = MacNatives.cgConstant("kCGWindowNumber");
    private final Pointer keyBounds = MacNatives.cgConstant("kCGWindowBounds");
    private static final Pointer AX_FOCUSED = MacNatives.cfString("AXFocusedUIElement");
    private static final Pointer AX_VALUE = MacNatives.cfString("AXValue");
    private static final Pointer AX_SELECTED_TEXT = MacNatives.cfString("AXSelectedText");

    void requireTrusted() {
        if (!MacNatives.ApplicationServices.INSTANCE.AXIsProcessTrusted()) {
            throw new IllegalStateException("Für Klicks braucht die App die Berechtigung „Bedienungshilfen“ "
                    + "(Systemeinstellungen → Datenschutz & Sicherheit → Bedienungshilfen) – aus einer IDE oder einem "
                    + "Terminal gestartet: dieses Programm.");
        }
    }

    /**
     * Prozess des vordersten Fensters unter dem Punkt, ohne das Fenster des Zeigers selbst.
     *
     * @throws IllegalStateException wenn dort kein Fenster liegt
     */
    int pidAt(Point at, long ownWindowNumber) {
        CoreFoundation cf = CoreFoundation.INSTANCE;
        Pointer list = CoreGraphics.INSTANCE.CGWindowListCopyWindowInfo(
                CoreGraphics.kCGWindowListOptionOnScreenOnly | CoreGraphics.kCGWindowListExcludeDesktopElements, 0);
        if (list == null) {
            throw new IllegalStateException("Fensterliste nicht lesbar.");
        }
        try {
            long count = cf.CFArrayGetCount(list);
            for (long i = 0; i < count; i++) {
                Pointer info = cf.CFArrayGetValueAtIndex(list, i);
                if (number(info, keyNumber) == ownWindowNumber) {
                    continue;
                }
                CGRect bounds = new CGRect();
                Pointer dict = cf.CFDictionaryGetValue(info, keyBounds);
                if (dict != null && CoreGraphics.INSTANCE.CGRectMakeWithDictionaryRepresentation(dict, bounds)
                        && bounds.contains(at.x, at.y)) {
                    return number(info, keyPid);
                }
            }
        } finally {
            cf.CFRelease(list);
        }
        throw new IllegalStateException("Unter dem Zeiger (" + at.x + ", " + at.y + ") liegt kein Fenster.");
    }

    void move(int pid, Point at, Set<MouseButton> held) {
        int type = held.contains(MouseButton.LEFT) ? LEFT_DRAGGED
                : held.contains(MouseButton.RIGHT) ? RIGHT_DRAGGED
                : held.contains(MouseButton.MIDDLE) ? OTHER_DRAGGED : MOVED;
        MouseButton button = held.isEmpty() ? MouseButton.LEFT : held.iterator().next();
        post(pid, CoreGraphics.INSTANCE.CGEventCreateMouseEvent(null, type, point(at), cgButton(button)), 0);
    }

    void button(int pid, Point at, MouseButton button, boolean down, int clickCount) {
        int type = switch (button) {
            case LEFT -> down ? LEFT_DOWN : LEFT_UP;
            case RIGHT -> down ? RIGHT_DOWN : RIGHT_UP;
            case MIDDLE -> down ? OTHER_DOWN : OTHER_UP;
        };
        post(pid, CoreGraphics.INSTANCE.CGEventCreateMouseEvent(null, type, point(at), cgButton(button)), clickCount);
    }

    /** Positiv = nach unten; CoreGraphics zählt nach oben positiv. */
    void wheel(int pid, Point at, int notches) {
        Pointer event = CoreGraphics.INSTANCE.CGEventCreateScrollWheelEvent2(null, SCROLL_UNIT_LINE, 1, -notches, 0, 0);
        if (event != null) {
            CoreGraphics.INSTANCE.CGEventSetLocation(event, point(at));
        }
        post(pid, event, 0);
    }

    private static final int UNICODE_CHUNK = 20;
    private static final long FLAG_SHIFT = 0x20000;
    private static final long FLAG_CONTROL = 0x40000;
    private static final long FLAG_ALTERNATE = 0x80000;
    private static final long FLAG_COMMAND = 0x100000;

    /**
     * Text in das fokussierte Element des Prozesses: zuerst über die Bedienungshilfen ({@link #insert}); nur wenn die
     * App ihn dort nicht annimmt, als Unicode-Tastenereignisse, unabhängig vom Tastaturlayout.
     *
     * @return ob der Text nachweislich angekommen ist – bei Tastenereignissen und nicht nachprüfbarem Einfügen ist das
     *         ungeprüft ({@code false})
     */
    boolean type(int pid, String text) {
        Insert result = insert(pid, text);
        if (result != Insert.FAILED) {
            return result == Insert.CONFIRMED; // angenommen: nicht zusätzlich tippen, sonst stünde er doppelt da
        }
        for (int i = 0; i < text.length(); i += UNICODE_CHUNK) {
            short[] units = MacNatives.utf16(text.substring(i, Math.min(text.length(), i + UNICODE_CHUNK)));
            for (boolean down : new boolean[]{true, false}) {
                Pointer event = CoreGraphics.INSTANCE.CGEventCreateKeyboardEvent(null, (short) 0, down);
                if (event != null) {
                    CoreGraphics.INSTANCE.CGEventKeyboardSetUnicodeString(event, units.length, units);
                }
                post(pid, event, 0);
            }
        }
        return false;
    }

    /** Ergebnis von {@link #insert}. */
    enum Insert {
        /** Der Wert des Elements hat sich geändert. */
        CONFIRMED,
        /** Die App hat den Text angenommen, der Wert ist (noch) unverändert – manche Apps aktualisieren ihn später. */
        ACCEPTED,
        /** Kein fokussiertes Element oder die App unterstützt das Setzen nicht. */
        FAILED
    }

    private static final int CONFIRM_POLLS = 5;
    private static final int CONFIRM_POLL_MILLIS = 20;

    /**
     * Setzt den Text als Auswahl des fokussierten Elements ({@code AXSelectedText}) – wie Einfügen an der Schreibmarke,
     * ohne das Fenster zu aktivieren. Normaler Text geht als Tastenereignis nur an das Key-Fenster einer aktiven App;
     * im Hintergrund verwerfen viele Apps ihn. Ob er angekommen ist, zeigt der Wert des Elements ({@code AXValue}) –
     * kurz nachgelesen, weil manche Apps ihn erst verzögert aktualisieren.
     */
    Insert insert(int pid, String text) {
        CoreFoundation cf = CoreFoundation.INSTANCE;
        ApplicationServices ax = ApplicationServices.INSTANCE;
        Pointer app = ax.AXUIElementCreateApplication(pid);
        if (app == null) {
            return Insert.FAILED;
        }
        try {
            PointerByReference ref = new PointerByReference();
            if (ax.AXUIElementCopyAttributeValue(app, AX_FOCUSED, ref) != ApplicationServices.kAXErrorSuccess
                    || ref.getValue() == null) {
                return Insert.FAILED;
            }
            Pointer focused = ref.getValue();
            Pointer before = copy(focused, AX_VALUE);
            Pointer value = cf.CFStringCreateWithCharacters(null, MacNatives.utf16(text), text.length());
            try {
                if (ax.AXUIElementSetAttributeValue(focused, AX_SELECTED_TEXT, value) != ApplicationServices.kAXErrorSuccess) {
                    return Insert.FAILED;
                }
                if (before == null) {
                    return Insert.ACCEPTED; // Wert nicht lesbar – angenommen, aber nicht nachprüfbar
                }
                for (int i = 0; i < CONFIRM_POLLS; i++) {
                    Pointer after = copy(focused, AX_VALUE);
                    try {
                        if (after == null || cf.CFEqual(before, after) == 0) {
                            return Insert.CONFIRMED;
                        }
                    } finally {
                        release(after);
                    }
                    sleep(CONFIRM_POLL_MILLIS);
                }
                return Insert.ACCEPTED;
            } finally {
                cf.CFRelease(value);
                release(before);
                cf.CFRelease(focused);
            }
        } finally {
            cf.CFRelease(app);
        }
    }

    private static void sleep(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Unterbrochen", e);
        }
    }

    private static Pointer copy(Pointer element, Pointer attribute) {
        PointerByReference ref = new PointerByReference();
        return ApplicationServices.INSTANCE.AXUIElementCopyAttributeValue(element, attribute, ref)
                == ApplicationServices.kAXErrorSuccess ? ref.getValue() : null;
    }

    private static void release(Pointer object) {
        if (object != null) {
            CoreFoundation.INSTANCE.CFRelease(object);
        }
    }

    /** Taste mit den gehaltenen Modifikatoren als Flags (Cmd = {@code VK_META}). */
    void key(int pid, int javaKeyCode, boolean down, Set<Integer> heldKeys) {
        int code = MacKeyCodes.of(javaKeyCode);
        if (code < 0) {
            throw new IllegalArgumentException("Taste " + KeyEvent.getKeyText(javaKeyCode)
                    + " lässt sich unter macOS nicht senden.");
        }
        Pointer event = CoreGraphics.INSTANCE.CGEventCreateKeyboardEvent(null, (short) code, down);
        if (event != null) {
            long flags = 0;
            if (heldKeys.contains(KeyEvent.VK_SHIFT)) {
                flags |= FLAG_SHIFT;
            }
            if (heldKeys.contains(KeyEvent.VK_CONTROL)) {
                flags |= FLAG_CONTROL;
            }
            if (heldKeys.contains(KeyEvent.VK_ALT)) {
                flags |= FLAG_ALTERNATE;
            }
            if (heldKeys.contains(KeyEvent.VK_META)) {
                flags |= FLAG_COMMAND;
            }
            CoreGraphics.INSTANCE.CGEventSetFlags(event, flags);
        }
        post(pid, event, 0);
    }

    private static void post(int pid, Pointer event, int clickCount) {
        if (event == null) {
            throw new IllegalStateException("CGEvent nicht erzeugt");
        }
        try {
            if (clickCount > 0) {
                CoreGraphics.INSTANCE.CGEventSetIntegerValueField(event, FIELD_CLICK_STATE, clickCount);
            }
            CoreGraphics.INSTANCE.CGEventPostToPid(pid, event);
        } finally {
            CoreFoundation.INSTANCE.CFRelease(event);
        }
    }

    private static int cgButton(MouseButton button) {
        return switch (button) {
            case LEFT -> 0;
            case RIGHT -> 1;
            case MIDDLE -> 2;
        };
    }

    private static CGPoint point(Point at) {
        return new CGPoint(at.x, at.y);
    }

    private static int number(Pointer dict, Pointer key) {
        Pointer n = CoreFoundation.INSTANCE.CFDictionaryGetValue(dict, key);
        IntByReference out = new IntByReference(-1);
        if (n != null) {
            CoreFoundation.INSTANCE.CFNumberGetValue(n, CoreFoundation.kCFNumberIntType, out);
        }
        return out.getValue();
    }
}
