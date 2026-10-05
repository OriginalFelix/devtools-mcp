package systems.grebe.devtools.mcp.modules.window;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Farben der KIs: reine Farbtöne (HSB mit voller Sättigung und Helligkeit) mit möglichst großem Abstand. Die erste KI
 * bekommt 0° (Rot), jede weitere die Mitte der größten Lücke zwischen den belegten Farbtönen – 0°, 180°, 90°, 270°,
 * 45° … Eine KI behält ihren Farbton, bis er freigegeben wird; frei gewordene Lücken werden wieder vergeben.
 */
final class AiColors {

    private final Map<Object, Double> hues = new HashMap<>();

    /** Farbton (Grad) für {@code key}; beim ersten Aufruf vergeben, danach immer derselbe. */
    synchronized double assign(Object key) {
        Double known = hues.get(key);
        if (known != null) {
            return known;
        }
        double hue = largestGapMiddle(new ArrayList<>(hues.values()));
        hues.put(key, hue);
        return hue;
    }

    synchronized void release(Object key) {
        hues.remove(key);
    }

    private static double largestGapMiddle(List<Double> taken) {
        if (taken.isEmpty()) {
            return 0;
        }
        taken.sort(null);
        double bestStart = 0;
        double bestLength = -1;
        for (int i = 0; i < taken.size(); i++) {
            double start = taken.get(i);
            double end = i + 1 < taken.size() ? taken.get(i + 1) : taken.getFirst() + 360;
            if (end - start > bestLength) { // bei Gleichstand bleibt die erste Lücke
                bestStart = start;
                bestLength = end - start;
            }
        }
        return (bestStart + bestLength / 2) % 360;
    }

    /** Reine Farbe zum Farbton. */
    static Color color(double hue) {
        return Color.getHSBColor((float) (hue / 360), 1f, 1f);
    }

    /** Schwarz oder Weiß – was auf {@code background} besser lesbar ist (relative Leuchtdichte nach sRGB). */
    static Color textColor(Color background) {
        double l = 0.2126 * linear(background.getRed()) + 0.7152 * linear(background.getGreen())
                + 0.0722 * linear(background.getBlue());
        return l > 0.5 ? Color.BLACK : Color.WHITE;
    }

    private static double linear(int channel) {
        double c = channel / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
}
