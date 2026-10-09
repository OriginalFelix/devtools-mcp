package systems.grebe.devtools.mcp.modules.window;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Hilfen für die regulären Ausdrücke „Nur diese Prozesse“ und „Prozesse ausschließen“. */
public final class ProcessPatterns {

    private ProcessPatterns() {
    }

    /**
     * Hängt einen Prozessnamen als Alternative an den Ausdruck an ({@code charmap} → {@code charmap|winword}).
     * Sonderzeichen werden escaped; passt der Ausdruck schon auf den Namen, bleibt er unverändert.
     */
    public static String append(String pattern, String processName) {
        String p = pattern == null ? "" : pattern.strip();
        String quoted = processName.replaceAll("[\\\\^$.|?*+()\\[\\]{}]", "\\\\$0");
        if (p.isEmpty()) {
            return quoted;
        }
        try {
            if (Pattern.compile(p, Pattern.CASE_INSENSITIVE).matcher(processName).find()) {
                return p;
            }
        } catch (PatternSyntaxException e) {
            // ungültiger Ausdruck: trotzdem anhängen, die Prüfung beim Speichern meldet ihn
        }
        return p + "|" + quoted;
    }
}
