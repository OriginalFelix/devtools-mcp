package systems.grebe.devtools.mcp.modules.memories;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/** Lesemodell für die Oberfläche – unabhängig von JPA-Entities. */
public final class MemoryViews {

    private MemoryViews() {
    }

    /**
     * Lebensdauer einer Memory. Standard ist {@link #PERMANENT}; {@link #TEMPORARY} nur, wenn LLM oder Nutzer es
     * ausdrücklich angeben (Zwischenstände, Notizen für die laufende Aufgabe). {@link #INVOCATION} hält fest, was zu
     * tun ist, wenn eine lang laufende Aktion fertig ist (Rückruf über den Channel); die App löscht sie, sobald der
     * Rückruf an alle verbundenen LLMs zugestellt ist. Temporäre und Invocation-Memories dürfen ohne die Freigaben für
     * dauerhafte Memories geändert und gelöscht werden.
     */
    public enum Type {
        PERMANENT, TEMPORARY, INVOCATION;

        /** Kurzlebig: ohne Freigabe für dauerhafte Memories änderbar und löschbar. */
        public boolean ephemeral() {
            return this != PERMANENT;
        }

        /** Bezeichnung für Ausgaben, z.B. „temporär“. */
        public String label() {
            return switch (this) {
                case PERMANENT -> "dauerhaft";
                case TEMPORARY -> "temporär";
                case INVOCATION -> "Rückruf";
            };
        }

        /** {@code null} gilt als {@link #PERMANENT} (Standard, auch für Memories aus der Zeit vor dem Typ). */
        public static Type orDefault(Type type) {
            return type == null ? PERMANENT : type;
        }
    }

    /**
     * Eine Memory: was bei einer früheren Aktion passiert ist.
     *
     * @param project   Projekt aus {@code projects_list} oder leer
     * @param skill     Name des Skills (Skill-Registrierung), nach dem gearbeitet wurde, oder leer
     * @param reference Bezug wie Ticket-Key, PR, Commit oder leer
     * @param type      Lebensdauer; {@code null} gilt als {@link Type#PERMANENT}
     * @param files     angehängte Dateien
     */
    public record Entry(long id, String title, String content, String project, String skill, String reference,
                        List<String> tags, Type type, Instant createdAt, Instant updatedAt, List<File> files) {

        public Entry {
            files = files == null ? List.of() : List.copyOf(files);
        }

        /** Ohne Dateien. */
        public Entry(long id, String title, String content, String project, String skill, String reference,
                     List<String> tags, Type type, Instant createdAt, Instant updatedAt) {
            this(id, title, content, project, skill, reference, tags, type, createdAt, updatedAt, List.of());
        }

        public boolean temporary() {
            return type == Type.TEMPORARY;
        }

        public boolean invocation() {
            return type == Type.INVOCATION;
        }

        /** Ohne Freigabe für dauerhafte Memories änderbar (temporär oder Rückruf). */
        public boolean ephemeral() {
            return type != null && type.ephemeral();
        }
    }

    /**
     * An eine Memory angehängte Datei mit beliebigem Inhalt (Screenshot, Log, Export …), gespeichert in der Dateiablage
     * des Backends.
     *
     * @param size Größe in Bytes
     * @param blob SHA-256 des Inhalts
     */
    public record File(String path, long size, String mediaType, String blob, Instant updatedAt) {
    }

    // ------------------------------------------------------------------ Prüfer (vom Backend und der App gemeinsam genutzt)

    public static final Pattern FILE_PATH = Pattern.compile("[A-Za-z0-9._/-]+");
    public static final int MAX_FILE_PATH = 200;

    /** Relativer Pfad ohne {@code ..}, z.B. {@code screenshot.png} oder {@code logs/server.log}. */
    public static String normalizeFilePath(String path) {
        String p = path == null ? "" : path.strip().replace('\\', '/');
        boolean valid = !p.isEmpty() && p.length() <= MAX_FILE_PATH && FILE_PATH.matcher(p).matches()
                && Arrays.stream(p.split("/", -1)).noneMatch(x -> x.isEmpty() || x.equals(".")
                || x.equals(".."));
        if (!valid) {
            throw new IllegalArgumentException("Ungültiger Dateipfad '" + path + "': relativ, nur Buchstaben, Ziffern, "
                    + "'.', '_', '-' und '/', ohne '..', max. " + MAX_FILE_PATH + " Zeichen (z.B. 'screenshot.png').");
        }
        return p;
    }

    /** Dateiname der Quelle als Pfad in der Memory; unzulässige Zeichen werden zu {@code _}, lange Namen gekürzt. */
    public static String fileName(Path source) {
        String n = source.getFileName() == null ? "" : source.getFileName().toString();
        n = n.replaceAll("[^A-Za-z0-9._-]", "_");
        if (n.isEmpty() || n.chars().allMatch(c -> c == '.')) {
            n = "datei";
        }
        return n.length() > MAX_FILE_PATH ? n.substring(n.length() - MAX_FILE_PATH) : n;
    }
}
