package systems.grebe.devtools.mcp.core;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Bilder als Teil des Ergebnisses des laufenden Tool-Aufrufs (MCP {@code ImageContent}), z.B. ein Screenshot. Das
 * Tool liefert wie immer Text; {@link #add} hängt zusätzlich ein Bild an, das die App nach dem Aufruf neben den Text
 * ins Ergebnis legt – am Text vorbei, damit Kürzen, Protokoll und Token-Statistik nicht mit Base64 arbeiten.
 *
 * <pre>{@code
 * byte[] png = …;
 * boolean sent = ToolImages.add(png, "image/png");
 * return sent ? "Screenshot 1280×800" : "Screenshot gespeichert unter …";
 * }</pre>
 *
 * <p>Wie {@link ToolProgress} über einen {@link ThreadLocal}: das Tool läuft im selben Thread wie der Handler. Außerhalb
 * eines MCP-Aufrufs (Skripte, Tests, andere Threads) nimmt niemand Bilder an – dann liefert {@link #add} {@code false}.
 */
public final class ToolImages {

    /** Ein angehängtes Bild. */
    public record Image(byte[] data, String mimeType) {
    }

    /** Ergebnis eines Aufrufs samt angehängter Bilder. */
    public record Collected<T>(T result, List<Image> images) {
    }

    private static final ThreadLocal<List<Image>> CURRENT = new ThreadLocal<>();

    private ToolImages() {
    }

    /** Für die App: führt {@code body} aus und sammelt dabei angehängte Bilder ein. */
    public static <T> Collected<T> collect(Supplier<T> body) {
        List<Image> previous = CURRENT.get();
        List<Image> images = new ArrayList<>();
        CURRENT.set(images);
        try {
            return new Collected<>(body.get(), List.copyOf(images));
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /** Ob der laufende Aufruf Bilder annimmt. */
    public static boolean active() {
        return CURRENT.get() != null;
    }

    /**
     * Hängt ein Bild an das Ergebnis des laufenden Aufrufs.
     *
     * @return ob es angenommen wurde ({@code false} außerhalb eines MCP-Aufrufs)
     */
    public static boolean add(byte[] data, String mimeType) {
        List<Image> images = CURRENT.get();
        if (images == null || data == null || data.length == 0) {
            return false;
        }
        images.add(new Image(data, mimeType));
        return true;
    }
}
