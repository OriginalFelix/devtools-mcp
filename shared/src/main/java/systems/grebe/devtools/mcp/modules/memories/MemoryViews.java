package systems.grebe.devtools.mcp.modules.memories;

import java.time.Instant;
import java.util.List;

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
     * @param owner     bei einer von einem anderen Benutzer geteilten Memory dessen E-Mail, sonst leer
     */
    public record Entry(long id, String title, String content, String project, String skill, String reference,
                        List<String> tags, Type type, Instant createdAt, Instant updatedAt, List<File> files,
                        String owner) {

        public Entry {
            files = files == null ? List.of() : List.copyOf(files);
        }

        /** Eigene Memory. */
        public Entry(long id, String title, String content, String project, String skill, String reference,
                     List<String> tags, Type type, Instant createdAt, Instant updatedAt, List<File> files) {
            this(id, title, content, project, skill, reference, tags, type, createdAt, updatedAt, files, null);
        }

        /** Ohne Dateien. */
        public Entry(long id, String title, String content, String project, String skill, String reference,
                     List<String> tags, Type type, Instant createdAt, Instant updatedAt) {
            this(id, title, content, project, skill, reference, tags, type, createdAt, updatedAt, List.of());
        }

        /** Von einem anderen Benutzer geteilt – schreibgeschützt. */
        public boolean shared() {
            return owner != null;
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
}
