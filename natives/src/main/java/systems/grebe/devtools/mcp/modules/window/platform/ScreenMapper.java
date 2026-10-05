package systems.grebe.devtools.mcp.modules.window.platform;

import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.List;

/**
 * Rechnet native Fensterkoordinaten in Java-Bildschirmkoordinaten (User-Space) um – die Einheit von
 * {@link java.awt.Robot} und {@link java.awt.MouseInfo}. Bei HiDPI unterscheiden sie sich:
 *
 * <ul>
 *   <li>{@link Mode#ANCHORED} (Windows): native Werte sind physische Pixel. Java legt jeden Monitor mit seinem
 *       physischen Ursprung ab und skaliert nur die Ausdehnung; ein Punkt wird deshalb relativ zum Ursprung seines
 *       Monitors durch dessen Skalierung geteilt.</li>
 *   <li>{@link Mode#SCALED} (X11): native Werte sind Pixel, Java teilt alles durch die Skalierung.</li>
 *   <li>{@link Mode#IDENTITY} (macOS): native Werte sind bereits Punkte = User-Space.</li>
 * </ul>
 */
public final class ScreenMapper {

    public enum Mode { ANCHORED, SCALED, IDENTITY }

    /** Ein Monitor: Grenzen in User-Space und Skalierung (physische Pixel je User-Space-Einheit). */
    public record Screen(Rectangle bounds, double scaleX, double scaleY) {

        Rectangle deviceBounds() {
            return new Rectangle(bounds.x, bounds.y, (int) Math.round(bounds.width * scaleX),
                    (int) Math.round(bounds.height * scaleY));
        }
    }

    private final Mode mode;
    private final List<Screen> screens;

    public ScreenMapper(Mode mode, List<Screen> screens) {
        this.mode = mode;
        this.screens = List.copyOf(screens);
    }

    /** Mapper mit den aktuell angeschlossenen Monitoren; ohne Grafikumgebung (headless) ohne Umrechnung. */
    public static ScreenMapper current(Mode mode) {
        if (mode == Mode.IDENTITY || GraphicsEnvironment.isHeadless()) {
            return new ScreenMapper(Mode.IDENTITY, List.of());
        }
        List<Screen> screens = new ArrayList<>();
        for (GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            GraphicsConfiguration gc = d.getDefaultConfiguration();
            AffineTransform t = gc.getDefaultTransform();
            screens.add(new Screen(gc.getBounds(), t.getScaleX(), t.getScaleY()));
        }
        return new ScreenMapper(mode, screens);
    }

    public Rectangle toUser(Rectangle nativeBounds) {
        if (mode == Mode.IDENTITY || screens.isEmpty()) {
            return new Rectangle(nativeBounds);
        }
        if (mode == Mode.SCALED) {
            Screen s = screens.getFirst();
            return new Rectangle(div(nativeBounds.x, s.scaleX()), div(nativeBounds.y, s.scaleY()),
                    div(nativeBounds.width, s.scaleX()), div(nativeBounds.height, s.scaleY()));
        }
        Screen s = screenOf(nativeBounds);
        Rectangle b = s.bounds();
        return new Rectangle(b.x + div(nativeBounds.x - b.x, s.scaleX()), b.y + div(nativeBounds.y - b.y, s.scaleY()),
                div(nativeBounds.width, s.scaleX()), div(nativeBounds.height, s.scaleY()));
    }

    /** Umkehrung von {@link #toUser} für einen Punkt: Java-Bildschirmkoordinaten → native Koordinaten. */
    public java.awt.Point toNative(java.awt.Point user) {
        if (mode == Mode.IDENTITY || screens.isEmpty()) {
            return new java.awt.Point(user);
        }
        if (mode == Mode.SCALED) {
            Screen s = screens.getFirst();
            return new java.awt.Point((int) Math.round(user.x * s.scaleX()), (int) Math.round(user.y * s.scaleY()));
        }
        Screen s = screens.stream().filter(sc -> sc.bounds().contains(user)).findFirst().orElse(screens.getFirst());
        Rectangle b = s.bounds();
        return new java.awt.Point(b.x + (int) Math.round((user.x - b.x) * s.scaleX()),
                b.y + (int) Math.round((user.y - b.y) * s.scaleY()));
    }

    /** Monitor mit der größten Überdeckung (bei keinem: der erste). */
    private Screen screenOf(Rectangle nativeBounds) {
        Screen best = screens.getFirst();
        long bestArea = -1;
        for (Screen s : screens) {
            Rectangle i = s.deviceBounds().intersection(nativeBounds);
            long area = i.isEmpty() ? 0 : (long) i.width * i.height;
            if (area > bestArea) {
                best = s;
                bestArea = area;
            }
        }
        return best;
    }

    private static int div(int value, double scale) {
        return (int) Math.round(value / scale);
    }
}
