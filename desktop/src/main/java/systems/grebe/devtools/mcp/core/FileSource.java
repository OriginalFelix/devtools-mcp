package systems.grebe.devtools.mcp.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Optional;

import systems.grebe.devtools.mcp.api.MediaTypes;

/**
 * Inhalt einer Datei aus einem Tool-Aufruf – genau eine von drei Quellen: Text ({@code file_content}), Base64
 * ({@code content_base64}, für kleine Binärdateien) oder eine lokale Datei ({@code source_path}, beliebig groß, nur aus
 * freigegebenen Verzeichnissen). Text und Base64 landen in einer temporären Datei, die {@link #close()} wieder löscht.
 */
public final class FileSource implements AutoCloseable {

    private final Path path;
    private final boolean temporary;
    private final String name;

    private FileSource(Path path, boolean temporary, String name) {
        this.path = path;
        this.temporary = temporary;
        this.name = name;
    }

    /**
     * @param text      Text ({@code null} = nicht angegeben)
     * @param base64    Base64-Inhalt, auch als {@code data:}-URL
     * @param localPath lokale Datei, geprüft über {@code files}
     */
    public static FileSource of(String text, String base64, String localPath, LocalFiles files) {
        boolean hasText = text != null;
        boolean hasBase64 = base64 != null && !base64.isBlank();
        boolean hasPath = localPath != null && !localPath.isBlank();
        if ((hasText ? 1 : 0) + (hasBase64 ? 1 : 0) + (hasPath ? 1 : 0) != 1) {
            throw new IllegalArgumentException("Genau eines angeben: file_content (Text), content_base64 (kleine "
                    + "Binärdateien) oder source_path (lokale Datei, beliebig groß).");
        }
        if (hasPath) {
            Path p = files.readable(localPath);
            return new FileSource(p, false, p.getFileName().toString());
        }
        byte[] bytes = hasText ? text.getBytes(StandardCharsets.UTF_8) : decode(base64);
        try {
            Path tmp = Files.createTempFile("devtools-upload-", ".tmp");
            Files.write(tmp, bytes);
            return new FileSource(tmp, true, null);
        } catch (IOException e) {
            throw new UncheckedIOException("Temporäre Datei nicht anlegbar: " + e.getMessage(), e);
        }
    }

    private static byte[] decode(String base64) {
        String b = base64.strip();
        int comma = b.indexOf(',');
        if (b.startsWith("data:") && comma > 0) {
            b = b.substring(comma + 1);
        }
        try {
            return Base64.getMimeDecoder().decode(b);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'content_base64' ist kein gültiges Base64: " + e.getMessage());
        }
    }

    /** Datei mit dem Inhalt. */
    public Path path() {
        return path;
    }

    /** Dateiname der lokalen Quelle; leer bei Text und Base64. */
    public Optional<String> name() {
        return Optional.ofNullable(name);
    }

    public long size() {
        try {
            return Files.size(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Der Inhalt als Text, wenn er sich als Text eignet: Medientyp textuell, höchstens {@code maxChars} Bytes und
     * gültiges UTF-8 – sonst leer (dann als Anhang speichern).
     */
    public Optional<String> text(String mediaType, int maxChars) {
        if (!MediaTypes.textual(mediaType) || size() > maxChars) {
            return Optional.empty();
        }
        try (InputStream in = Files.newInputStream(path)) {
            byte[] bytes = in.readAllBytes();
            return MediaTypes.looksLikeText(bytes) && bytes.length > 0
                    ? Optional.of(new String(bytes, StandardCharsets.UTF_8)) : Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException("Datei " + path + " nicht lesbar: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        if (temporary) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // temporäre Datei bleibt liegen
            }
        }
    }
}
