package systems.grebe.devtools.mcp.modules.window;

import java.awt.Point;
import java.awt.event.InputEvent;
import java.util.List;
import java.util.Locale;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.ToolHints;

/**
 * Mauseingaben in Fenster des gebundenen Prozesses: mit eigenem Zeiger ohne das Fenster nach vorn zu holen, mit der
 * echten Maus ({@link java.awt.Robot}) erst nach dem Aktivieren und mit Abbruch, wenn es den Fokus verliert oder der
 * Nutzer die Maus bewegt. Tippen und Tasten: {@link WindowKeyboardTools}.
 */
@ToolHints(readOnly = false, destructive = true, openWorld = false)
public class WindowInputTools {

    static final String COORDS = " in Pixeln des letzten window_screenshot dieses Fensters (Ursprung oben links)";

    private final WindowSupport support;
    private final boolean keyboard;

    /** @param keyboard ob die Tastatur genutzt werden darf (Klicks mit gehaltenen Tasten) */
    WindowInputTools(WindowSupport support, boolean keyboard) {
        this.support = support;
        this.keyboard = keyboard;
    }

    @Tool(name = "click", description = "Klickt in ein Fenster des gebundenen Prozesses. Koordinaten" + COORDS + ". Danach "
            + "mit window_screenshot das Ergebnis prüfen." + ShellHints.WINDOW)
    public String click(
            @ToolParam(description = "x" + COORDS) int x,
            @ToolParam(description = "y" + COORDS) int y,
            @ToolParam(required = false, description = "left (Standard), right oder middle") String button,
            @ToolParam(required = false, description = "Anzahl Klicks, 2 = Doppelklick (Standard 1)") Integer count,
            @ToolParam(required = false, description = "Gehaltene Tasten, z.B. \"ctrl\" oder \"shift\"") String modifiers,
            @ToolParam(required = false, description = WindowReadTools.WINDOW) String window) {
        int mask = button(button);
        int clicks = count == null ? 1 : Math.clamp(count, 1, 3);
        List<Integer> held = List.of();
        if (modifiers != null && !modifiers.isBlank()) {
            if (!keyboard) {
                throw new IllegalArgumentException("Klicks mit gehaltenen Tasten brauchen die Tastatur – „Tastatur "
                        + "erlauben“ ist in der DevTools-App aus. Ohne modifiers klicken oder den Nutzer fragen.");
            }
            KeySpec.Combo combo = KeySpec.parse(modifiers.strip().replaceAll("\\s+", "+") + "+a").getFirst();
            if (combo.usesSystemKey(support.mac())) {
                throw new IllegalArgumentException("Die Windows-/Super-Taste ist gesperrt.");
            }
            held = combo.modifiers();
        }
        List<Integer> heldKeys = held;
        return support.pointerInput(window, !heldKeys.isEmpty(), g -> {
            Point p = g.toScreen(x, y);
            g.move(p);
            pressAll(g, heldKeys);
            try {
                g.step(() -> g.device().click(mask, clicks));
            } finally {
                releaseAll(g, heldKeys);
            }
            return (clicks == 2 ? "Doppelklick" : clicks + "× Klick") + " (" + name(mask) + ") bei " + x + "," + y
                    + " in " + g.window().hexId() + " „" + g.window().title() + "“." + g.unverified();
        });
    }

    @Tool(name = "scroll", description = "Scrollt mit dem Mausrad an einer Stelle des Fensters. Koordinaten" + COORDS + "."
            + ShellHints.WINDOW)
    public String scroll(
            @ToolParam(description = "x" + COORDS) int x,
            @ToolParam(description = "y" + COORDS) int y,
            @ToolParam(description = "Rasten: positiv = nach unten, negativ = nach oben (max. ±50)") int amount,
            @ToolParam(required = false, description = WindowReadTools.WINDOW) String window) {
        int notches = Math.clamp(amount, -50, 50);
        return support.pointerInput(window, false, g -> {
            g.move(g.toScreen(x, y));
            int step = Integer.signum(notches);
            for (int i = 0; i < Math.abs(notches); i++) {
                g.step(() -> g.device().wheel(step));
            }
            return "Gescrollt um " + notches + " Rasten bei " + x + "," + y + " in " + g.window().hexId() + "."
                    + g.unverified();
        });
    }

    @Tool(name = "drag", description = "Zieht mit gedrückter Maustaste von einem Punkt zu einem anderen (Schieberegler, "
            + "Markieren, Drag & Drop innerhalb des Fensters). Koordinaten" + COORDS + "." + ShellHints.WINDOW)
    public String drag(
            @ToolParam(description = "Start x") int fromX,
            @ToolParam(description = "Start y") int fromY,
            @ToolParam(description = "Ziel x") int toX,
            @ToolParam(description = "Ziel y") int toY,
            @ToolParam(required = false, description = "left (Standard), right oder middle") String button,
            @ToolParam(required = false, description = WindowReadTools.WINDOW) String window) {
        int mask = button(button);
        return support.pointerInput(window, false, g -> {
            Point from = g.toScreen(fromX, fromY);
            Point to = g.toScreen(toX, toY);
            g.move(from);
            g.step(() -> g.device().press(mask));
            try {
                int steps = 10;
                for (int i = 1; i <= steps; i++) {
                    g.move(new Point(from.x + (to.x - from.x) * i / steps, from.y + (to.y - from.y) * i / steps));
                }
            } finally {
                g.device().release(mask);
            }
            return "Gezogen von " + fromX + "," + fromY + " nach " + toX + "," + toY + " in " + g.window().hexId() + "."
                    + g.unverified();
        });
    }

    static void pressAll(InputGuard g, List<Integer> keys) {
        for (int k : keys) {
            g.device().keyPress(k);
        }
    }

    static void releaseAll(InputGuard g, List<Integer> keys) {
        for (int i = keys.size() - 1; i >= 0; i--) {
            g.device().keyRelease(keys.get(i));
        }
    }

    private static int button(String button) {
        String b = button == null || button.isBlank() ? "left" : button.toLowerCase(Locale.ROOT);
        return switch (b) {
            case "left", "links" -> InputEvent.BUTTON1_DOWN_MASK;
            case "middle", "mitte" -> InputEvent.BUTTON2_DOWN_MASK;
            case "right", "rechts" -> InputEvent.BUTTON3_DOWN_MASK;
            default -> throw new IllegalArgumentException("button muss left, right oder middle sein.");
        };
    }

    private static String name(int mask) {
        return mask == InputEvent.BUTTON3_DOWN_MASK ? "rechts" : mask == InputEvent.BUTTON2_DOWN_MASK ? "Mitte" : "links";
    }
}
