package systems.grebe.devtools.mcp.modules.window;

import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;

import systems.grebe.devtools.mcp.modules.window.cursor.CursorController;
import systems.grebe.devtools.mcp.modules.window.cursor.CursorImage;
import systems.grebe.devtools.mcp.modules.window.cursor.VirtualCursor;
import systems.grebe.devtools.mcp.modules.window.platform.ScreenMapper;

/**
 * Zeigt sichtbar an, dass die KI ein Fenster steuert: farbiger Rahmen um das Fenster, ein Hinweis mit der
 * Abbruch-Möglichkeit und ein eigener KI-Zeiger an der Stelle, an der die KI gerade arbeitet. Der Mauszeiger des
 * Nutzers bleibt unberührt.
 *
 * <p>Rahmen und Hinweis sind kleine, immer oben liegende Swing-Fenster, die nie den Fokus nehmen; der Rahmen besteht
 * aus vier schmalen Streifen außerhalb der Fenstergrenzen, damit das Fenster selbst bedienbar bleibt und Screenshots
 * ihn nicht enthalten. Der KI-Zeiger ist ein nativer zweiter Zeiger ({@link CursorController}); ist er auf diesem
 * System nicht verfügbar, fehlt nur er. Solange gesteuert wird, folgt der Rahmen dem Fenster; nach
 * {@link #IDLE_MILLIS} ohne Aktion blendet sich alles aus.
 *
 * <p>Koordinaten sind Java-Bildschirmkoordinaten (User-Space, wie {@link java.awt.Robot}); für den nativen Zeiger
 * rechnet {@link ScreenMapper} um. Alle Methoden sind threadsicher und kehren sofort zurück.
 */
final class ControlOverlay implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(ControlOverlay.class.getName());
    private static final int BORDER = 4;
    private static final int FOLLOW_MILLIS = 400;
    static final long IDLE_MILLIS = 60_000;

    private final Supplier<CursorController> cursors;
    private final ScreenMapper.Mode mode;
    /** Farbe der KI für Rahmen, Hinweis und Zeiger. */
    private final Color color;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "window-control-overlay");
        t.setDaemon(true);
        return t;
    });

    // nur auf dem EDT
    private final List<JWindow> frame = new ArrayList<>();
    private JWindow label;
    private JLabel labelText;

    // unter der Sperre von this
    private volatile String hint = "";
    private Supplier<Optional<Rectangle>> bounds = Optional::empty;
    private ScheduledFuture<?> follow;
    private ScheduledFuture<?> idle;
    private VirtualCursor cursor;
    private boolean cursorUnavailable;

    /**
     * @param cursors liefert den Controller des nativen Zeigers (wirft, wenn es keinen gibt)
     * @param mode    wie native Koordinaten aus User-Space entstehen (Windows {@code ANCHORED}, X11 {@code SCALED},
     *                macOS {@code IDENTITY})
     */
    ControlOverlay(Supplier<CursorController> cursors, ScreenMapper.Mode mode) {
        this(cursors, mode, CursorImage.ACCENT);
    }

    /** @param color Farbe der KI (siehe {@link AiColors}) */
    ControlOverlay(Supplier<CursorController> cursors, ScreenMapper.Mode mode, Color color) {
        this.cursors = cursors;
        this.mode = mode;
        this.color = color;
    }

    /** Text des Hinweises, z.B. die Abbruch-Möglichkeit. */
    void hint(String text) {
        hint = text == null ? "" : text;
        SwingUtilities.invokeLater(() -> {
            if (labelText != null) {
                labelText.setText(" " + hint + " ");
                label.pack();
            }
        });
    }

    /** Blendet Rahmen und Hinweis um das Fenster ein und folgt seinen Grenzen, bis {@link #hide()}. */
    synchronized void show(Supplier<Optional<Rectangle>> windowBounds) {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        bounds = windowBounds;
        touch();
        if (follow == null) {
            follow = timer.scheduleWithFixedDelay(this::refresh, 0, FOLLOW_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    /** Setzt den KI-Zeiger an diesen Punkt (erzeugt ihn beim ersten Mal). */
    synchronized void pointer(Point user) {
        if (GraphicsEnvironment.isHeadless() || cursorUnavailable) {
            return;
        }
        touch();
        Point at = ScreenMapper.current(mode).toNative(user);
        try {
            CursorController c = cursors.get();
            if (cursor == null || !cursor.isOpen()) {
                cursor = c.create(at.x, at.y, color);
            } else {
                c.move(cursor, at.x, at.y);
            }
        } catch (RuntimeException | LinkageError e) {
            // nur Anzeige – ohne nativen Zeiger geht die Steuerung weiter
            cursorUnavailable = true;
            LOG.log(Level.INFO, "KI-Zeiger nicht verfügbar: " + e.getMessage(), e);
        }
    }

    /** Blendet alles aus und zerstört den KI-Zeiger. */
    synchronized void hide() {
        if (follow != null) {
            follow.cancel(false);
            follow = null;
        }
        if (idle != null) {
            idle.cancel(false);
            idle = null;
        }
        bounds = Optional::empty;
        if (cursor != null) {
            VirtualCursor c = cursor;
            cursor = null;
            try {
                cursors.get().destroy(c);
            } catch (RuntimeException | LinkageError e) {
                LOG.log(Level.FINE, "KI-Zeiger nicht zerstört", e);
            }
        }
        SwingUtilities.invokeLater(this::disposeWindows);
    }

    /** Blendet aus und beendet den Zeitgeber – die KI-Session ist vorbei. */
    @Override
    public void close() {
        hide();
        timer.shutdownNow();
    }

    /** Ob gerade etwas angezeigt wird – für Tests. */
    synchronized boolean visible() {
        return follow != null;
    }

    private void touch() {
        if (idle != null) {
            idle.cancel(false);
        }
        idle = timer.schedule(this::hide, IDLE_MILLIS, TimeUnit.MILLISECONDS);
    }

    /** Liest die Fenstergrenzen (außerhalb des EDT – das sind native Aufrufe) und setzt die Fenster darauf. */
    private void refresh() {
        Supplier<Optional<Rectangle>> source;
        synchronized (this) {
            source = bounds;
        }
        Optional<Rectangle> b;
        try {
            b = source.get();
        } catch (RuntimeException e) {
            b = Optional.empty();
        }
        Optional<Rectangle> target = b;
        SwingUtilities.invokeLater(() -> place(target));
    }

    private void place(Optional<Rectangle> target) {
        synchronized (this) {
            if (follow == null) {
                return; // inzwischen ausgeblendet
            }
        }
        if (target.isEmpty()) {
            frame.forEach(w -> w.setVisible(false));
            if (label != null) {
                label.setVisible(false);
            }
            return;
        }
        Rectangle r = target.get();
        if (frame.isEmpty()) {
            for (int i = 0; i < 4; i++) {
                JWindow strip = window();
                strip.getContentPane().setBackground(color);
                frame.add(strip);
            }
            label = window();
            labelText = new JLabel(" " + hint + " ");
            labelText.setOpaque(true);
            labelText.setBackground(color);
            labelText.setForeground(AiColors.textColor(color));
            labelText.setFont(labelText.getFont().deriveFont(Font.BOLD, 12f));
            labelText.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
            label.getContentPane().add(labelText);
        }
        // außerhalb der Fenstergrenzen: oben, unten, links, rechts
        frame.get(0).setBounds(r.x - BORDER, r.y - BORDER, r.width + 2 * BORDER, BORDER);
        frame.get(1).setBounds(r.x - BORDER, r.y + r.height, r.width + 2 * BORDER, BORDER);
        frame.get(2).setBounds(r.x - BORDER, r.y, BORDER, r.height);
        frame.get(3).setBounds(r.x + r.width, r.y, BORDER, r.height);
        frame.forEach(w -> w.setVisible(true));
        label.pack();
        int labelY = r.y - BORDER - label.getHeight();
        if (labelY < screenTop(r)) {
            labelY = r.y; // kein Platz über dem Fenster: innen oben
        }
        label.setLocation(r.x + r.width - label.getWidth(), labelY);
        label.setVisible(!hint.isEmpty());
    }

    private static int screenTop(Rectangle r) {
        for (var d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            Rectangle s = d.getDefaultConfiguration().getBounds();
            if (s.intersects(r)) {
                return s.y;
            }
        }
        return 0;
    }

    private static JWindow window() {
        JWindow w = new JWindow();
        w.setAlwaysOnTop(true);
        w.setFocusableWindowState(false);
        w.setAutoRequestFocus(false);
        w.setType(java.awt.Window.Type.UTILITY);
        return w;
    }

    private void disposeWindows() {
        frame.forEach(JWindow::dispose);
        frame.clear();
        if (label != null) {
            label.dispose();
            label = null;
            labelText = null;
        }
    }
}
