package systems.grebe.devtools.mcp.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Dateien so schreiben, dass nie eine halbe Datei liegen bleibt: erst in eine Nachbardatei {@code .tmp}, dann
 * ersetzen - atomar, und auf Dateisystemen ohne atomares Umbenennen (manche Netz- oder umgeleiteten Home-Laufwerke
 * unter Windows) als gewöhnliches Ersetzen.
 */
public final class AtomicFiles {

    /** Schreibt den Inhalt in die übergebene temporäre Datei. */
    @FunctionalInterface
    public interface IoWriter {
        void write(Path tmp) throws IOException;
    }

    private AtomicFiles() {
    }

    /** Verschiebt {@code tmp} nach {@code target} und überschreibt dabei: atomar, sonst ersatzweise nicht atomar. */
    public static void replace(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Legt das Verzeichnis an, lässt {@code writer} die Nachbardatei {@code <name>.tmp} füllen und ersetzt das Ziel
     * damit. Scheitert etwas, wird die {@code .tmp}-Datei entfernt.
     */
    public static void write(Path target, IoWriter writer) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            writer.write(tmp);
            replace(tmp, target);
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // die ursprüngliche Ausnahme zählt
            }
            throw e;
        }
    }

    public static void writeString(Path target, String content) throws IOException {
        write(target, tmp -> Files.writeString(tmp, content, StandardCharsets.UTF_8));
    }
}
