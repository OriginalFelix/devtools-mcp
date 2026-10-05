package systems.grebe.devtools.mcp.modules.window.cursor.windows;

import java.awt.Point;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import com.sun.jna.Function;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.Guid.CLSID;
import com.sun.jna.platform.win32.Guid.IID;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.OleAuto;
import com.sun.jna.platform.win32.WTypes;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

/**
 * Bedienen per UI Automation (COM, {@code UIAutomationCore}) – für UWP/WinUI-Fenster, die Maus-Nachrichten nicht lesen
 * und echte Mausereignisse nur über den einen Mauszeiger des Systems annehmen. Ermittelt wird das Element unter der
 * Zeigerspitze ({@code ElementFromPoint}); ein Klick löst es aus wie die Anwendung selbst es bei einem Mausklick täte
 * (Invoke, Toggle, Select, Aus-/Einklappen, zuletzt die Standardaktion), das Mausrad scrollt den nächsten scrollbaren
 * Vorfahren. Der Mauszeiger des Nutzers bewegt sich dabei nicht, das Fenster wird nicht aktiviert.
 *
 * <p>Alle Aufrufe laufen auf einem eigenen Thread (COM im Multithread-Apartment, Per-Monitor-DPI v2 → physische Pixel).
 * Die Indizes der COM-Methoden folgen der Reihenfolge in {@code UIAutomationClient.h}.
 */
final class Win32Automation {

    private static final CLSID CLSID_CUI_AUTOMATION = new CLSID("{ff48dba4-60ef-4201-aa87-54103eef594e}");
    private static final IID IID_IUI_AUTOMATION = new IID("{30cbe57d-d9d0-452a-ab13-7ac5ac4825ee}");
    private static final int CLSCTX_INPROC_SERVER = 1;
    private static final int COINIT_MULTITHREADED = 0;
    private static final int S_OK = 0;
    private static final long TIMEOUT_SECONDS = 10;
    /** So weit nach oben wird nach einem bedienbaren Element gesucht (Text in einer Schaltfläche …). */
    private static final int MAX_PARENTS = 6;

    // IUnknown
    private static final int RELEASE = 2;
    // IUIAutomation
    private static final int ELEMENT_FROM_POINT = 7;
    private static final int GET_CONTROL_VIEW_WALKER = 14;
    // IUIAutomationTreeWalker
    private static final int GET_PARENT_ELEMENT = 3;
    // IUIAutomationElement
    private static final int GET_CURRENT_PATTERN = 16;
    private static final int GET_CURRENT_PROCESS_ID = 20;
    private static final int GET_CURRENT_CONTROL_TYPE = 21;
    private static final int GET_CURRENT_NAME = 23;
    private static final int GET_CURRENT_IS_ENABLED = 28;
    // Muster und ihre Methoden
    private static final int INVOKE_PATTERN = 10000;
    private static final int SCROLL_PATTERN = 10004;
    private static final int EXPAND_COLLAPSE_PATTERN = 10005;
    private static final int SELECTION_ITEM_PATTERN = 10010;
    private static final int TOGGLE_PATTERN = 10015;
    private static final int LEGACY_PATTERN = 10018;
    private static final int METHOD_3 = 3;
    private static final int COLLAPSE = 4;
    private static final int EXPAND_STATE = 5;
    private static final int LEGACY_DO_DEFAULT_ACTION = 4;
    // ScrollAmount
    private static final int SMALL_DECREMENT = 1;
    private static final int NO_AMOUNT = 2;
    private static final int SMALL_INCREMENT = 4;
    // ExpandCollapseState
    private static final int COLLAPSED = 0;
    // Steuerelementtypen, deren Standardaktion kein Klick ist
    private static final int CONTROL_TYPE_PANE = 50033;
    private static final int CONTROL_TYPE_WINDOW = 50032;

    /** Für {@code ElementFromPoint}: {@code POINT} als Wert. */
    @Structure.FieldOrder({"x", "y"})
    public static class PointByValue extends Structure implements Structure.ByValue {
        public int x;
        public int y;
    }

    private final ExecutorService thread = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "virtual-cursor-uia");
        t.setDaemon(true);
        return t;
    });
    private Pointer automation;

    /**
     * Löst das Element unter dem Punkt aus wie ein Linksklick.
     *
     * @param pids Prozesse, deren Elemente bedient werden dürfen (das Zielfenster)
     * @return was ausgelöst wurde, z.B. {@code Schaltfläche „Sechs“: Invoke}; leer, wenn dort nichts Bedienbares liegt
     */
    Optional<String> click(Point physical, Set<Long> pids) {
        return call(() -> {
            Pointer element = elementFromPoint(physical);
            Pointer walker = null;
            try {
                if (element == null || !pids.contains(processId(element))) {
                    return Optional.empty();
                }
                walker = controlViewWalker();
                Pointer current = element;
                element = null;
                for (int depth = 0; depth <= MAX_PARENTS && current != null; depth++) {
                    if (!pids.contains(processId(current))) {
                        release(current);
                        return Optional.empty();
                    }
                    Optional<String> done = act(current, depth);
                    if (done.isPresent()) {
                        String what = describe(current) + ": " + done.get();
                        release(current);
                        return Optional.of(what);
                    }
                    Pointer parent = parent(walker, current);
                    release(current);
                    current = parent;
                }
                if (current != null) {
                    release(current);
                }
                return Optional.empty();
            } finally {
                release(element);
                release(walker);
            }
        });
    }

    /**
     * Scrollt den nächsten scrollbaren Vorfahren des Elements unter dem Punkt; positiv = nach unten.
     *
     * @return ob gescrollt wurde
     */
    boolean scroll(Point physical, int notches, Set<Long> pids) {
        return call(() -> {
            Pointer walker = controlViewWalker();
            Pointer current = elementFromPoint(physical);
            try {
                for (int depth = 0; depth <= 2 * MAX_PARENTS && current != null; depth++) {
                    if (!pids.contains(processId(current))) {
                        return false;
                    }
                    Pointer pattern = pattern(current, SCROLL_PATTERN);
                    if (pattern != null) {
                        try {
                            int vertical = notches > 0 ? SMALL_INCREMENT : SMALL_DECREMENT;
                            for (int i = 0; i < Math.abs(notches); i++) {
                                if (invoke(pattern, METHOD_3, NO_AMOUNT, vertical) != S_OK) {
                                    return i > 0;
                                }
                            }
                            return true;
                        } finally {
                            release(pattern);
                        }
                    }
                    Pointer parent = parent(walker, current);
                    release(current);
                    current = parent;
                }
                return false;
            } finally {
                release(current);
                release(walker);
            }
        });
    }

    /** Bedient das Element mit dem ersten passenden Muster; leer, wenn es keins hat. */
    private Optional<String> act(Pointer element, int depth) {
        if (!enabled(element)) {
            return Optional.empty();
        }
        if (call(element, INVOKE_PATTERN, METHOD_3)) {
            return Optional.of("Invoke");
        }
        if (call(element, TOGGLE_PATTERN, METHOD_3)) {
            return Optional.of("Toggle");
        }
        if (call(element, SELECTION_ITEM_PATTERN, METHOD_3)) {
            return Optional.of("Select");
        }
        Pointer expand = pattern(element, EXPAND_COLLAPSE_PATTERN);
        if (expand != null) {
            try {
                IntByReference state = new IntByReference();
                invoke(expand, EXPAND_STATE, state.getPointer());
                boolean collapsed = state.getValue() == COLLAPSED;
                if (invoke(expand, collapsed ? METHOD_3 : COLLAPSE) == S_OK) {
                    return Optional.of(collapsed ? "Expand" : "Collapse");
                }
            } finally {
                release(expand);
            }
        }
        // Standardaktion nur nah an der Spitze und nicht für Flächen/Fenster (die „aktivieren“ sonst irgendetwas)
        int type = controlType(element);
        if (depth <= 1 && type != CONTROL_TYPE_PANE && type != CONTROL_TYPE_WINDOW
                && call(element, LEGACY_PATTERN, LEGACY_DO_DEFAULT_ACTION)) {
            return Optional.of("DoDefaultAction");
        }
        return Optional.empty();
    }

    /** Holt das Muster und ruft eine Methode ohne Argumente auf; ob es das Muster gab und der Aufruf gelang. */
    private boolean call(Pointer element, int patternId, int method) {
        Pointer pattern = pattern(element, patternId);
        if (pattern == null) {
            return false;
        }
        try {
            return invoke(pattern, method) == S_OK;
        } finally {
            release(pattern);
        }
    }

    private Pointer elementFromPoint(Point physical) {
        PointByValue pt = new PointByValue();
        pt.x = physical.x;
        pt.y = physical.y;
        PointerByReference out = new PointerByReference();
        int hr = invoke(automation(), ELEMENT_FROM_POINT, pt, out);
        return hr == S_OK ? out.getValue() : null;
    }

    private Pointer controlViewWalker() {
        PointerByReference out = new PointerByReference();
        int hr = invoke(automation(), GET_CONTROL_VIEW_WALKER, out);
        if (hr != S_OK || out.getValue() == null) {
            throw new IllegalStateException("UI Automation: kein TreeWalker (0x" + Integer.toHexString(hr) + ")");
        }
        return out.getValue();
    }

    private Pointer parent(Pointer walker, Pointer element) {
        PointerByReference out = new PointerByReference();
        return invoke(walker, GET_PARENT_ELEMENT, element, out) == S_OK ? out.getValue() : null;
    }

    private Pointer pattern(Pointer element, int patternId) {
        PointerByReference out = new PointerByReference();
        return invoke(element, GET_CURRENT_PATTERN, patternId, out) == S_OK ? out.getValue() : null;
    }

    private long processId(Pointer element) {
        IntByReference pid = new IntByReference();
        return invoke(element, GET_CURRENT_PROCESS_ID, pid.getPointer()) == S_OK
                ? Integer.toUnsignedLong(pid.getValue()) : -1;
    }

    private int controlType(Pointer element) {
        IntByReference type = new IntByReference();
        return invoke(element, GET_CURRENT_CONTROL_TYPE, type.getPointer()) == S_OK ? type.getValue() : 0;
    }

    private boolean enabled(Pointer element) {
        IntByReference value = new IntByReference();
        return invoke(element, GET_CURRENT_IS_ENABLED, value.getPointer()) != S_OK || value.getValue() != 0;
    }

    private String describe(Pointer element) {
        PointerByReference bstr = new PointerByReference();
        String name = "";
        if (invoke(element, GET_CURRENT_NAME, bstr) == S_OK && bstr.getValue() != null) {
            name = bstr.getValue().getWideString(0);
            OleAuto.INSTANCE.SysFreeString(new WTypes.BSTR(bstr.getValue()));
        }
        return "Element „" + name + "“ (Typ " + controlType(element) + ")";
    }

    /** {@code this->lpVtbl[index](this, args...)}, Ergebnis HRESULT. */
    private static int invoke(Pointer self, int index, Object... args) {
        Pointer vtbl = self.getPointer(0);
        Function f = Function.getFunction(vtbl.getPointer((long) index * Native.POINTER_SIZE), Function.ALT_CONVENTION);
        Object[] all = new Object[args.length + 1];
        all[0] = self;
        System.arraycopy(args, 0, all, 1, args.length);
        return f.invokeInt(all);
    }

    private static void release(Pointer unknown) {
        if (unknown != null) {
            invoke(unknown, RELEASE);
        }
    }

    private Pointer automation() {
        if (automation == null) {
            PointerByReference out = new PointerByReference();
            int hr = Ole32.INSTANCE.CoCreateInstance(CLSID_CUI_AUTOMATION, null, CLSCTX_INPROC_SERVER,
                    IID_IUI_AUTOMATION, out).intValue();
            if (hr != S_OK) {
                throw new IllegalStateException("UI Automation nicht verfügbar (CoCreateInstance: 0x"
                        + Integer.toHexString(hr) + ")");
            }
            automation = out.getValue();
        }
        return automation;
    }

    /** Auf dem COM-Thread ausführen (beim ersten Mal: COM und DPI-Kontext einrichten). */
    private <T> T call(Supplier<T> body) {
        Future<T> f = thread.submit(() -> {
            if (automation == null) {
                Ole32.INSTANCE.CoInitializeEx(null, COINIT_MULTITHREADED);
                User32Ext.INSTANCE.SetThreadDpiAwarenessContext(User32Ext.PER_MONITOR_AWARE_V2);
            }
            return body.get();
        });
        try {
            return f.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            throw c instanceof RuntimeException r ? r : new IllegalStateException("UI Automation: " + c.getMessage(), c);
        } catch (TimeoutException e) {
            f.cancel(true);
            throw new IllegalStateException("UI Automation reagiert nicht (Anwendung hängt?).", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Unterbrochen", e);
        }
    }
}
