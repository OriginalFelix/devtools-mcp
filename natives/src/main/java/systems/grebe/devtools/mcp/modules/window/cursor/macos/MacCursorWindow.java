package systems.grebe.devtools.mcp.modules.window.cursor.macos;

import java.awt.Point;
import java.util.concurrent.CompletableFuture;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;

import systems.grebe.devtools.mcp.modules.window.cursor.CursorImage;

import static systems.grebe.devtools.mcp.modules.window.cursor.macos.MacNatives.cls;
import static systems.grebe.devtools.mcp.modules.window.cursor.macos.MacNatives.send;

/**
 * Fenster eines macOS-Zeigers: randloses, durchsichtiges {@code NSWindow} auf Höhe der Bildschirmschoner-Ebene, das
 * Mausereignisse durchlässt, auf allen Spaces und über Vollbild-Apps liegt und nie Fokus nimmt. Inhalt ist ein
 * {@code NSImageView} mit dem Zeigerbild in doppelter Auflösung (scharf auf Retina).
 */
final class MacCursorWindow {

    private static final double IMAGE_SCALE = 2;
    private static final long NS_WINDOW_STYLE_BORDERLESS = 0;
    private static final long NS_BACKING_STORE_BUFFERED = 2;
    private static final long NS_SCREEN_SAVER_WINDOW_LEVEL = 1000;
    private static final long NS_WINDOW_SHARING_NONE = 0;
    /** canJoinAllSpaces | stationary | ignoresCycle | fullScreenAuxiliary */
    private static final long COLLECTION_BEHAVIOR = 1 | 16 | 64 | 256;

    private final Point hotspotPoints;
    private final double sizePoints;
    private volatile Pointer window;

    MacCursorWindow(int x, int y) {
        CursorImage img = CursorImage.render(IMAGE_SCALE);
        hotspotPoints = new Point((int) Math.round(img.hotspot().x / IMAGE_SCALE),
                (int) Math.round(img.hotspot().y / IMAGE_SCALE));
        sizePoints = img.width() / IMAGE_SCALE;
        byte[] png = img.png();
        CompletableFuture<Pointer> created = MacNatives.submit(() -> createWindow(png, x, y));
        try {
            window = MacNatives.await(created);
        } catch (IllegalStateException e) {
            // kommt das Fenster doch noch (Zeitüberschreitung), gleich wieder schließen – läuft dann auf dem Hauptthread
            created.thenAccept(MacCursorWindow::destroy);
            throw e;
        }
    }

    void moveTo(int x, int y) {
        Pointer w = requireOpen();
        MacNatives.CGPoint origin = origin(x, y);
        MacNatives.onMain(() -> {
            send(w, "setFrameOrigin:", origin);
            return null;
        });
    }

    /** Fensternummer ({@code kCGWindowNumber}) – um das eigene Fenster bei der Suche zu überspringen. */
    long windowNumber() {
        Pointer w = requireOpen();
        return MacNatives.onMain(() -> Pointer.nativeValue(send(w, "windowNumber")));
    }

    void close() {
        Pointer w;
        synchronized (this) {
            w = window;
            window = null;
        }
        if (w != null) {
            MacNatives.onMain(() -> {
                destroy(w);
                return null;
            });
        }
    }

    private Pointer requireOpen() {
        Pointer w = window;
        if (w == null) {
            throw new IllegalStateException("Der Zeiger ist bereits zerstört.");
        }
        return w;
    }

    /** Läuft auf dem Hauptthread. */
    private static void destroy(Pointer w) {
        send(w, "orderOut:", Pointer.NULL);
        send(w, "close");
        send(w, "release");
    }

    /** Läuft auf dem Hauptthread. */
    private Pointer createWindow(byte[] png, int x, int y) {
        send(cls("NSApplication"), "sharedApplication");
        Memory bytes = new Memory(png.length);
        bytes.write(0, png, 0, png.length);
        Pointer data = send(cls("NSData"), "dataWithBytes:length:", bytes, (long) png.length);
        Pointer image = send(send(cls("NSImage"), "alloc"), "initWithData:", data);
        if (image == null) {
            throw new IllegalStateException("NSImage aus PNG nicht erzeugt");
        }
        send(image, "setSize:", new MacNatives.CGSize(sizePoints, sizePoints));

        MacNatives.CGPoint origin = origin(x, y);
        Pointer view = send(send(cls("NSImageView"), "alloc"), "initWithFrame:",
                new MacNatives.CGRect.ByValue(0, 0, sizePoints, sizePoints));
        send(view, "setImage:", image);
        send(image, "release");

        Pointer w = send(send(cls("NSWindow"), "alloc"), "initWithContentRect:styleMask:backing:defer:",
                new MacNatives.CGRect.ByValue(origin.x, origin.y, sizePoints, sizePoints),
                NS_WINDOW_STYLE_BORDERLESS, NS_BACKING_STORE_BUFFERED, 0);
        if (w == null) {
            send(view, "release");
            throw new IllegalStateException("NSWindow nicht erzeugt");
        }
        send(w, "setReleasedWhenClosed:", 0);
        send(w, "setOpaque:", 0);
        send(w, "setBackgroundColor:", send(cls("NSColor"), "clearColor"));
        send(w, "setHasShadow:", 0);
        send(w, "setIgnoresMouseEvents:", 1);
        send(w, "setLevel:", NS_SCREEN_SAVER_WINDOW_LEVEL);
        send(w, "setCollectionBehavior:", COLLECTION_BEHAVIOR);
        send(w, "setSharingType:", NS_WINDOW_SHARING_NONE); // nicht in Bildschirmaufnahmen
        send(w, "setContentView:", view);
        send(view, "release");
        send(w, "orderFrontRegardless");
        return w;
    }

    /**
     * Fensterursprung in Cocoa-Koordinaten (unten links am Hauptbildschirm, y nach oben) für die Zeigerspitze an
     * {@code (x, y)} in globalen Koordinaten (oben links, y nach unten).
     */
    private MacNatives.CGPoint origin(int x, int y) {
        double top = y - hotspotPoints.y;
        return new MacNatives.CGPoint(x - hotspotPoints.x, MacNatives.primaryHeight() - top - sizePoints);
    }
}
