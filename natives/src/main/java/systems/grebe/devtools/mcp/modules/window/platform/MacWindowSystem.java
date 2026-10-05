package systems.grebe.devtools.mcp.modules.window.platform;

import java.awt.Rectangle;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import systems.grebe.devtools.mcp.modules.window.cursor.macos.MacZOrder;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * macOS: Fenster über {@code CGWindowListCopyWindowInfo} (CoreGraphics), Aktivieren über
 * {@code NSRunningApplication activateWithOptions:} (objc-Runtime) und gezielt ein Fenster nach vorn über die
 * Bedienungshilfen-API ({@code AXRaise}).
 *
 * <p>Berechtigungen: Robot-Eingaben und {@code AXRaise} brauchen „Bedienungshilfen“, Fenstertitel und Screenshots
 * „Bildschirmaufnahme“ (Systemeinstellungen → Datenschutz &amp; Sicherheit). Koordinaten sind Punkte mit Ursprung oben
 * links am Hauptbildschirm – wie in Java.
 */
final class MacWindowSystem implements WindowSystem {

    private static final String FRAMEWORKS = "/System/Library/Frameworks/";
    private static final int ON_SCREEN_ONLY = 1;
    private static final int EXCLUDE_DESKTOP_ELEMENTS = 16;
    private static final int CF_NUMBER_SINT64 = 4;
    private static final int CF_STRING_ENCODING_UTF8 = 0x08000100;
    private static final long ACTIVATE_ALL_WINDOWS_IGNORING_OTHER_APPS = 1 | 2;

    private final MethodHandle cfArrayGetCount;
    private final MethodHandle cfArrayGetValueAtIndex;
    private final MethodHandle cfDictionaryGetValue;
    private final MethodHandle cfNumberGetValue;
    private final MethodHandle cfStringCreate;
    private final MethodHandle cfStringGetLength;
    private final MethodHandle cfStringGetMaxSize;
    private final MethodHandle cfStringGetCString;
    private final MethodHandle cfRelease;
    private final MethodHandle cgWindowList;
    private final MethodHandle cgRectFromDictionary;
    private final MethodHandle cgPreflightScreenCapture;
    private final MethodHandle axIsProcessTrusted;
    private final MethodHandle axCreateApplication;
    private final MethodHandle axCopyAttribute;
    private final MethodHandle axPerformAction;
    private final MethodHandle axGetWindow;
    private final MethodHandle objcGetClass;
    private final MethodHandle selRegisterName;
    private final MethodHandle msgSendPid;
    private final MethodHandle msgSendLong;

    private final MemorySegment keyNumber;
    private final MemorySegment keyOwnerPid;
    private final MemorySegment keyName;
    private final MemorySegment keyLayer;
    private final MemorySegment keyBounds;
    private final MemorySegment axWindows;
    private final MemorySegment axRaise;

    MacWindowSystem() {
        Linker linker = Linker.nativeLinker();
        Arena global = Arena.global();
        SymbolLookup cf = SymbolLookup.libraryLookup(FRAMEWORKS + "CoreFoundation.framework/CoreFoundation", global);
        SymbolLookup cg = SymbolLookup.libraryLookup(FRAMEWORKS + "CoreGraphics.framework/CoreGraphics", global);
        SymbolLookup as = SymbolLookup.libraryLookup(
                FRAMEWORKS + "ApplicationServices.framework/ApplicationServices", global);
        // AppKit laden, damit die Klasse NSRunningApplication registriert ist
        SymbolLookup.libraryLookup(FRAMEWORKS + "AppKit.framework/AppKit", global);
        SymbolLookup objc = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", global);

        cfArrayGetCount = Natives.bind(linker, cf, "CFArrayGetCount", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
        cfArrayGetValueAtIndex = Natives.bind(linker, cf, "CFArrayGetValueAtIndex",
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG));
        cfDictionaryGetValue = Natives.bind(linker, cf, "CFDictionaryGetValue",
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
        cfNumberGetValue = Natives.bind(linker, cf, "CFNumberGetValue",
                FunctionDescriptor.of(JAVA_BYTE, ADDRESS, JAVA_LONG, ADDRESS));
        cfStringCreate = Natives.bind(linker, cf, "CFStringCreateWithCString",
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT));
        cfStringGetLength = Natives.bind(linker, cf, "CFStringGetLength", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
        cfStringGetMaxSize = Natives.bind(linker, cf, "CFStringGetMaximumSizeForEncoding",
                FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_INT));
        cfStringGetCString = Natives.bind(linker, cf, "CFStringGetCString",
                FunctionDescriptor.of(JAVA_BYTE, ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT));
        cfRelease = Natives.bind(linker, cf, "CFRelease", FunctionDescriptor.ofVoid(ADDRESS));
        cgWindowList = Natives.bind(linker, cg, "CGWindowListCopyWindowInfo",
                FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_INT));
        cgRectFromDictionary = Natives.bind(linker, cg, "CGRectMakeWithDictionaryRepresentation",
                FunctionDescriptor.of(JAVA_BYTE, ADDRESS, ADDRESS));
        cgPreflightScreenCapture = Natives.bindOptional(linker, cg, "CGPreflightScreenCaptureAccess",
                FunctionDescriptor.of(JAVA_BYTE));
        axIsProcessTrusted = Natives.bind(linker, as, "AXIsProcessTrusted", FunctionDescriptor.of(JAVA_BYTE));
        axCreateApplication = Natives.bind(linker, as, "AXUIElementCreateApplication",
                FunctionDescriptor.of(ADDRESS, JAVA_INT));
        axCopyAttribute = Natives.bind(linker, as, "AXUIElementCopyAttributeValue",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        axPerformAction = Natives.bind(linker, as, "AXUIElementPerformAction",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        // nicht dokumentiert, aber seit Jahren stabil: ordnet ein AX-Fenster seiner CGWindowID zu
        axGetWindow = Natives.bindOptional(linker, as, "_AXUIElementGetWindow",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        objcGetClass = Natives.bind(linker, objc, "objc_getClass", FunctionDescriptor.of(ADDRESS, ADDRESS));
        selRegisterName = Natives.bind(linker, objc, "sel_registerName", FunctionDescriptor.of(ADDRESS, ADDRESS));
        // objc_msgSend muss je Signatur mit genau den Argumenttypen gebunden werden (arm64: nicht variadisch)
        msgSendPid = Natives.bind(linker, objc, "objc_msgSend",
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT));
        msgSendLong = Natives.bind(linker, objc, "objc_msgSend",
                FunctionDescriptor.of(JAVA_BYTE, ADDRESS, ADDRESS, JAVA_LONG));

        keyNumber = constant(cg, "kCGWindowNumber");
        keyOwnerPid = constant(cg, "kCGWindowOwnerPID");
        keyName = constant(cg, "kCGWindowName");
        keyLayer = constant(cg, "kCGWindowLayer");
        keyBounds = constant(cg, "kCGWindowBounds");
        axWindows = cfString("AXWindows");
        axRaise = cfString("AXRaise");
    }

    /** Wert einer exportierten {@code CFStringRef}-Konstante. */
    private static MemorySegment constant(SymbolLookup lib, String name) {
        return lib.findOrThrow(name).reinterpret(ADDRESS.byteSize()).get(ADDRESS, 0);
    }

    /** Dauerhafte CFString (wird nie freigegeben). */
    private MemorySegment cfString(String value) {
        try (Arena arena = Arena.ofConfined()) {
            return (MemorySegment) Natives.call(cfStringCreate, "CFStringCreateWithCString", MemorySegment.NULL,
                    arena.allocateFrom(value), CF_STRING_ENCODING_UTF8);
        }
    }

    @Override
    public String name() {
        return "macOS (CoreGraphics)";
    }

    @Override
    public Optional<String> unsupportedReason() {
        return Optional.empty();
    }

    @Override
    public List<String> warnings() {
        List<String> out = new ArrayList<>();
        if (!inputTrusted()) {
            out.add("Bedienungshilfen nicht freigegeben – Klicks und Tastatureingaben kommen nicht an.");
        }
        if (!captureAllowed()) {
            out.add("Bildschirmaufnahme nicht freigegeben – keine Fenstertitel und Screenshots.");
        }
        return out;
    }

    @Override
    public void requireInputPermission() {
        if (!inputTrusted()) {
            throw new IllegalStateException("macOS: Die DevTools-App braucht die Berechtigung „Bedienungshilfen“ "
                    + "(Systemeinstellungen → Datenschutz & Sicherheit → Bedienungshilfen), sonst kommen Klicks und "
                    + "Tastatureingaben nicht an. Danach die App neu starten.");
        }
    }

    @Override
    public void requireCapturePermission() {
        if (!captureAllowed()) {
            throw new IllegalStateException("macOS: Die DevTools-App braucht die Berechtigung „Bildschirmaufnahme“ "
                    + "(Systemeinstellungen → Datenschutz & Sicherheit → Bildschirm- & Systemaudioaufnahme). Danach die "
                    + "App neu starten.");
        }
    }

    private boolean inputTrusted() {
        return (byte) Natives.call(axIsProcessTrusted, "AXIsProcessTrusted") != 0;
    }

    private boolean captureAllowed() {
        return cgPreflightScreenCapture == null
                || (byte) Natives.call(cgPreflightScreenCapture, "CGPreflightScreenCaptureAccess") != 0;
    }

    @Override
    public synchronized List<NativeWindow> windows() {
        MemorySegment list = (MemorySegment) Natives.call(cgWindowList, "CGWindowListCopyWindowInfo",
                ON_SCREEN_ONLY | EXCLUDE_DESKTOP_ELEMENTS, 0);
        if (list.address() == 0) {
            return List.of();
        }
        try (Arena arena = Arena.ofConfined()) {
            long count = (long) Natives.call(cfArrayGetCount, "CFArrayGetCount", list);
            List<NativeWindow> out = new ArrayList<>();
            MemorySegment number = arena.allocate(JAVA_LONG);
            MemorySegment rect = arena.allocate(JAVA_DOUBLE, 4);
            for (long i = 0; i < count; i++) {
                MemorySegment dict = (MemorySegment) Natives.call(cfArrayGetValueAtIndex, "CFArrayGetValueAtIndex", list, i);
                Long layer = number(dict, keyLayer, number);
                Long id = number(dict, keyNumber, number);
                Long pid = number(dict, keyOwnerPid, number);
                MemorySegment boundsDict = value(dict, keyBounds);
                // Layer 0 = normale Anwendungsfenster (keine Menüleiste, kein Dock, keine Overlays)
                if (layer == null || layer != 0 || id == null || pid == null || boundsDict.address() == 0
                        || (byte) Natives.call(cgRectFromDictionary, "CGRectMakeWithDictionaryRepresentation",
                        boundsDict, rect) == 0) {
                    continue;
                }
                Rectangle bounds = new Rectangle((int) Math.round(rect.getAtIndex(JAVA_DOUBLE, 0)),
                        (int) Math.round(rect.getAtIndex(JAVA_DOUBLE, 1)), (int) Math.round(rect.getAtIndex(JAVA_DOUBLE, 2)),
                        (int) Math.round(rect.getAtIndex(JAVA_DOUBLE, 3)));
                if (bounds.width <= 1 || bounds.height <= 1) {
                    continue;
                }
                out.add(new NativeWindow(id, pid, null, string(value(dict, keyName)), bounds, false));
            }
            return out;
        } finally {
            Natives.call(cfRelease, "CFRelease", list);
        }
    }

    private MemorySegment value(MemorySegment dict, MemorySegment key) {
        return (MemorySegment) Natives.call(cfDictionaryGetValue, "CFDictionaryGetValue", dict, key);
    }

    private Long number(MemorySegment dict, MemorySegment key, MemorySegment buffer) {
        MemorySegment n = value(dict, key);
        if (n.address() == 0
                || (byte) Natives.call(cfNumberGetValue, "CFNumberGetValue", n, (long) CF_NUMBER_SINT64, buffer) == 0) {
            return null;
        }
        return buffer.get(JAVA_LONG, 0);
    }

    private String string(MemorySegment str) {
        if (str.address() == 0) {
            return "";
        }
        long len = (long) Natives.call(cfStringGetLength, "CFStringGetLength", str);
        long size = (long) Natives.call(cfStringGetMaxSize, "CFStringGetMaximumSizeForEncoding", len,
                CF_STRING_ENCODING_UTF8) + 1;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(size);
            if ((byte) Natives.call(cfStringGetCString, "CFStringGetCString", str, buf, size, CF_STRING_ENCODING_UTF8) == 0) {
                return "";
            }
            return buf.getString(0, StandardCharsets.UTF_8);
        }
    }

    /** Fensternummern der eigenen Anzeige-Fenster (Rahmen, Hinweis) – einmal über die Fensterliste gefunden. */
    private final java.util.Map<java.awt.Window, Long> ownNumbers = new java.util.WeakHashMap<>();

    @Override
    public boolean canStackAbove() {
        return true;
    }

    /**
     * Sucht die Fensternummern der eigenen Fenster über die Fensterliste (eigene PID, gleiche Grenzen) und ordnet sie
     * per AppKit direkt über das Ziel ({@link MacZOrder}) – nur, wenn sie dort nicht schon zusammenhängend liegen.
     * Fenster, die noch nicht auf dem Bildschirm sind, haben keine Nummer: dann {@code false}.
     */
    @Override
    public boolean stackAbove(List<java.awt.Window> overlays, NativeWindow target) {
        List<NativeWindow> all = windows(); // von vorn nach hinten
        java.util.Set<Long> own = new java.util.HashSet<>();
        for (java.awt.Window overlay : overlays) {
            Long n = ownNumber(overlay, all);
            if (n != null) {
                own.add(n);
            }
        }
        int at = -1;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id() == target.id()) {
                at = i;
                break;
            }
        }
        if (own.size() == overlays.size() && at >= own.size()) {
            boolean placed = true;
            for (int i = at - own.size(); i < at; i++) {
                placed &= own.contains(all.get(i).id());
            }
            if (placed) {
                return true; // liegen schon direkt vor dem Ziel
            }
        }
        own.forEach(n -> MacZOrder.stackAbove(n, target.id()));
        return own.size() == overlays.size();
    }

    /** Fensternummer eines eigenen Fensters – gemerkt, sobald es einmal in der Fensterliste auftaucht. */
    private Long ownNumber(java.awt.Window overlay, List<NativeWindow> all) {
        synchronized (ownNumbers) {
            Long known = ownNumbers.get(overlay);
            if (known != null) {
                return known;
            }
        }
        Rectangle want = overlay.getBounds();
        long self = ProcessHandle.current().pid();
        Long n = all.stream()
                .filter(w -> w.pid() == self && Math.abs(w.bounds().x - want.x) <= 1
                        && Math.abs(w.bounds().y - want.y) <= 1 && Math.abs(w.bounds().width - want.width) <= 1
                        && Math.abs(w.bounds().height - want.height) <= 1)
                .map(NativeWindow::id).findFirst().orElse(null);
        if (n != null) {
            synchronized (ownNumbers) {
                ownNumbers.put(overlay, n);
            }
        }
        return n;
    }

    /** Die Fensterliste ist von vorn nach hinten sortiert: das erste normale Fenster liegt im Vordergrund. */
    @Override
    public OptionalLong foreground() {
        List<NativeWindow> all = windows();
        return all.isEmpty() ? OptionalLong.empty() : OptionalLong.of(all.getFirst().id());
    }

    @Override
    public synchronized void activate(long id) {
        NativeWindow w = window(id).orElseThrow(() -> new IllegalStateException("Fenster " + id + " existiert nicht mehr."));
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cls = (MemorySegment) Natives.call(objcGetClass, "objc_getClass",
                    arena.allocateFrom("NSRunningApplication"));
            MemorySegment byPid = (MemorySegment) Natives.call(selRegisterName, "sel_registerName",
                    arena.allocateFrom("runningApplicationWithProcessIdentifier:"));
            MemorySegment activate = (MemorySegment) Natives.call(selRegisterName, "sel_registerName",
                    arena.allocateFrom("activateWithOptions:"));
            MemorySegment app = (MemorySegment) Natives.call(msgSendPid, "objc_msgSend", cls, byPid, (int) w.pid());
            if (app.address() != 0) {
                Natives.call(msgSendLong, "objc_msgSend", app, activate, ACTIVATE_ALL_WINDOWS_IGNORING_OTHER_APPS);
            }
        }
        raise(w);
    }

    /** Hebt genau dieses Fenster der Anwendung nach vorn (sonst käme nur ihr zuletzt aktives). */
    private void raise(NativeWindow w) {
        if (axGetWindow == null || !inputTrusted()) {
            return;
        }
        MemorySegment app = (MemorySegment) Natives.call(axCreateApplication, "AXUIElementCreateApplication", (int) w.pid());
        if (app.address() == 0) {
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            if ((int) Natives.call(axCopyAttribute, "AXUIElementCopyAttributeValue", app, axWindows, out) != 0) {
                return;
            }
            MemorySegment windows = out.get(ADDRESS, 0);
            try {
                long count = (long) Natives.call(cfArrayGetCount, "CFArrayGetCount", windows);
                MemorySegment wid = arena.allocate(JAVA_INT);
                for (long i = 0; i < count; i++) {
                    MemorySegment el = (MemorySegment) Natives.call(cfArrayGetValueAtIndex, "CFArrayGetValueAtIndex",
                            windows, i);
                    if ((int) Natives.call(axGetWindow, "_AXUIElementGetWindow", el, wid) == 0
                            && Integer.toUnsignedLong(wid.get(JAVA_INT, 0)) == w.id()) {
                        Natives.call(axPerformAction, "AXUIElementPerformAction", el, axRaise);
                        return;
                    }
                }
            } finally {
                Natives.call(cfRelease, "CFRelease", windows);
            }
        } finally {
            Natives.call(cfRelease, "CFRelease", app);
        }
    }
}
