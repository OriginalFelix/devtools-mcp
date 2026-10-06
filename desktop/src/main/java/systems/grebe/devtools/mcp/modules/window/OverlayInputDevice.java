package systems.grebe.devtools.mcp.modules.window;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.datatransfer.Transferable;
import java.awt.image.BufferedImage;
import java.util.Set;

import systems.grebe.devtools.mcp.modules.window.platform.NativeWindow;
import systems.grebe.devtools.mcp.modules.window.platform.WindowSystem;

/**
 * Ein {@link InputDevice}, das während der Steuerung die {@link ControlOverlay}-Anzeige führt: Rahmen um das
 * Zielfenster ab {@link #target}, KI-Zeiger bei jeder Mausbewegung, Ausblenden bei {@link #release()}. Alle Eingaben
 * selbst gehen unverändert an das umhüllte Gerät.
 */
final class OverlayInputDevice implements InputDevice, AutoCloseable {

    private final InputDevice delegate;
    private final ControlOverlay overlay;
    private final WindowSystem windows;
    private final boolean showPointer;

    /** @param showPointer KI-Zeiger zeichnen – nicht, wenn das Gerät selbst einen eigenen Zeiger hat */
    OverlayInputDevice(InputDevice delegate, ControlOverlay overlay, WindowSystem windows, boolean showPointer) {
        this.delegate = delegate;
        this.overlay = overlay;
        this.windows = windows;
        this.showPointer = showPointer;
    }

    @Override
    public void target(NativeWindow window, Set<Long> allowedPids) {
        long id = window.id();
        overlay.show(() -> windows.window(id).filter(w -> allowedPids.contains(w.pid())));
        delegate.target(window, allowedPids);
    }

    @Override
    public void release() {
        overlay.hide();
        delegate.release();
    }

    /** Ende der KI-Session: ausblenden und ein eigenes Gerät (eigener Zeiger) schließen; die echte Maus bleibt. */
    @Override
    public void close() throws Exception {
        release();
        if (delegate instanceof AutoCloseable c) {
            c.close();
        }
    }

    @Override
    public void move(int x, int y) {
        delegate.move(x, y);
        if (showPointer) {
            overlay.pointer(new Point(x, y));
        }
    }

    @Override
    public boolean independentPointer() {
        return delegate.independentPointer();
    }

    @Override
    public boolean independentKeyboard() {
        return delegate.independentKeyboard();
    }

    @Override
    public void click(int buttons, int count) {
        delegate.click(buttons, count);
    }

    @Override
    public Point pointer() {
        return delegate.pointer();
    }

    @Override
    public void press(int buttons) {
        delegate.press(buttons);
    }

    @Override
    public void release(int buttons) {
        delegate.release(buttons);
    }

    @Override
    public void wheel(int notches) {
        delegate.wheel(notches);
    }

    @Override
    public void keyPress(int keyCode) {
        delegate.keyPress(keyCode);
    }

    @Override
    public void keyRelease(int keyCode) {
        delegate.keyRelease(keyCode);
    }

    @Override
    public void pause(int millis) {
        delegate.pause(millis);
    }

    @Override
    public BufferedImage capture(Rectangle bounds) {
        return delegate.capture(bounds);
    }

    @Override
    public BufferedImage capture(NativeWindow window) {
        return delegate.capture(window);
    }

    @Override
    public Transferable clipboard() {
        return delegate.clipboard();
    }

    @Override
    public void clipboard(Transferable content) {
        delegate.clipboard(content);
    }

    @Override
    public boolean typesDirectly() {
        return delegate.typesDirectly();
    }

    @Override
    public boolean typeText(String text) {
        return delegate.typeText(text);
    }
}
