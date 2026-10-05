package systems.grebe.devtools.mcp.modules.window;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import systems.grebe.devtools.mcp.modules.window.platform.NativeWindow;
import systems.grebe.devtools.mcp.modules.window.platform.WindowSystem;

/** Attrappen für Fenstersystem und Eingabegerät; Ereignisse werden als Text protokolliert. */
final class FakeDesktop implements WindowSystem, InputDevice {

    final List<NativeWindow> windows = new ArrayList<>();
    final List<String> events = new ArrayList<>();
    /** Übernahme und Abgabe der Kontrolle ({@link #target}, {@link #release()}) – getrennt von den Eingaben. */
    final List<String> control = new ArrayList<>();
    /** Wie ein eigener Zeiger: Mauseingaben ohne Vordergrund. */
    boolean independent;
    /** Wie eine eigene Tastatur: Tastatur-Eingaben ohne Vordergrund, Text direkt. */
    boolean independentKeys;
    /** Bild für {@link #captureInBackground}; {@code null} = kann das System nicht. */
    BufferedImage backgroundImage;
    long foreground;
    boolean activationWorks = true;
    private int foregroundLostAfter = -1;
    private int foregroundQueries;
    Point pointer = new Point(0, 0);
    Transferable clipboard;
    final AtomicLong clock = new AtomicLong(1_000_000);
    final UserPresenceMonitor presence = new UserPresenceMonitor(clock::get);

    /** Nach so vielen weiteren Abfragen des Vordergrunds wechselt er auf 0 (Fokusverlust). */
    void loseForegroundAfter(int queries) {
        foregroundQueries = 0;
        foregroundLostAfter = queries;
    }

    WindowSupport support(WindowSession session, ProcessFilter filter) {
        return support(session, filter, false);
    }

    WindowSupport support(WindowSession session, ProcessFilter filter, boolean allowSiblings) {
        return new WindowSupport(this, filter, () -> session, () -> this, presence, new ReentrantLock(),
                new WindowSupport.Settings(1280, true, Duration.ofSeconds(10), allowSiblings), millis -> { }, false);
    }

    // --- WindowSystem

    @Override
    public String name() {
        return "Fake";
    }

    @Override
    public Optional<String> unsupportedReason() {
        return Optional.empty();
    }

    @Override
    public List<NativeWindow> windows() {
        return List.copyOf(windows);
    }

    @Override
    public OptionalLong foreground() {
        foregroundQueries++;
        if (foregroundLostAfter >= 0 && foregroundQueries > foregroundLostAfter) {
            foreground = 0;
        }
        return foreground == 0 ? OptionalLong.empty() : OptionalLong.of(foreground);
    }

    @Override
    public Optional<BufferedImage> captureInBackground(NativeWindow window) {
        if (backgroundImage != null) {
            events.add("background-capture " + Long.toHexString(window.id()));
        }
        return Optional.ofNullable(backgroundImage);
    }

    @Override
    public void activate(long id) {
        events.add("activate " + Long.toHexString(id));
        if (activationWorks) {
            foreground = id;
        }
    }

    // --- InputDevice

    @Override
    public Point pointer() {
        return new Point(pointer);
    }

    @Override
    public void move(int x, int y) {
        pointer = new Point(x, y);
        events.add("move " + x + "," + y);
    }

    @Override
    public void press(int buttons) {
        events.add("press " + buttons);
    }

    @Override
    public void release(int buttons) {
        events.add("release " + buttons);
    }

    @Override
    public void wheel(int notches) {
        events.add("wheel " + notches);
    }

    @Override
    public void keyPress(int keyCode) {
        events.add("down " + keyName(keyCode));
    }

    @Override
    public void keyRelease(int keyCode) {
        events.add("up " + keyName(keyCode));
    }

    /** Feste Namen statt KeyEvent.getKeyText (der ist lokalisiert). */
    private static String keyName(int code) {
        return switch (code) {
            case java.awt.event.KeyEvent.VK_SHIFT -> "Shift";
            case java.awt.event.KeyEvent.VK_CONTROL -> "Ctrl";
            case java.awt.event.KeyEvent.VK_META -> "Meta";
            case java.awt.event.KeyEvent.VK_ALT -> "Alt";
            case java.awt.event.KeyEvent.VK_SPACE -> "Space";
            case java.awt.event.KeyEvent.VK_ENTER -> "Enter";
            default -> code >= '0' && code <= 'Z' ? String.valueOf((char) code) : "#" + code;
        };
    }

    @Override
    public void pause(int millis) {
    }

    @Override
    public BufferedImage capture(Rectangle bounds) {
        events.add("capture " + bounds.width + "x" + bounds.height);
        return new BufferedImage(bounds.width, bounds.height, BufferedImage.TYPE_INT_RGB);
    }

    @Override
    public Transferable clipboard() {
        return clipboard;
    }

    @Override
    public void clipboard(Transferable content) {
        clipboard = content;
        try {
            events.add("clipboard " + content.getTransferData(DataFlavor.stringFlavor));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void target(NativeWindow window, java.util.Set<Long> allowedPids) {
        control.add("target " + Long.toHexString(window.id()));
    }

    @Override
    public void release() {
        control.add("release");
    }

    @Override
    public boolean independentPointer() {
        return independent;
    }

    @Override
    public boolean independentKeyboard() {
        return independentKeys;
    }

    @Override
    public boolean typesDirectly() {
        return independentKeys;
    }

    @Override
    public void typeChar(char c) {
        events.add("char " + c);
    }
}
