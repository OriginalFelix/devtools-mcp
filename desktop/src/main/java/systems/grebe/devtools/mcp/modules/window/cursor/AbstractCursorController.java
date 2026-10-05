package systems.grebe.devtools.mcp.modules.window.cursor;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gemeinsamer Teil aller Controller: Griffe, Position, gehaltene Maus- und Tastaturtasten je Zeiger, Klickfolgen,
 * Aufräumen. Die
 * Unterklassen setzen nur die nativen Schritte um; sie werden unter der Sperre des Controllers aufgerufen.
 *
 * @param <C> nativer Zustand eines Zeigers (Fenster, Gerät, Ziel einer gehaltenen Taste …)
 */
public abstract class AbstractCursorController<C> implements CursorController {

    private static final int MAX_CLICKS = 3;

    private final Map<Long, Handle> cursors = new LinkedHashMap<>();
    private long nextId = 1;

    /** Erzeugt den nativen Zeiger mit der Spitze an {@code at}. */
    protected abstract C open(Point at);

    /** Zerstört den nativen Zeiger; gehaltene Tasten sind bereits losgelassen. */
    protected abstract void dispose(C cursor);

    /** Bewegt den nativen Zeiger; {@code held} = gerade gehaltene Tasten (nicht leer = Ziehen). */
    protected abstract void moveTo(C cursor, Point to, Set<MouseButton> held);

    /**
     * Drückt oder lässt eine Taste los.
     *
     * @param clickCount 1 beim einfachen Klick, 2 beim zweiten Klick eines Doppelklicks usw.
     * @param held       gehaltene Tasten nach diesem Schritt
     */
    protected abstract void button(C cursor, Point at, MouseButton button, boolean down, int clickCount,
                                   Set<MouseButton> held);

    /** Mausrad; positiv = nach unten. */
    protected abstract void wheel(C cursor, Point at, int notches, Set<MouseButton> held);

    /** Tippt Text in den Fokus des Zeigers (zuletzt angeklicktes Element, sonst das Fenster unter ihm). */
    protected abstract void typeText(C cursor, Point at, String text);

    /**
     * Drückt oder lässt eine Taste los ({@link java.awt.event.KeyEvent}{@code .VK_*}), im Fokus des Zeigers.
     *
     * @param heldKeys gehaltene Tasten nach diesem Schritt (Strg, Umschalt … gelten nur für dieses Ziel)
     */
    protected abstract void key(C cursor, Point at, int keyCode, boolean down, Set<Integer> heldKeys);

    @Override
    public synchronized VirtualCursor create(int x, int y) {
        Point at = new Point(x, y);
        C nativeCursor = open(at);
        Handle h = new Handle(nextId++, nativeCursor, at);
        cursors.put(h.id, h);
        return h;
    }

    @Override
    public synchronized void destroy(VirtualCursor cursor) {
        Handle h = own(cursor);
        if (!h.open) {
            return;
        }
        try {
            for (MouseButton b : List.copyOf(h.held)) {
                h.held.remove(b);
                button(h.nativeCursor, h.position, b, false, 1, held(h));
            }
            for (Integer k : List.copyOf(h.keys).reversed()) {
                h.keys.remove(k);
                key(h.nativeCursor, h.position, k, false, keys(h));
            }
        } finally {
            h.open = false;
            cursors.remove(h.id);
            dispose(h.nativeCursor);
        }
    }

    @Override
    public synchronized void move(VirtualCursor cursor, int x, int y) {
        Handle h = open(cursor);
        Point to = new Point(x, y);
        moveTo(h.nativeCursor, to, held(h));
        h.position = to;
    }

    @Override
    public synchronized Point position(VirtualCursor cursor) {
        return new Point(open(cursor).position);
    }

    @Override
    public synchronized void press(VirtualCursor cursor, MouseButton button) {
        Handle h = open(cursor);
        if (h.held.contains(button)) {
            return;
        }
        h.held.add(button);
        try {
            button(h.nativeCursor, h.position, button, true, 1, held(h));
        } catch (RuntimeException e) {
            h.held.remove(button); // nicht gedrückt
            throw e;
        }
    }

    @Override
    public synchronized void release(VirtualCursor cursor, MouseButton button) {
        Handle h = open(cursor);
        if (!h.held.remove(button)) {
            return;
        }
        button(h.nativeCursor, h.position, button, false, 1, held(h));
    }

    @Override
    public synchronized Set<MouseButton> pressed(VirtualCursor cursor) {
        return held(open(cursor));
    }

    @Override
    public synchronized void click(VirtualCursor cursor, MouseButton button, int count) {
        if (count < 1 || count > MAX_CLICKS) {
            throw new IllegalArgumentException("Anzahl Klicks muss zwischen 1 und " + MAX_CLICKS + " liegen: " + count);
        }
        Handle h = open(cursor);
        if (h.held.contains(button)) {
            throw new IllegalStateException("Taste " + button + " wird gerade gehalten – erst loslassen.");
        }
        for (int i = 1; i <= count; i++) {
            h.held.add(button);
            try {
                button(h.nativeCursor, h.position, button, true, i, held(h));
            } catch (RuntimeException e) {
                h.held.remove(button);
                throw e;
            }
            h.held.remove(button);
            button(h.nativeCursor, h.position, button, false, i, held(h));
        }
    }

    @Override
    public synchronized void scroll(VirtualCursor cursor, int notches) {
        Handle h = open(cursor);
        if (notches != 0) {
            wheel(h.nativeCursor, h.position, notches, held(h));
        }
    }

    @Override
    public synchronized void type(VirtualCursor cursor, String text) {
        Handle h = open(cursor);
        if (text != null && !text.isEmpty()) {
            typeText(h.nativeCursor, h.position, text);
        }
    }

    @Override
    public synchronized void keyPress(VirtualCursor cursor, int keyCode) {
        Handle h = open(cursor);
        if (!h.keys.add(keyCode)) {
            return;
        }
        try {
            key(h.nativeCursor, h.position, keyCode, true, keys(h));
        } catch (RuntimeException e) {
            h.keys.remove(keyCode); // nicht gedrückt
            throw e;
        }
    }

    @Override
    public synchronized void keyRelease(VirtualCursor cursor, int keyCode) {
        Handle h = open(cursor);
        if (h.keys.remove(keyCode)) {
            key(h.nativeCursor, h.position, keyCode, false, keys(h));
        }
    }

    @Override
    public synchronized Set<Integer> pressedKeys(VirtualCursor cursor) {
        return keys(open(cursor));
    }

    @Override
    public synchronized List<VirtualCursor> cursors() {
        return List.copyOf(cursors.values());
    }

    @Override
    public synchronized void close() {
        RuntimeException first = null;
        for (Handle h : new ArrayList<>(cursors.values())) {
            try {
                destroy(h);
            } catch (RuntimeException e) {
                if (first == null) {
                    first = e;
                } else {
                    first.addSuppressed(e);
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }

    /** Nativer Zustand eines Zeigers – für Tests und Unterklassen. */
    protected synchronized C nativeCursor(VirtualCursor cursor) {
        return open(cursor).nativeCursor;
    }

    private Handle own(VirtualCursor cursor) {
        if (cursor instanceof AbstractCursorController<?>.Handle h && h.owner() == this) {
            @SuppressWarnings("unchecked")
            Handle mine = (Handle) h;
            return mine;
        }
        throw new IllegalArgumentException("Zeiger gehört nicht zu diesem Controller: " + cursor);
    }

    private Handle open(VirtualCursor cursor) {
        Handle h = own(cursor);
        if (!h.open) {
            throw new IllegalStateException("Zeiger " + h.id + " ist bereits zerstört.");
        }
        return h;
    }

    private Set<MouseButton> held(Handle h) {
        return Collections.unmodifiableSet(EnumSet.copyOf(h.held));
    }

    private Set<Integer> keys(Handle h) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(h.keys));
    }

    private final class Handle implements VirtualCursor {
        private final long id;
        private final C nativeCursor;
        private final EnumSet<MouseButton> held = EnumSet.noneOf(MouseButton.class);
        /** Gehaltene Tastaturtasten in Drückreihenfolge. */
        private final LinkedHashSet<Integer> keys = new LinkedHashSet<>();
        private Point position;
        private volatile boolean open = true;

        Handle(long id, C nativeCursor, Point position) {
            this.id = id;
            this.nativeCursor = nativeCursor;
            this.position = position;
        }

        AbstractCursorController<C> owner() {
            return AbstractCursorController.this;
        }

        @Override
        public long id() {
            return id;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public String toString() {
            return "VirtualCursor[" + id + (open ? "" : ", zerstört") + "]";
        }
    }
}
