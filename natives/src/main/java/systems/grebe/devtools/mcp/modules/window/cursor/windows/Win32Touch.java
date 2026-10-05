package systems.grebe.devtools.mcp.modules.window.cursor.windows;

import java.awt.Point;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.WinDef.POINT;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

/**
 * Touch-Eingaben per {@code InjectTouchInput} (ab Windows 8) – für Anwendungen, die Fenster-Nachrichten nicht lesen
 * (UWP/WinUI wie der Windows-Rechner). Injizierte Berührungen bewegen den Mauszeiger des Nutzers nicht; jeder zweite
 * Zeiger bekommt eine eigene Kontakt-ID, so dass mehrere gleichzeitig arbeiten können.
 *
 * <p>Punkte sind physische Bildschirmpixel; der aufrufende Thread muss Per-Monitor-DPI-aware sein.
 */
final class Win32Touch {

    /** Gleichzeitige Kontakte – Obergrenze von Windows ist 256, mehr KI-Zeiger braucht niemand. */
    static final int MAX_CONTACTS = 10;
    private static final int PT_TOUCH = 2;
    private static final int TOUCH_FEEDBACK_NONE = 3;
    private static final int POINTER_FLAG_INRANGE = 0x2;
    private static final int POINTER_FLAG_INCONTACT = 0x4;
    private static final int POINTER_FLAG_DOWN = 0x10000;
    private static final int POINTER_FLAG_UPDATE = 0x20000;
    private static final int POINTER_FLAG_UP = 0x40000;
    private static final int TOUCH_MASK_CONTACTAREA = 0x1;
    private static final int TOUCH_MASK_PRESSURE = 0x4;
    private static final int CONTACT_RADIUS = 2;
    private static final int PRESSURE = 32000;

    interface TouchUser32 extends StdCallLibrary {
        TouchUser32 INSTANCE = Native.load("user32", TouchUser32.class, W32APIOptions.DEFAULT_OPTIONS);

        boolean InitializeTouchInjection(int maxCount, int feedbackMode);

        boolean InjectTouchInput(int count, PointerTouchInfo[] contacts);
    }

    @Structure.FieldOrder({"pointerType", "pointerId", "frameId", "pointerFlags", "sourceDevice", "hwndTarget",
            "ptPixelLocation", "ptHimetricLocation", "ptPixelLocationRaw", "ptHimetricLocationRaw", "dwTime",
            "historyCount", "inputData", "dwKeyStates", "performanceCount", "buttonChangeType"})
    public static class PointerInfo extends Structure {
        public int pointerType;
        public int pointerId;
        public int frameId;
        public int pointerFlags;
        public Pointer sourceDevice;
        public Pointer hwndTarget;
        public POINT ptPixelLocation = new POINT();
        public POINT ptHimetricLocation = new POINT();
        public POINT ptPixelLocationRaw = new POINT();
        public POINT ptHimetricLocationRaw = new POINT();
        public int dwTime;
        public int historyCount;
        public int inputData;
        public int dwKeyStates;
        public long performanceCount;
        public int buttonChangeType;
    }

    @Structure.FieldOrder({"pointerInfo", "touchFlags", "touchMask", "rcContact", "rcContactRaw", "orientation",
            "pressure"})
    public static class PointerTouchInfo extends Structure {
        public PointerInfo pointerInfo = new PointerInfo();
        public int touchFlags;
        public int touchMask;
        public RECT rcContact = new RECT();
        public RECT rcContactRaw = new RECT();
        public int orientation;
        public int pressure;
    }

    private boolean initialized;

    synchronized void down(int contact, Point at) {
        inject(contact, at, POINTER_FLAG_INRANGE | POINTER_FLAG_INCONTACT | POINTER_FLAG_DOWN);
    }

    synchronized void move(int contact, Point at) {
        inject(contact, at, POINTER_FLAG_INRANGE | POINTER_FLAG_INCONTACT | POINTER_FLAG_UPDATE);
    }

    synchronized void up(int contact, Point at) {
        inject(contact, at, POINTER_FLAG_UP);
    }

    private void inject(int contact, Point at, int flags) {
        if (!initialized) {
            if (!TouchUser32.INSTANCE.InitializeTouchInjection(MAX_CONTACTS, TOUCH_FEEDBACK_NONE)) {
                throw new IllegalStateException("Touch-Eingabe nicht verfügbar (InitializeTouchInjection: Fehler "
                        + Native.getLastError() + ").");
            }
            initialized = true;
        }
        PointerTouchInfo[] contacts = (PointerTouchInfo[]) new PointerTouchInfo().toArray(1);
        PointerTouchInfo t = contacts[0];
        t.pointerInfo.pointerType = PT_TOUCH;
        t.pointerInfo.pointerId = contact;
        t.pointerInfo.pointerFlags = flags;
        t.pointerInfo.ptPixelLocation.x = at.x;
        t.pointerInfo.ptPixelLocation.y = at.y;
        t.touchMask = TOUCH_MASK_CONTACTAREA | TOUCH_MASK_PRESSURE;
        t.rcContact.left = at.x - CONTACT_RADIUS;
        t.rcContact.right = at.x + CONTACT_RADIUS;
        t.rcContact.top = at.y - CONTACT_RADIUS;
        t.rcContact.bottom = at.y + CONTACT_RADIUS;
        t.pressure = PRESSURE;
        if (!TouchUser32.INSTANCE.InjectTouchInput(1, contacts)) {
            throw new IllegalStateException("Touch-Eingabe abgelehnt (InjectTouchInput: Fehler " + Native.getLastError()
                    + ") – liegt dort ein Fenster mit höheren Rechten?");
        }
    }
}
