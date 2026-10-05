package systems.grebe.devtools.mcp.modules.window;

import java.awt.Color;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.datatransfer.Transferable;
import java.awt.event.InputEvent;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

import systems.grebe.devtools.mcp.modules.window.cursor.CursorController;
import systems.grebe.devtools.mcp.modules.window.cursor.CursorImage;
import systems.grebe.devtools.mcp.modules.window.cursor.MouseButton;
import systems.grebe.devtools.mcp.modules.window.cursor.VirtualCursor;
import systems.grebe.devtools.mcp.modules.window.platform.NativeWindow;
import systems.grebe.devtools.mcp.modules.window.platform.WindowSystem;

/**
 * Maus und/oder Tastatur über einen eigenen, zweiten Zeiger ({@link CursorController}) – getrennt einstellbar. Was
 * eigen ist, lässt Maus bzw. Tastatur des Nutzers unberührt, der Nutzer kann parallel weiterarbeiten; der Rest, dazu
 * Bildschirm und Zwischenablage, geht an das echte Gerät ({@link RobotInputDevice}).
 *
 * <p>Die eigene Tastatur hat einen eigenen Fokus: das Element, das der eigene Zeiger zuletzt angeklickt hat, sonst das
 * fokussierte Element des Fensters unter ihm. Ohne eigene Maus steht der Zeiger dafür in der Mitte des Zielfensters
 * (dort, wo bei echten Klicks ohnehin das aktive Fenster liegt).
 *
 * <p>Weil das Zielfenster mit eigenem Zeiger nicht nach vorn geholt wird, prüft jeder Klick (und Tastatur-Eingaben vor
 * dem ersten Klick), dass an der Stelle kein fremdes Fenster darüber liegt. Wechselt das Zielfenster, beginnt ein neuer
 * Zeiger (ohne den Fokus des alten Fensters). Nach {@link #IDLE} ohne Aktion verschwindet der Zeiger von selbst – auch
 * wenn die KI vergisst, die Bindung aufzuheben.
 *
 * <p>Threadsicher (alle Methoden synchronisiert), weil das Ausblenden nach Leerlauf auf einem eigenen Thread läuft.
 */
final class VirtualCursorInputDevice implements InputDevice, AutoCloseable {

    private final InputDevice real;
    private final Supplier<CursorController> cursors;
    private final Function<Point, Point> toNative;
    private final WindowSystem windows;
    private final boolean ownPointer;
    private final boolean ownKeyboard;
    private final long self = ProcessHandle.current().pid();
    private final Duration idle;
    private final Color color;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "virtual-cursor-idle");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> idleTask;

    /** Nach so langer Untätigkeit wird der eigene Zeiger zerstört. */
    static final Duration IDLE = Duration.ofSeconds(60);

    // unter dem Eingabe-Lock von WindowSupport
    private VirtualCursor cursor;
    private Point position;
    private Set<Long> allowedPids = Set.of();
    private NativeWindow window;
    /** Ob der Zeiger seit dem Erzeugen geklickt hat – vorher hat seine Tastatur noch keinen eigenen Fokus. */
    private boolean clicked;

    /**
     * @param real        echte Maus und Tastatur, Bildschirm, Zwischenablage
     * @param toNative    Java-Bildschirmkoordinaten → Koordinaten des Controllers (z.B. physische Pixel unter Windows)
     * @param ownPointer  eigene Maus statt der echten
     * @param ownKeyboard eigene Tastatur statt der echten
     */
    VirtualCursorInputDevice(InputDevice real, Supplier<CursorController> cursors, Function<Point, Point> toNative,
                             WindowSystem windows, boolean ownPointer, boolean ownKeyboard) {
        this(real, cursors, toNative, windows, ownPointer, ownKeyboard, IDLE);
    }

    /** @param idle nach so langer Untätigkeit verschwindet der Zeiger */
    VirtualCursorInputDevice(InputDevice real, Supplier<CursorController> cursors, Function<Point, Point> toNative,
                             WindowSystem windows, boolean ownPointer, boolean ownKeyboard, Duration idle) {
        this(real, cursors, toNative, windows, ownPointer, ownKeyboard, idle, CursorImage.ACCENT);
    }

    /** @param color Farbe des eigenen Zeigers – die Farbe der KI (siehe {@link AiColors}) */
    VirtualCursorInputDevice(InputDevice real, Supplier<CursorController> cursors, Function<Point, Point> toNative,
                             WindowSystem windows, boolean ownPointer, boolean ownKeyboard, Duration idle,
                             Color color) {
        this.color = color;
        this.idle = idle;
        this.real = real;
        this.cursors = cursors;
        this.toNative = toNative;
        this.windows = windows;
        this.ownPointer = ownPointer;
        this.ownKeyboard = ownKeyboard;
    }

    @Override
    public synchronized boolean independentPointer() {
        return ownPointer;
    }

    @Override
    public synchronized boolean independentKeyboard() {
        return ownKeyboard;
    }

    // --- Maus

    /** Mit eigener Maus deren Position – der Mauszeiger des Nutzers spielt dann keine Rolle. */
    @Override
    public synchronized Point pointer() {
        if (!ownPointer) {
            return real.pointer();
        }
        return position == null ? null : new Point(position);
    }

    @Override
    public synchronized void move(int x, int y) {
        if (ownPointer) {
            place(x, y);
        } else {
            real.move(x, y);
        }
    }

    @Override
    public synchronized void press(int buttons) {
        if (!ownPointer) {
            real.press(buttons);
            return;
        }
        requireUnobstructed();
        cursors.get().press(requireCursor(), button(buttons));
        clicked = true;
        touch();
    }

    @Override
    public synchronized void release(int buttons) {
        if (ownPointer) {
            cursors.get().release(requireCursor(), button(buttons));
        } else {
            real.release(buttons);
        }
    }

    @Override
    public synchronized void click(int buttons, int count) {
        if (!ownPointer) {
            real.click(buttons, count);
            return;
        }
        requireUnobstructed();
        cursors.get().click(requireCursor(), button(buttons), count);
        clicked = true;
        touch();
    }

    @Override
    public synchronized void wheel(int notches) {
        if (!ownPointer) {
            real.wheel(notches);
            return;
        }
        requireUnobstructed();
        cursors.get().scroll(requireCursor(), notches);
        touch();
    }

    // --- Tastatur

    @Override
    public synchronized void keyPress(int keyCode) {
        if (ownKeyboard) {
            cursors.get().keyPress(keyboardCursor(), keyCode);
        } else {
            real.keyPress(keyCode);
        }
    }

    @Override
    public synchronized void keyRelease(int keyCode) {
        if (!ownKeyboard) {
            real.keyRelease(keyCode);
            return;
        }
        VirtualCursor c = cursor;
        if (c != null && c.isOpen()) {
            cursors.get().keyRelease(c, keyCode);
        }
    }

    @Override
    public synchronized boolean typesDirectly() {
        return ownKeyboard || real.typesDirectly();
    }

    @Override
    public synchronized void typeChar(char c) {
        if (ownKeyboard) {
            cursors.get().type(keyboardCursor(), String.valueOf(c));
        } else {
            real.typeChar(c);
        }
    }

    // --- Ziel und Kontrolle

    @Override
    public synchronized void target(NativeWindow target, Set<Long> pids) {
        if (window != null && window.id() != target.id() && cursor != null) {
            dropCursor(); // neues Fenster: der Fokus des alten gilt nicht mehr
        }
        window = target;
        allowedPids = Set.copyOf(pids);
        real.target(target, pids);
    }

    /** Zeiger zerstören – die KI gibt die Kontrolle ab. */
    @Override
    public synchronized void release() {
        dropCursor();
        real.release();
    }

    private void place(int x, int y) {
        Point at = toNative.apply(new Point(x, y));
        CursorController c = cursors.get();
        if (cursor == null || !cursor.isOpen()) {
            cursor = c.create(at.x, at.y, color);
        } else {
            c.move(cursor, at.x, at.y);
        }
        position = new Point(x, y);
        touch();
    }

    /** Leerlauf neu messen: jede Aktion mit dem Zeiger verschiebt sein Ausblenden. */
    private void touch() {
        if (idleTask != null) {
            idleTask.cancel(false);
        }
        idleTask = timer.schedule(this::idleTimeout, idle.toMillis(), TimeUnit.MILLISECONDS);
    }

    private synchronized void idleTimeout() {
        dropCursor();
    }

    /** Zerstört den Zeiger und beendet den Zeitgeber – die KI-Session ist vorbei. */
    @Override
    public synchronized void close() {
        dropCursor();
        timer.shutdownNow();
    }

    private void dropCursor() {
        if (idleTask != null) {
            idleTask.cancel(false);
            idleTask = null;
        }
        VirtualCursor c = cursor;
        cursor = null;
        position = null;
        clicked = false;
        if (c != null) {
            cursors.get().destroy(c);
        }
    }

    /**
     * Zeiger für die Tastatur: vor dem ersten Klick in die Mitte des Zielfensters, wo nichts Fremdes darüber liegen
     * darf (ohne eigenen Fokus geht die Eingabe an das Fenster unter dem Zeiger).
     */
    private VirtualCursor keyboardCursor() {
        if (cursor == null || !cursor.isOpen()) {
            if (window == null) {
                throw new IllegalStateException("Kein Zielfenster für die Tastatur.");
            }
            Rectangle b = window.bounds();
            place(b.x + b.width / 2, b.y + b.height / 2);
        }
        if (!clicked) {
            requireUnobstructed();
        }
        touch();
        return cursor;
    }

    private VirtualCursor requireCursor() {
        if (cursor == null || !cursor.isOpen()) {
            throw new IllegalStateException("Der eigene Zeiger wurde noch nicht positioniert.");
        }
        return cursor;
    }

    /**
     * Das vorderste Fenster an der Zeigerposition muss zu den erlaubten Prozessen gehören. Fenster dieser App
     * (Rahmen, Hinweis, Zeiger selbst) liegen zwar darüber, lassen Eingaben aber durch.
     */
    private void requireUnobstructed() {
        Point at = position;
        if (at == null) {
            throw new IllegalStateException("Der eigene Zeiger wurde noch nicht positioniert.");
        }
        for (NativeWindow w : windows.windows()) {
            if (w.minimized() || w.pid() == self || !w.bounds().contains(at)) {
                continue;
            }
            if (allowedPids.contains(w.pid())) {
                return;
            }
            throw new IllegalStateException("An dieser Stelle liegt ein anderes Fenster darüber („" + w.title()
                    + "“, PID " + w.pid() + ") – nicht geklickt, damit nichts in einem fremden Fenster landet. Den "
                    + "Nutzer bitten, es zur Seite zu schieben, oder mit window_screenshot das Fenster nach vorn holen.");
        }
    }

    private static MouseButton button(int mask) {
        if ((mask & InputEvent.BUTTON3_DOWN_MASK) != 0) {
            return MouseButton.RIGHT;
        }
        if ((mask & InputEvent.BUTTON2_DOWN_MASK) != 0) {
            return MouseButton.MIDDLE;
        }
        return MouseButton.LEFT;
    }

    // --- Bildschirm, Zwischenablage: immer das echte Gerät

    @Override
    public synchronized void pause(int millis) {
        real.pause(millis);
    }

    @Override
    public synchronized BufferedImage capture(Rectangle bounds) {
        return real.capture(bounds);
    }

    @Override
    public synchronized BufferedImage capture(NativeWindow window) {
        return real.capture(window);
    }

    @Override
    public synchronized Transferable clipboard() {
        return real.clipboard();
    }

    @Override
    public synchronized void clipboard(Transferable content) {
        real.clipboard(content);
    }
}
