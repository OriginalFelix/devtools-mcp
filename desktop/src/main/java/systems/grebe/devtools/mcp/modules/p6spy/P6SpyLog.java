package systems.grebe.devtools.mcp.modules.p6spy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Liest ein p6spy-Protokoll ({@code spy.log}) im Standardformat {@code SingleLineFormat}
 * ({@code zeit|ms|kategorie|connection N|url …|prepared|sql}, ohne {@code url} vor p6spy 3.9) oder im Standard von
 * {@code CustomLineFormat} ({@code zeit|ms|kategorie|connectionN|sql}). Andere Zeilen (Stacktraces) werden übergangen.
 */
final class P6SpyLog {

    // shortcut: MultiLineFormat und eigene customLogMessageFormat-Muster werden nicht erkannt – bei Bedarf nachrüsten.
    private static final Pattern HEAD = Pattern.compile("^([^|]+)\\|(\\d+)\\|(\\w*)\\|connection ?(-?\\d+)\\|");
    private static final Pattern STRING = Pattern.compile("'(?:[^']|'')*'");
    private static final Pattern NUMBER = Pattern.compile("(?<![\\w.])-?\\d+(?:\\.\\d+)?(?![\\w.])");
    private static final Pattern IN_LIST = Pattern.compile("\\(\\s*\\?(?:\\s*,\\s*\\?)+\\s*\\)");
    private static final Pattern SPACE = Pattern.compile("\\s+");

    /** Eine Zeile des Protokolls; {@code offset} ist die Byte-Position ihres Anfangs. */
    record Entry(long offset, String time, long millis, String category, String connection, String prepared,
                 String sql) {

        /** SQL ohne Werte – fasst gleiche Statements mit verschiedenen Parametern zusammen. */
        String key() {
            return normalize(prepared.isEmpty() ? sql : prepared);
        }
    }

    /** Gelesene Einträge, {@code end} = Byte-Position für den nächsten Aufruf. */
    record Chunk(List<Entry> entries, long start, long end, boolean skipped, boolean rotated) {
    }

    private P6SpyLog() {
    }

    /**
     * @param since    Byte-Position aus einem früheren Aufruf ({@code null} = vom Dateiende her lesen)
     * @param maxBytes höchstens so viele Bytes vom Ende her
     */
    static Chunk read(Path file, Long since, long maxBytes) {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = ch.size();
            boolean rotated = since != null && since > size;
            long from = since == null || rotated ? 0 : since;
            boolean skipped = size - from > maxBytes;
            long start = skipped ? size - maxBytes : from;
            ByteBuffer buf = ByteBuffer.allocate((int) (size - start));
            while (buf.hasRemaining() && ch.read(buf, start + buf.position()) >= 0) {
                // bis voll lesen
            }
            byte[] bytes = buf.array();
            int pos = 0;
            if (skipped) {
                // angeschnittene erste Zeile überspringen
                while (pos < bytes.length && bytes[pos] != '\n') {
                    pos++;
                }
                pos++;
            }
            List<Entry> entries = new ArrayList<>();
            int end = pos;
            for (int i = pos; i < bytes.length; i++) {
                if (bytes[i] == '\n') {
                    Entry e = parse(start + pos, new String(bytes, pos, i - pos, StandardCharsets.UTF_8));
                    if (e != null) {
                        entries.add(e);
                    }
                    pos = i + 1;
                    end = pos;
                }
            }
            // eine unvollständige letzte Zeile schreibt die Anwendung gerade – beim nächsten Aufruf
            return new Chunk(entries, start, start + Math.min(end, bytes.length), skipped, rotated);
        } catch (IOException e) {
            throw new UncheckedIOException("p6spy-Protokoll " + file + " nicht lesbar: " + e.getMessage(), e);
        }
    }

    static Entry parse(long offset, String line) {
        String l = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
        var m = HEAD.matcher(l);
        if (!m.find()) {
            return null;
        }
        String rest = l.substring(m.end());
        String prepared;
        String sql;
        if (l.charAt(m.start(4) - 1) != ' ') {
            // CustomLineFormat: nur sqlSingleLine
            prepared = "";
            sql = rest;
        } else {
            if (rest.startsWith("url ")) {
                int bar = rest.indexOf('|');
                rest = bar < 0 ? "" : rest.substring(bar + 1);
            }
            int split = split(rest);
            prepared = split < 0 ? rest : rest.substring(0, split);
            sql = split < 0 ? "" : rest.substring(split + 1);
        }
        return new Entry(offset, m.group(1), Long.parseLong(m.group(2)), m.group(3), m.group(4), prepared.strip(),
                sql.strip());
    }

    /** Trennstrich zwischen prepared und sql – beide können selbst {@code |} enthalten ({@code ||}). */
    private static int split(String rest) {
        int first = rest.indexOf('|');
        for (int i = first; i >= 0; i = rest.indexOf('|', i + 1)) {
            if (normalize(rest.substring(0, i)).equals(normalize(rest.substring(i + 1)))) {
                return i;
            }
        }
        return first;
    }

    static String normalize(String sql) {
        String s = STRING.matcher(sql).replaceAll("?");
        s = NUMBER.matcher(s).replaceAll("?");
        s = IN_LIST.matcher(s).replaceAll("(?)");
        return SPACE.matcher(s).replaceAll(" ").strip();
    }
}
