package systems.grebe.devtools.mcp.modules.window;

import java.awt.Point;
import java.awt.Rectangle;

import systems.grebe.devtools.mcp.modules.window.platform.NativeWindow;

/**
 * Eine laufende Eingabe in genau ein Fenster. Vor jedem Schritt wird geprüft, dass der Nutzer nicht eingegriffen hat
 * ({@link UserPresenceMonitor}) und das Fenster noch im Vordergrund liegt; Mauspositionen müssen innerhalb der
 * aktuellen Fenstergrenzen liegen.
 */
final class InputGuard {

    private final WindowSupport support;
    private final InputDevice device;
    private final UserPresenceMonitor presence;
    private NativeWindow window;
    private final double scale;
    private final boolean requireForeground;

    InputGuard(WindowSupport support, InputDevice device, UserPresenceMonitor presence, NativeWindow window, double scale) {
        this(support, device, presence, window, scale, true);
    }

    /** @param requireForeground {@code false} bei eigenem Zeiger: Mauseingaben brauchen den Vordergrund nicht */
    InputGuard(WindowSupport support, InputDevice device, UserPresenceMonitor presence, NativeWindow window, double scale,
               boolean requireForeground) {
        this.support = support;
        this.device = device;
        this.presence = presence;
        this.window = window;
        this.scale = scale;
        this.requireForeground = requireForeground;
    }

    NativeWindow window() {
        return window;
    }

    /**
     * Zusatz für die Antwort, wenn die Eingabe ohne Vordergrund lief (eigener Zeiger bzw. eigene Tastatur): die
     * Ereignisse gehen direkt an den Prozess, ob er sie verarbeitet, ist ungeprüft – manche Programme verwerfen sie im
     * Hintergrund. Leer bei Eingaben ins Vordergrundfenster.
     */
    String unverified() {
        return requireForeground ? "" : " Wirkung nicht geprüft – im Hintergrund verwerfen manche Programme Eingaben; "
                + "mit window_screenshot kontrollieren.";
    }

    InputDevice device() {
        return device;
    }

    /**
     * Rechnet Bildpixel des letzten Screenshots in Bildschirmkoordinaten um und prüft, dass der Punkt im Fenster liegt
     * (Grenzen neu gelesen, falls das Fenster verschoben wurde).
     */
    Point toScreen(int imageX, int imageY) {
        window = support.refresh(window);
        Rectangle b = window.bounds();
        Point p = new Point(b.x + (int) Math.round(imageX / scale), b.y + (int) Math.round(imageY / scale));
        if (!b.contains(p)) {
            throw new IllegalArgumentException("Punkt (" + imageX + ", " + imageY + ") liegt außerhalb von Fenster "
                    + window.hexId() + " (Bild " + (int) Math.round(b.width * scale) + "×"
                    + (int) Math.round(b.height * scale) + " px). Koordinaten beziehen sich auf den letzten "
                    + "window_screenshot dieses Fensters.");
        }
        return p;
    }

    /** Vor jedem Schritt: Nutzer hat nicht eingegriffen, Fenster liegt noch vorn (sofern verlangt). */
    void checkpoint() {
        WindowSupport.Settings s = support.settings();
        presence.check(device.pointer(), s.cooldown(), s.abortOnMouseMove());
        if (requireForeground && !support.isForeground(window)) {
            throw new FocusLostException("Fenster " + window.hexId() + " („" + window.title() + "“) ist nicht mehr im "
                    + "Vordergrund – Eingabe abgebrochen. Mit window_screenshot nachsehen, was sich geöffnet hat.");
        }
    }

    void move(Point p) {
        checkpoint();
        device.move(p.x, p.y);
        presence.expect(p);
    }

    /** Ein Ereignis ohne Mausbewegung (Taste, Maustaste, Rad) nach erfolgreicher Prüfung. */
    void step(Runnable event) {
        checkpoint();
        event.run();
    }

    /** Der Fokus ging während der Eingabe verloren. */
    static final class FocusLostException extends IllegalStateException {
        FocusLostException(String message) {
            super(message);
        }
    }
}
