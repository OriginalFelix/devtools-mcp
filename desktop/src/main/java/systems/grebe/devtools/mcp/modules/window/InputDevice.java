package systems.grebe.devtools.mcp.modules.window;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.datatransfer.Transferable;
import java.awt.image.BufferedImage;
import java.util.Set;

import systems.grebe.devtools.mcp.modules.window.platform.NativeWindow;

/**
 * Maus, Tastatur, Zwischenablage und Bildschirm – in Java-Bildschirmkoordinaten. Produktiv {@link RobotInputDevice};
 * Tests verwenden eine Attrappe.
 */
interface InputDevice {

    /** Aktuelle Mausposition; {@code null}, wenn unbekannt. */
    Point pointer();

    /**
     * Position der Maus des Nutzers, die der Not-Aus ({@link UserPresenceMonitor}) beobachtet; {@code null}, wenn das
     * Gerät sie nicht benutzt (eigener Zeiger – dann stört eine Mausbewegung des Nutzers nicht, und die Zeiger mehrerer
     * KIs dürfen nicht als Bewegung des Nutzers gelten).
     */
    default Point userPointer() {
        return independentPointer() ? null : pointer();
    }

    void move(int x, int y);

    /** Maustasten als {@link java.awt.event.InputEvent}{@code .BUTTON*_DOWN_MASK}. */
    void press(int buttons);

    void release(int buttons);

    /** Mausrad in Rasten; positiv = nach unten. */
    void wheel(int notches);

    void keyPress(int keyCode);

    void keyRelease(int keyCode);

    void pause(int millis);

    BufferedImage capture(Rectangle bounds);

    /** Inhalt der Zwischenablage oder {@code null}. */
    Transferable clipboard();

    void clipboard(Transferable content);

    /** Vor jeder Aktion: in welches Fenster die Eingaben gehen und welche Prozesse dabei erlaubt sind. */
    default void target(NativeWindow window, Set<Long> allowedPids) {
    }

    /** Bild des Fensters; Standard: Bildschirmausschnitt seiner Grenzen. */
    default BufferedImage capture(NativeWindow window) {
        return capture(window.bounds());
    }

    /** Ob {@link #typeText} Zeichen direkt (ohne Tastaturlayout und Zwischenablage) eingeben kann. */
    default boolean typesDirectly() {
        return false;
    }

    /**
     * Gibt Text direkt in den Fokus ein.
     *
     * @return ob der Text nachweislich angekommen ist; {@code false}, wenn nur Ereignisse verschickt wurden
     */
    default boolean typeText(String text) {
        throw new UnsupportedOperationException("Direkte Zeicheneingabe nicht verfügbar");
    }

    /**
     * Ob die Maus des Geräts unabhängig vom Mauszeiger des Nutzers ist (eigener Zeiger). Dann brauchen Mauseingaben
     * weder den Vordergrund noch ruhige Hände des Nutzers – Tastatur-Eingaben schon.
     */
    default boolean independentPointer() {
        return false;
    }

    /**
     * Ob die Tastatur des Geräts unabhängig von der des Nutzers ist (eigene Tastatur des eigenen Zeigers). Dann brauchen
     * Tastatur-Eingaben den Vordergrund nicht; Text geht über {@link #typeText}.
     */
    default boolean independentKeyboard() {
        return false;
    }

    /** {@code count} Klicks in Folge (2 = Doppelklick) an der aktuellen Position. */
    default void click(int buttons, int count) {
        for (int i = 0; i < count; i++) {
            press(buttons);
            release(buttons);
        }
    }

    /** Die KI gibt die Kontrolle ab (Bindung aufgehoben, Abbruch) – z.B. Anzeige ausblenden. */
    default void release() {
    }
}
