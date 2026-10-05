package systems.grebe.devtools.mcp.modules.window.cursor.x11;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;
import java.util.function.Supplier;

import com.sun.jna.Callback;
import com.sun.jna.Function;
import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;

/**
 * Ein echter zweiter Zeiger per Multi-Pointer-X (XInput2). {@code XIChangeHierarchy(XIAddMaster)} legt ein neues
 * Master-Paar (Zeiger + Tastatur) an, das der X-Server selbst zeichnet; bewegt wird es mit {@code XIWarpPointer},
 * zerstört mit {@code XIRemoveMaster}. Tasten drückt XTest über das XTEST-Gerät, das der Server dem neuen Master
 * mitgibt – die Ereignisse kommen damit vom zweiten Zeiger, nicht vom Zeiger des Nutzers.
 *
 * <p>Zum Master gehört eine eigene Master-Tastatur: ihr Fokus folgt dem zweiten Zeiger ({@code PointerRoot}), Tasten
 * kommen über deren XTEST-Gerät – die Tastatur des Nutzers und ihr Fokus bleiben unberührt.
 *
 * <p>Eine eigene Display-Verbindung, alle Aufrufe synchronisiert. X-Fehler würden mit dem Standard-Handler den Prozess
 * beenden; deshalb ist während jedes Aufrufs ein eigener Handler gesetzt, der Fehler dieser Verbindung meldet und die
 * anderer Verbindungen (z.B. AWT) an den vorherigen Handler weiterreicht.
 */
final class X11MasterPointer {

    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static final int SUCCESS = 0;
    private static final int XI_ALL_DEVICES = 0;
    private static final int XI_ALL_MASTER_DEVICES = 1;
    private static final int XI_MASTER_POINTER = 1;
    private static final int XI_SLAVE_POINTER = 3;
    private static final int XI_MASTER_KEYBOARD = 2;
    private static final int XI_SLAVE_KEYBOARD = 4;
    private static final long POINTER_ROOT = 1;
    private static final int XI_ADD_MASTER = 1;
    private static final int XI_REMOVE_MASTER = 2;
    private static final int XI_FLOATING = 2;
    private static final int P = Native.POINTER_SIZE;
    /** {@code XIDeviceInfo { int deviceid; char *name; int use; int attachment; Bool enabled; int num_classes; XIAnyClassInfo **classes; }} */
    private static final long DEVICE_INFO_SIZE = align(2L * P + 16, P) + P;

    interface X11 extends Library {
        X11 INSTANCE = Native.load("X11", X11.class);

        Pointer XOpenDisplay(String name);

        int XCloseDisplay(Pointer display);

        NativeLong XDefaultRootWindow(Pointer display);

        boolean XQueryExtension(Pointer display, String name, IntByReference opcode, IntByReference event,
                                IntByReference error);

        int XSync(Pointer display, boolean discard);
    }

    interface Xi extends Library {
        Xi INSTANCE = Native.load("Xi", Xi.class);

        int XIQueryVersion(Pointer display, IntByReference major, IntByReference minor);

        int XIChangeHierarchy(Pointer display, Pointer changes, int count);

        Pointer XIQueryDevice(Pointer display, int deviceId, IntByReference count);

        void XIFreeDeviceInfo(Pointer info);

        int XIWarpPointer(Pointer display, int deviceId, NativeLong srcWindow, NativeLong dstWindow, double srcX,
                          double srcY, int srcWidth, int srcHeight, double dstX, double dstY);

        /** XInput 1 – XTest braucht das {@code XDevice*}. */
        Pointer XOpenDevice(Pointer display, NativeLong deviceId);

        int XCloseDevice(Pointer display, Pointer device);

        int XISetFocus(Pointer display, int deviceId, NativeLong focus, NativeLong time);
    }

    interface Keys extends Library {
        Keys INSTANCE = Native.load("X11", Keys.class);

        byte XKeysymToKeycode(Pointer display, NativeLong keysym);

        NativeLong XkbKeycodeToKeysym(Pointer display, byte keycode, int group, int level);
    }

    interface Xtst extends Library {
        Xtst INSTANCE = Native.load("Xtst", Xtst.class);

        boolean XTestQueryExtension(Pointer display, IntByReference event, IntByReference error, IntByReference major,
                                    IntByReference minor);

        int XTestFakeDeviceButtonEvent(Pointer display, Pointer device, int button, boolean press, Pointer axes,
                                       int axisCount, NativeLong delay);

        int XTestFakeDeviceKeyEvent(Pointer display, Pointer device, int keycode, boolean press, Pointer axes,
                                    int axisCount, NativeLong delay);
    }

    interface XErrorHandler extends Callback {
        int handle(Pointer display, Pointer event);
    }

    private static final Function SET_ERROR_HANDLER = NativeLibrary.getInstance("X11").getFunction("XSetErrorHandler");

    private final String masterName = "devtools-ki-" + ProcessHandle.current().pid() + "-" + COUNTER.incrementAndGet();
    private Pointer display;
    private final NativeLong root;
    /** 0, solange der Master nicht angelegt ist. */
    private int masterId;
    private Pointer xtestDevice;
    private Pointer xtestKeyboard;
    /** Fehlercode des letzten X-Fehlers dieser Verbindung während {@link #guarded}; 0 = keiner. */
    private volatile int lastError;
    private Pointer previousHandler;
    /** Fest referenziert, solange er gesetzt sein kann. */
    private final XErrorHandler handler = this::onError;

    X11MasterPointer(int x, int y) {
        display = X11.INSTANCE.XOpenDisplay(null);
        if (display == null) {
            throw new IllegalStateException("Keine Verbindung zum X-Server (DISPLAY=" + System.getenv("DISPLAY") + ")");
        }
        try {
            requireExtensions();
            root = X11.INSTANCE.XDefaultRootWindow(display);
            masterId = guarded("XIAddMaster", () -> {
                addMaster();
                return findDevice(XI_ALL_MASTER_DEVICES, masterName + " pointer", use -> use == XI_MASTER_POINTER, -1);
            });
            xtestDevice = guarded("XOpenDevice", () -> {
                int slave = findDevice(XI_ALL_DEVICES, masterName + " XTEST pointer",
                        use -> use == XI_SLAVE_POINTER, masterId);
                return Xi.INSTANCE.XOpenDevice(display, new NativeLong(slave));
            });
            if (xtestDevice == null) {
                throw new IllegalStateException("XTEST-Gerät des neuen Zeigers nicht zu öffnen");
            }
            xtestKeyboard = guarded("XISetFocus", () -> {
                int keyboard = findDevice(XI_ALL_MASTER_DEVICES, masterName + " keyboard",
                        use -> use == XI_MASTER_KEYBOARD, -1);
                Xi.INSTANCE.XISetFocus(display, keyboard, new NativeLong(POINTER_ROOT), new NativeLong(0));
                int slave = findDevice(XI_ALL_DEVICES, masterName + " XTEST keyboard",
                        use -> use == XI_SLAVE_KEYBOARD, keyboard);
                return Xi.INSTANCE.XOpenDevice(display, new NativeLong(slave));
            });
            if (xtestKeyboard == null) {
                throw new IllegalStateException("XTEST-Tastatur des neuen Zeigers nicht zu öffnen");
            }
            warp(x, y);
        } catch (RuntimeException | LinkageError e) {
            closeQuietly();
            throw e;
        }
    }

    String masterName() {
        return masterName;
    }

    synchronized void moveTo(int x, int y) {
        requireOpen();
        warp(x, y);
    }

    /** X-Taste: 1 links, 2 Mitte, 3 rechts, 4/5 Rad hoch/runter. */
    synchronized void button(int button, boolean press) {
        requireOpen();
        guarded("XTestFakeDeviceButtonEvent", () -> Xtst.INSTANCE.XTestFakeDeviceButtonEvent(display, xtestDevice,
                button, press, Pointer.NULL, 0, new NativeLong(0)));
    }

    /** Taste der eigenen Tastatur ({@link X11Keysyms}); wirkt im Fenster unter dem zweiten Zeiger. */
    synchronized void key(int keysym, boolean press) {
        requireOpen();
        int keycode = keycode(keysym);
        if (keycode == 0) {
            throw new IllegalArgumentException("Taste (Keysym 0x" + Integer.toHexString(keysym) + ") gibt es in der "
                    + "Tastaturbelegung des X-Servers nicht.");
        }
        fakeKey(keycode, press);
    }

    /** Tippt Text: je Zeichen die Taste der Belegung, bei Bedarf mit Umschalt. */
    synchronized void type(String text) {
        requireOpen();
        int shift = keycode(X11Keysyms.SHIFT_L);
        text.codePoints().forEach(cp -> {
            if (cp == '\r') {
                return;
            }
            int keysym = X11Keysyms.ofChar(cp);
            int keycode = keycode(keysym);
            if (keycode == 0) {
                throw new IllegalArgumentException("Zeichen '" + new String(Character.toChars(cp)) + "' gibt es in "
                        + "der Tastaturbelegung des X-Servers nicht.");
            }
            boolean needsShift = level(keycode, 0) != keysym && level(keycode, 1) == keysym;
            if (!needsShift && level(keycode, 0) != keysym && keysym != X11Keysyms.RETURN && keysym != X11Keysyms.TAB) {
                throw new IllegalArgumentException("Zeichen '" + new String(Character.toChars(cp)) + "' braucht AltGr "
                        + "oder eine andere Ebene der Tastaturbelegung – nicht unterstützt.");
            }
            if (needsShift) {
                fakeKey(shift, true);
            }
            fakeKey(keycode, true);
            fakeKey(keycode, false);
            if (needsShift) {
                fakeKey(shift, false);
            }
        });
    }

    private int keycode(int keysym) {
        return Keys.INSTANCE.XKeysymToKeycode(display, new NativeLong(keysym)) & 0xFF;
    }

    private long level(int keycode, int level) {
        return Keys.INSTANCE.XkbKeycodeToKeysym(display, (byte) keycode, 0, level).longValue();
    }

    private void fakeKey(int keycode, boolean press) {
        guarded("XTestFakeDeviceKeyEvent", () -> Xtst.INSTANCE.XTestFakeDeviceKeyEvent(display, xtestKeyboard,
                keycode, press, Pointer.NULL, 0, new NativeLong(0)));
    }

    synchronized boolean isOpen() {
        return display != null;
    }

    synchronized void close() {
        if (display == null) {
            return;
        }
        try {
            guarded("XIRemoveMaster", () -> {
                if (xtestDevice != null) {
                    Xi.INSTANCE.XCloseDevice(display, xtestDevice);
                    xtestDevice = null;
                }
                if (xtestKeyboard != null) {
                    Xi.INSTANCE.XCloseDevice(display, xtestKeyboard);
                    xtestKeyboard = null;
                }
                return removeMaster();
            });
        } finally {
            X11.INSTANCE.XCloseDisplay(display);
            display = null;
        }
    }

    private void closeQuietly() {
        if (display == null) {
            return;
        }
        try {
            if (xtestDevice != null) {
                Xi.INSTANCE.XCloseDevice(display, xtestDevice);
            }
            if (xtestKeyboard != null) {
                Xi.INSTANCE.XCloseDevice(display, xtestKeyboard);
            }
            if (masterId > 0) {
                removeMaster();
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Aufräumen nach einem Fehler – der ursprüngliche Fehler zählt
        } finally {
            X11.INSTANCE.XCloseDisplay(display);
            display = null;
        }
    }

    private void requireOpen() {
        if (display == null) {
            throw new IllegalStateException("Der Zeiger ist bereits zerstört.");
        }
    }

    private void requireExtensions() {
        if (!X11.INSTANCE.XQueryExtension(display, "XInputExtension", new IntByReference(), new IntByReference(),
                new IntByReference())) {
            throw new UnsupportedOperationException("Der X-Server hat keine XInput-Erweiterung.");
        }
        IntByReference major = new IntByReference(2);
        IntByReference minor = new IntByReference(0);
        if (Xi.INSTANCE.XIQueryVersion(display, major, minor) != SUCCESS || major.getValue() < 2) {
            throw new UnsupportedOperationException("Der X-Server kann kein XInput2 (Multi-Pointer), nur "
                    + major.getValue() + "." + minor.getValue() + ".");
        }
        if (!Xtst.INSTANCE.XTestQueryExtension(display, new IntByReference(), new IntByReference(),
                new IntByReference(), new IntByReference())) {
            throw new UnsupportedOperationException("Der X-Server hat keine XTEST-Erweiterung.");
        }
    }

    /** {@code XIAddMasterInfo { int type; char *name; Bool send_core; Bool enable; }} */
    private void addMaster() {
        Memory name = new Memory(masterName.length() + 1L);
        name.setString(0, masterName, "US-ASCII");
        Memory change = new Memory(64);
        change.clear();
        change.setInt(0, XI_ADD_MASTER);
        change.setPointer(P, name);
        change.setInt(2L * P, 1);
        change.setInt(2L * P + 4, 1);
        int status = Xi.INSTANCE.XIChangeHierarchy(display, change, 1);
        if (status != SUCCESS) {
            throw new IllegalStateException("XIChangeHierarchy(XIAddMaster) meldet " + status);
        }
        X11.INSTANCE.XSync(display, false); // name muss bis hier leben
    }

    /** {@code XIRemoveMasterInfo { int type; int deviceid; int return_mode; int return_pointer; int return_keyboard; }} */
    private int removeMaster() {
        Memory change = new Memory(64);
        change.clear();
        change.setInt(0, XI_REMOVE_MASTER);
        change.setInt(4, masterId);
        change.setInt(8, XI_FLOATING);
        return Xi.INSTANCE.XIChangeHierarchy(display, change, 1);
    }

    /** Sucht ein Gerät nach Name und Verwendung; {@code attachment < 0} = egal. */
    private int findDevice(int which, String wanted, IntPredicate use, int attachment) {
        IntByReference count = new IntByReference();
        Pointer infos = Xi.INSTANCE.XIQueryDevice(display, which, count);
        if (infos == null) {
            throw new IllegalStateException("XIQueryDevice lieferte keine Geräte");
        }
        try {
            for (int i = 0; i < count.getValue(); i++) {
                Pointer info = infos.share(i * DEVICE_INFO_SIZE);
                Pointer name = info.getPointer(P);
                if (use.test(info.getInt(2L * P)) && (attachment < 0 || info.getInt(2L * P + 4) == attachment)
                        && name != null && wanted.equals(name.getString(0, "UTF-8"))) {
                    return info.getInt(0);
                }
            }
        } finally {
            Xi.INSTANCE.XIFreeDeviceInfo(infos);
        }
        throw new IllegalStateException("Gerät „" + wanted + "“ nicht gefunden");
    }

    private void warp(int x, int y) {
        guarded("XIWarpPointer", () -> Xi.INSTANCE.XIWarpPointer(display, masterId, new NativeLong(0), root, 0, 0, 0,
                0, x, y));
    }

    private static long align(long offset, int alignment) {
        return (offset + alignment - 1) / alignment * alignment;
    }

    /** Führt {@code call} mit eigenem Fehler-Handler aus, synchronisiert und wirft bei einem X-Fehler. */
    private synchronized <T> T guarded(String what, Supplier<T> call) {
        lastError = 0;
        previousHandler = SET_ERROR_HANDLER.invokePointer(new Object[]{handler});
        T result;
        try {
            result = call.get();
            X11.INSTANCE.XSync(display, false);
        } finally {
            SET_ERROR_HANDLER.invokePointer(new Object[]{previousHandler});
        }
        if (lastError != 0) {
            throw new IllegalStateException(what + ": X-Fehler " + lastError);
        }
        return result;
    }

    /**
     * {@code XErrorEvent { int type; Display *display; XID resourceid; unsigned long serial; unsigned char
     * error_code; ... }}
     */
    private int onError(Pointer errorDisplay, Pointer event) {
        if (errorDisplay != null && errorDisplay.equals(display)) {
            lastError = event.getByte(4L * P) & 0xFF;
            return 0;
        }
        Pointer previous = previousHandler;
        if (previous != null) {
            return Function.getFunction(previous).invokeInt(new Object[]{errorDisplay, event});
        }
        return 0;
    }
}
