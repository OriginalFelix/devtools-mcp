package systems.grebe.devtools.mcp.api;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Medientypen der Dateianhänge von Skills und Memories: aus dem Dateinamen raten, Text von Binärem unterscheiden.
 * Text zeigen die Tools dem LLM direkt, Binäres speichern sie als Datei, die das LLM selbst öffnen kann.
 */
public final class MediaTypes {

    public static final String BINARY = "application/octet-stream";
    public static final String TEXT = "text/plain";
    /** Spaltenbreite. */
    public static final int MAX_LENGTH = 100;

    private static final Pattern VALID = Pattern.compile("[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*");

    private static final Map<String, String> BY_EXTENSION = Map.ofEntries(
            Map.entry("md", "text/markdown"), Map.entry("markdown", "text/markdown"), Map.entry("txt", TEXT),
            Map.entry("log", TEXT), Map.entry("ini", TEXT), Map.entry("cfg", TEXT), Map.entry("conf", TEXT),
            Map.entry("csv", "text/csv"), Map.entry("tsv", "text/tab-separated-values"),
            Map.entry("html", "text/html"), Map.entry("htm", "text/html"), Map.entry("css", "text/css"),
            Map.entry("js", "text/javascript"), Map.entry("mjs", "text/javascript"),
            Map.entry("ts", "text/x-typescript"), Map.entry("json", "application/json"),
            Map.entry("xml", "application/xml"), Map.entry("xsd", "application/xml"),
            Map.entry("yaml", "application/yaml"), Map.entry("yml", "application/yaml"),
            Map.entry("toml", "application/toml"), Map.entry("properties", "text/x-java-properties"),
            Map.entry("java", "text/x-java"), Map.entry("kt", "text/x-kotlin"), Map.entry("kts", "text/x-kotlin"),
            Map.entry("groovy", "text/x-groovy"), Map.entry("gradle", "text/x-groovy"),
            Map.entry("py", "text/x-python"), Map.entry("sh", "application/x-sh"), Map.entry("bat", TEXT),
            Map.entry("ps1", TEXT), Map.entry("sql", "application/sql"), Map.entry("feature", "text/x-gherkin"),
            Map.entry("svg", "image/svg+xml"), Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"), Map.entry("gif", "image/gif"), Map.entry("webp", "image/webp"),
            Map.entry("bmp", "image/bmp"), Map.entry("ico", "image/x-icon"), Map.entry("pdf", "application/pdf"),
            Map.entry("zip", "application/zip"), Map.entry("jar", "application/java-archive"),
            Map.entry("war", "application/java-archive"), Map.entry("gz", "application/gzip"),
            Map.entry("tar", "application/x-tar"), Map.entry("doc", "application/msword"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("xls", "application/vnd.ms-excel"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("ppt", "application/vnd.ms-powerpoint"),
            Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
            Map.entry("odt", "application/vnd.oasis.opendocument.text"),
            Map.entry("ods", "application/vnd.oasis.opendocument.spreadsheet"),
            Map.entry("mp3", "audio/mpeg"), Map.entry("wav", "audio/wav"), Map.entry("mp4", "video/mp4"),
            Map.entry("class", "application/java-vm"));

    private MediaTypes() {
    }

    /** Medientyp nach der Dateiendung; unbekannt = {@link #BINARY}. */
    public static String guess(String fileName) {
        String n = fileName == null ? "" : fileName.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1);
        int dot = n.lastIndexOf('.');
        return dot < 0 ? BINARY : BY_EXTENSION.getOrDefault(n.substring(dot + 1).toLowerCase(Locale.ROOT), BINARY);
    }

    /** Ob Inhalte dieses Typs als Text lesbar sind (Markdown, Quelltext, JSON, XML, YAML …). */
    public static boolean textual(String mediaType) {
        if (mediaType == null) {
            return false;
        }
        String t = mediaType.toLowerCase(Locale.ROOT);
        int semicolon = t.indexOf(';');
        if (semicolon >= 0) {
            t = t.substring(0, semicolon).strip();
        }
        return t.startsWith("text/") || t.endsWith("+json") || t.endsWith("+xml")
                || switch (t) {
                    case "application/json", "application/xml", "application/yaml", "application/toml",
                         "application/sql", "application/x-sh", "application/javascript" -> true;
                    default -> false;
                };
    }

    /**
     * Angegebener Medientyp, klein und geprüft; leer = aus dem Dateinamen geraten.
     *
     * @throws IllegalArgumentException bei ungültiger Schreibweise
     */
    public static String orGuess(String mediaType, String fileName) {
        if (mediaType == null || mediaType.isBlank()) {
            return guess(fileName);
        }
        String t = mediaType.strip().toLowerCase(Locale.ROOT);
        int semicolon = t.indexOf(';');
        String base = semicolon < 0 ? t : t.substring(0, semicolon).strip();
        if (!VALID.matcher(base).matches() || t.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("Ungültiger Medientyp '" + mediaType + "' – z.B. 'image/png' oder "
                    + "'application/pdf'; leer lassen zum Raten aus dem Dateinamen.");
        }
        return t;
    }

    /** Ob die Bytes gültiges UTF-8 ohne Nullbytes sind – dann lohnt es, sie als Text zu zeigen. */
    public static boolean looksLikeText(byte[] bytes) {
        for (byte b : bytes) {
            if (b == 0) {
                return false;
            }
        }
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    /** Größe für Menschen, z.B. {@code 12 KB}. */
    public static String size(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return bytes / 1024 + " KB";
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
