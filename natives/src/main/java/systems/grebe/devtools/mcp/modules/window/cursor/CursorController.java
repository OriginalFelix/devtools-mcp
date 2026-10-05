package systems.grebe.devtools.mcp.modules.window.cursor;

import java.awt.Point;
import java.util.List;
import java.util.Set;

/**
 * Erzeugt, bewegt und zerstört zweite Mauszeiger, klickt und tippt mit ihnen – ohne Maus und Tastatur des Nutzers zu
 * benutzen. Mauseingaben gehen an das Fenster unter dem Zeiger; solange eine Taste gehalten wird, an das Fenster, in
 * dem sie gedrückt wurde (Ziehen).
 *
 * <p>Jeder Zeiger hat eine eigene Tastatur mit eigenem Fokus: Tastatureingaben gehen an das Element, das er zuletzt
 * angeklickt hat (sonst an das Fenster unter ihm) – unabhängig davon, welches Fenster beim Nutzer den Fokus hat.
 *
 * <p>Koordinaten sind Bildschirmkoordinaten des jeweiligen Systems: Windows physische Pixel, macOS Punkte mit Ursprung
 * oben links am Hauptbildschirm, X11 Pixel des Root-Fensters. Implementierungen sind threadsicher.
 */
public interface CursorController extends AutoCloseable {

    /** Name für Ausgaben, z.B. {@code Windows (Layered-Fenster, PostMessage)}. */
    String name();

    /** Erzeugt einen Zeiger in {@link CursorImage#ACCENT} mit der Spitze an {@code (x, y)}. */
    default VirtualCursor create(int x, int y) {
        return create(x, y, CursorImage.ACCENT);
    }

    /**
     * Erzeugt einen Zeiger in {@code color} mit der Spitze an {@code (x, y)} – z.B. je KI eine eigene Farbe. Wo das
     * System den Zeiger selbst zeichnet (X11), bleibt die Farbe ohne Wirkung.
     */
    VirtualCursor create(int x, int y, java.awt.Color color);

    /** Zerstört den Zeiger; noch gehaltene Tasten werden vorher losgelassen. Mehrfacher Aufruf ist erlaubt. */
    void destroy(VirtualCursor cursor);

    /** Bewegt die Zeigerspitze; bei gehaltener Taste ist das ein Ziehen. */
    void move(VirtualCursor cursor, int x, int y);

    Point position(VirtualCursor cursor);

    void press(VirtualCursor cursor, MouseButton button);

    void release(VirtualCursor cursor, MouseButton button);

    /** Gerade gehaltene Tasten dieses Zeigers. */
    Set<MouseButton> pressed(VirtualCursor cursor);

    /** Klickt {@code count}-mal (2 = Doppelklick, 3 = Dreifachklick) an der aktuellen Position. */
    void click(VirtualCursor cursor, MouseButton button, int count);

    default void click(VirtualCursor cursor, MouseButton button) {
        click(cursor, button, 1);
    }

    /** Bewegt den Zeiger und klickt dort. */
    default void clickAt(VirtualCursor cursor, int x, int y, MouseButton button, int count) {
        move(cursor, x, y);
        click(cursor, button, count);
    }

    /** Mausrad in Rasten an der aktuellen Position; positiv = nach unten. */
    void scroll(VirtualCursor cursor, int notches);

    /** Tippt Text in den Fokus dieses Zeigers. */
    void type(VirtualCursor cursor, String text);

    /** Drückt eine Taste ({@link java.awt.event.KeyEvent}{@code .VK_*}) der Tastatur dieses Zeigers. */
    void keyPress(VirtualCursor cursor, int keyCode);

    void keyRelease(VirtualCursor cursor, int keyCode);

    /** Gerade gehaltene Tastaturtasten dieses Zeigers. */
    Set<Integer> pressedKeys(VirtualCursor cursor);

    /** Alle noch existierenden Zeiger dieses Controllers. */
    List<VirtualCursor> cursors();

    /** Zerstört alle Zeiger dieses Controllers. Der Controller bleibt nutzbar. */
    @Override
    void close();
}
