package systems.grebe.devtools.mcp.modules.window.cursor.macos;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import com.sun.jna.Callback;
import com.sun.jna.Function;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;

/**
 * JNA-Bindungen für macOS: ObjC-Runtime, CoreGraphics, CoreFoundation, libdispatch – und die Regel, dass AppKit nur
 * auf dem Hauptthread angesprochen werden darf ({@link #onMain}). {@code objc_msgSend} wird nicht variadisch
 * aufgerufen (Pflicht auf arm64).
 */
final class MacNatives {

    private static final long TIMEOUT_SECONDS = 5;

    private MacNatives() {
    }

    /** {@code CGPoint}/{@code NSPoint} als Wert. */
    @Structure.FieldOrder({"x", "y"})
    public static class CGPoint extends Structure implements Structure.ByValue {
        public double x;
        public double y;

        public CGPoint() {
        }

        CGPoint(double x, double y) {
            this.x = x;
            this.y = y;
        }
    }

    /** {@code CGSize}/{@code NSSize} als Wert. */
    @Structure.FieldOrder({"width", "height"})
    public static class CGSize extends Structure implements Structure.ByValue {
        public double width;
        public double height;

        public CGSize() {
        }

        CGSize(double width, double height) {
            this.width = width;
            this.height = height;
        }
    }

    /** {@code CGRect}/{@code NSRect}: Ursprung und Größe flach – gleiches Speicherbild wie die verschachtelte Form. */
    @Structure.FieldOrder({"x", "y", "width", "height"})
    public static class CGRect extends Structure {
        public double x;
        public double y;
        public double width;
        public double height;

        public CGRect() {
        }

        CGRect(double x, double y, double width, double height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        boolean contains(double px, double py) {
            return px >= x && py >= y && px < x + width && py < y + height;
        }

        /** Als Wert übergeben/zurückgegeben. */
        public static class ByValue extends CGRect implements Structure.ByValue {
            public ByValue() {
            }

            ByValue(double x, double y, double width, double height) {
                super(x, y, width, height);
            }
        }
    }

    interface ObjC extends Library {
        ObjC INSTANCE = Native.load("objc", ObjC.class);

        Pointer objc_getClass(String name);

        Pointer sel_registerName(String name);
    }

    interface CoreGraphics extends Library {
        CoreGraphics INSTANCE = Native.load("CoreGraphics", CoreGraphics.class);

        int kCGWindowListOptionOnScreenOnly = 1;
        int kCGWindowListExcludeDesktopElements = 16;

        int CGMainDisplayID();

        CGRect.ByValue CGDisplayBounds(int display);

        Pointer CGEventCreateMouseEvent(Pointer source, int type, CGPoint position, int button);

        /** Nicht variadisch (ab macOS 10.13) – anders als {@code CGEventCreateScrollWheelEvent}. */
        Pointer CGEventCreateScrollWheelEvent2(Pointer source, int units, int wheelCount, int wheel1, int wheel2,
                                               int wheel3);

        void CGEventSetLocation(Pointer event, CGPoint location);

        void CGEventSetIntegerValueField(Pointer event, int field, long value);

        void CGEventPostToPid(int pid, Pointer event);

        Pointer CGWindowListCopyWindowInfo(int option, int relativeToWindow);

        Pointer CGEventCreateKeyboardEvent(Pointer source, short virtualKey, boolean keyDown);

        /** {@code UniChar} sind 16 Bit – daher {@code short[]}, nicht {@code char[]} (das wäre {@code wchar_t}). */
        void CGEventKeyboardSetUnicodeString(Pointer event, long length, short[] string);

        void CGEventSetFlags(Pointer event, long flags);

        boolean CGRectMakeWithDictionaryRepresentation(Pointer dict, CGRect rect);
    }

    interface CoreFoundation extends Library {
        CoreFoundation INSTANCE = Native.load("CoreFoundation", CoreFoundation.class);

        int kCFNumberIntType = 9;

        long CFArrayGetCount(Pointer array);

        Pointer CFArrayGetValueAtIndex(Pointer array, long index);

        Pointer CFDictionaryGetValue(Pointer dict, Pointer key);

        boolean CFNumberGetValue(Pointer number, int type, IntByReference value);

        void CFRelease(Pointer object);
    }

    interface ApplicationServices extends Library {
        ApplicationServices INSTANCE = Native.load("ApplicationServices", ApplicationServices.class);

        boolean AXIsProcessTrusted();
    }

    interface Dispatch extends Library {
        Dispatch INSTANCE = Native.load("System", Dispatch.class);

        void dispatch_async_f(Pointer queue, Pointer context, DispatchFunction work);

        int pthread_main_np();
    }

    interface DispatchFunction extends Callback {
        void invoke(Pointer context);
    }

    private static final Function MSG_SEND = NativeLibrary.getInstance("objc").getFunction("objc_msgSend");
    private static final Pointer MAIN_QUEUE = NativeLibrary.getInstance("System")
            .getGlobalVariableAddress("_dispatch_main_q");
    /** Laufende Callbacks fest referenziert, bis AppKit sie ausgeführt hat. */
    private static final Set<DispatchFunction> PENDING = ConcurrentHashMap.newKeySet();

    /** Globale {@code CFStringRef}-Konstante aus CoreGraphics, z.B. {@code kCGWindowOwnerPID}. */
    static Pointer cgConstant(String name) {
        return NativeLibrary.getInstance("CoreGraphics").getGlobalVariableAddress(name).getPointer(0);
    }

    static Pointer cls(String name) {
        Pointer c = ObjC.INSTANCE.objc_getClass(name);
        if (c == null) {
            throw new IllegalStateException("ObjC-Klasse " + name + " nicht gefunden");
        }
        return c;
    }

    /** {@code objc_msgSend(receiver, selector, args...)} mit Zeiger-Ergebnis (bei {@code void}-Methoden bedeutungslos). */
    static Pointer send(Pointer receiver, String selector, Object... args) {
        Object[] all = new Object[args.length + 2];
        all[0] = receiver;
        all[1] = ObjC.INSTANCE.sel_registerName(selector);
        System.arraycopy(args, 0, all, 2, args.length);
        return MSG_SEND.invokePointer(all);
    }

    /** Höhe des Hauptbildschirms in Punkten – für die Umrechnung zwischen Cocoa (y nach oben) und global (y nach unten). */
    static double primaryHeight() {
        return CoreGraphics.INSTANCE.CGDisplayBounds(CoreGraphics.INSTANCE.CGMainDisplayID()).height;
    }

    /** Führt {@code work} auf dem AppKit-Hauptthread aus und wartet auf das Ergebnis. */
    static <T> T onMain(Supplier<T> work) {
        return await(submit(work));
    }

    /** Stellt {@code work} in die Main-Queue; auf dem Hauptthread selbst sofort ausgeführt. */
    static <T> CompletableFuture<T> submit(Supplier<T> work) {
        CompletableFuture<T> result = new CompletableFuture<>();
        if (Dispatch.INSTANCE.pthread_main_np() != 0) {
            try {
                result.complete(work.get());
            } catch (RuntimeException | Error e) {
                result.completeExceptionally(e);
            }
            return result;
        }
        DispatchFunction[] self = new DispatchFunction[1];
        self[0] = context -> {
            try {
                result.complete(work.get());
            } catch (RuntimeException | Error e) {
                result.completeExceptionally(e);
            } finally {
                PENDING.remove(self[0]);
            }
        };
        PENDING.add(self[0]);
        Dispatch.INSTANCE.dispatch_async_f(MAIN_QUEUE, Pointer.NULL, self[0]);
        return result;
    }

    static <T> T await(CompletableFuture<T> result) {
        try {
            return result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new IllegalStateException("AppKit: " + e.getCause().getMessage(), e.getCause());
        } catch (TimeoutException e) {
            throw new IllegalStateException("Der AppKit-Hauptthread reagiert nicht – läuft die App ohne Oberfläche?", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Unterbrochen", e);
        }
    }
}
