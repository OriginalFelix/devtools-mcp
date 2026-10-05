package systems.grebe.devtools.mcp.modules.window;

import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.Locale;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.ToolHints;

/**
 * Tippen und Tastenkombinationen – eigener Schalter „Tastatur erlauben“. Mit eigenem Zeiger über dessen eigene
 * Tastatur: Text und Tasten gehen an das Element, das der Zeiger zuletzt angeklickt hat, ohne das Fenster nach vorn zu
 * holen und ohne die Tastatur des Nutzers. Im Modus „maus“ über die echte Tastatur ({@link java.awt.Robot}): dann
 * wird das Fenster aktiviert und bei Fokusverlust oder Eingriff des Nutzers abgebrochen.
 */
@ToolHints(readOnly = false, destructive = true, openWorld = false)
public class WindowKeyboardTools {

    /** Nach so vielen getippten Zeichen wird erneut geprüft (Fokus, Nutzer). */
    static final int CHUNK = 20;
    private static final int PASTE_SETTLE_MILLIS = 150;

    private final WindowSupport support;

    WindowKeyboardTools(WindowSupport support) {
        this.support = support;
    }

    @Tool(name = "type", description = "Tippt Text in das Fenster (in das fokussierte Eingabefeld – vorher ggf. "
            + "hineinklicken). Buchstaben, Ziffern, Leerzeichen, Tab und Zeilenumbruch werden als Tasten getippt, andere "
            + "Zeichen (Umlaute, Sonderzeichen) über die Zwischenablage eingefügt; die Zwischenablage wird danach "
            + "wiederhergestellt. Keine Passwörter eintippen." + ShellHints.WINDOW)
    public String type(
            @ToolParam(description = "Zu tippender Text") String text,
            @ToolParam(required = false, description = "auto (Standard), keys (nur Tasten) oder paste (alles über die "
                    + "Zwischenablage)") String method,
            @ToolParam(required = false, description = WindowReadTools.WINDOW) String window) {
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("Kein Text angegeben.");
        }
        String m = method == null || method.isBlank() ? "auto" : method.toLowerCase(Locale.ROOT);
        if (!List.of("auto", "keys", "paste").contains(m)) {
            throw new IllegalArgumentException("method muss auto, keys oder paste sein.");
        }
        return support.keyboardInput(window, g -> {
            int[] done = {0};
            try {
                if (g.device().typesDirectly()) {
                    typeDirectly(g, text, done); // eigene Tastatur: jedes Zeichen direkt, keine Zwischenablage nötig
                } else if (m.equals("paste")) {
                    paste(g, text);
                    done[0] = text.length();
                } else {
                    typeChars(g, text, m.equals("keys"), done);
                }
            } catch (InputGuard.FocusLostException | UserPresenceMonitor.UserInterventionException e) {
                throw new IllegalStateException(e.getMessage() + " Bis dahin getippt: " + done[0] + " von "
                        + text.length() + " Zeichen.", e);
            }
            return text.length() + " Zeichen in " + g.window().hexId() + " „" + g.window().title() + "“ getippt.";
        });
    }

    /** Tippt jedes Zeichen direkt in den Fokus der eigenen Tastatur; alle {@link #CHUNK} Zeichen eine Prüfung. */
    private void typeDirectly(InputGuard g, String text, int[] done) {
        for (int i = 0; i < text.length(); i++) {
            if (i % CHUNK == 0) {
                g.checkpoint();
            }
            g.device().typeChar(text.charAt(i));
            done[0] = i + 1;
        }
    }

    /**
     * Tippt Zeichen für Zeichen; Folgen nicht tippbarer Zeichen gehen gesammelt über die Zwischenablage.
     *
     * @param done Fortschritt (getippte Zeichen), auch bei Abbruch aktuell
     */
    private void typeChars(InputGuard g, String text, boolean keysOnly, int[] done) {
        int i = 0;
        int sinceCheck = CHUNK;
        while (i < text.length()) {
            if (sinceCheck >= CHUNK) {
                g.checkpoint();
                sinceCheck = 0;
            }
            char c = text.charAt(i);
            int code = KeySpec.simpleKey(c);
            if (code != KeyEvent.VK_UNDEFINED) {
                boolean shift = Character.isUpperCase(c);
                if (shift) {
                    g.device().keyPress(KeyEvent.VK_SHIFT);
                }
                try {
                    g.device().keyPress(code);
                    g.device().keyRelease(code);
                } finally {
                    if (shift) {
                        g.device().keyRelease(KeyEvent.VK_SHIFT);
                    }
                }
                done[0] = ++i;
                sinceCheck++;
                continue;
            }
            if (keysOnly) {
                throw new IllegalArgumentException("Zeichen '" + c + "' (Position " + i + ") lässt sich nicht "
                        + "layoutunabhängig als Taste tippen – method=auto oder paste verwenden.");
            }
            int end = i;
            while (end < text.length() && KeySpec.simpleKey(text.charAt(end)) == KeyEvent.VK_UNDEFINED) {
                end++;
            }
            g.checkpoint();
            paste(g, text.substring(i, end));
            done[0] = i = end;
            sinceCheck = 0;
        }
    }

    private void paste(InputGuard g, String text) {
        InputDevice d = g.device();
        Transferable previous = d.clipboard();
        d.clipboard(new StringSelection(text));
        int modifier = support.mac() ? KeyEvent.VK_META : KeyEvent.VK_CONTROL;
        try {
            g.step(() -> d.keyPress(modifier));
            try {
                d.keyPress(KeyEvent.VK_V);
                d.keyRelease(KeyEvent.VK_V);
            } finally {
                d.keyRelease(modifier);
            }
            d.pause(PASTE_SETTLE_MILLIS); // Ziel liest die Zwischenablage oft erst verzögert
        } finally {
            if (previous != null) {
                d.clipboard(previous);
            }
        }
    }

    @Tool(name = "key", description = "Drückt Tasten oder Tastenkombinationen, z.B. \"enter\", \"ctrl+s\", \"alt+f4\", "
            + "\"shift+tab\"; mehrere nacheinander durch Leerzeichen getrennt (\"ctrl+a ctrl+c\"). Unter macOS cmd statt "
            + "ctrl. Die Windows-/Super-Taste ist gesperrt." + ShellHints.WINDOW)
    public String key(
            @ToolParam(description = "Taste(n), z.B. \"ctrl+shift+s\" oder \"down down enter\"") String keys,
            @ToolParam(required = false, description = "Wie oft die ganze Folge gedrückt wird (Standard 1, max. 50)") Integer repeat,
            @ToolParam(required = false, description = WindowReadTools.WINDOW) String window) {
        List<KeySpec.Combo> combos = KeySpec.parse(keys);
        for (KeySpec.Combo c : combos) {
            if (c.usesSystemKey(support.mac())) {
                throw new IllegalArgumentException("\"" + c.text() + "\" verwendet die Windows-/Super-Taste – sie wirkt "
                        + "auf das System statt auf das Fenster und ist gesperrt.");
            }
        }
        int times = repeat == null ? 1 : Math.clamp(repeat, 1, 50);
        return support.keyboardInput(window, g -> {
            for (int r = 0; r < times; r++) {
                for (KeySpec.Combo c : combos) {
                    WindowInputTools.pressAll(g, c.modifiers());
                    try {
                        g.step(() -> g.device().keyPress(c.key()));
                        g.device().keyRelease(c.key());
                    } finally {
                        WindowInputTools.releaseAll(g, c.modifiers());
                    }
                }
            }
            return "Gedrückt: " + keys + (times > 1 ? " (" + times + "×)" : "") + " in " + g.window().hexId() + " „"
                    + g.window().title() + "“.";
        });
    }
}
