package systems.grebe.devtools.mcp.modules.window;

import java.awt.AWTException;
import java.awt.GraphicsEnvironment;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.Transferable;
import java.awt.image.BufferedImage;

/** {@link InputDevice} über {@link Robot}; die Zwischenablage über das AWT-Toolkit. */
final class RobotInputDevice implements InputDevice {

    private static final int AUTO_DELAY_MILLIS = 12;

    private final Robot robot;

    RobotInputDevice() {
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("Die App läuft ohne Bildschirm (headless) – Maus, Tastatur und Screenshots "
                    + "sind nicht verfügbar. Die Desktop-App ohne --headless starten.");
        }
        try {
            robot = new Robot();
        } catch (AWTException | SecurityException e) {
            throw new IllegalStateException("java.awt.Robot nicht verfügbar: " + e.getMessage(), e);
        }
        robot.setAutoDelay(AUTO_DELAY_MILLIS);
    }

    @Override
    public Point pointer() {
        PointerInfo info = MouseInfo.getPointerInfo();
        return info == null ? null : info.getLocation();
    }

    @Override
    public void move(int x, int y) {
        robot.mouseMove(x, y);
    }

    @Override
    public void press(int buttons) {
        robot.mousePress(buttons);
    }

    @Override
    public void release(int buttons) {
        robot.mouseRelease(buttons);
    }

    @Override
    public void wheel(int notches) {
        robot.mouseWheel(notches);
    }

    @Override
    public void keyPress(int keyCode) {
        robot.keyPress(keyCode);
    }

    @Override
    public void keyRelease(int keyCode) {
        robot.keyRelease(keyCode);
    }

    @Override
    public void pause(int millis) {
        robot.delay(millis);
    }

    @Override
    public BufferedImage capture(Rectangle bounds) {
        return robot.createScreenCapture(bounds);
    }

    @Override
    public Transferable clipboard() {
        try {
            return systemClipboard().getContents(null);
        } catch (IllegalStateException e) {
            return null; // gerade von einer anderen Anwendung belegt
        }
    }

    @Override
    public void clipboard(Transferable content) {
        systemClipboard().setContents(content, null);
    }

    private static Clipboard systemClipboard() {
        return Toolkit.getDefaultToolkit().getSystemClipboard();
    }
}
