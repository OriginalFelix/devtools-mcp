package systems.grebe.devtools.mcp.modules.memories;

import java.time.Instant;
import java.util.List;

/** Lesemodell für die Oberfläche – unabhängig von JPA-Entities. */
public final class MemoryViews {

    private MemoryViews() {
    }

    /**
     * Lebensdauer einer Memory. Standard ist {@link #PERMANENT}; {@link #TEMPORARY} nur, wenn LLM oder Nutzer es
     * ausdrücklich angeben (Zwischenstände, Notizen für die laufende Aufgabe). Temporäre Memories dürfen ohne die
     * Freigaben für dauerhafte Memories geändert und gelöscht werden.
     */
    public enum Type {
        PERMANENT, TEMPORARY;

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
     */
    public record Entry(long id, String title, String content, String project, String skill, String reference,
                        List<String> tags, Type type, Instant createdAt, Instant updatedAt) {

        public boolean temporary() {
            return type == Type.TEMPORARY;
        }
    }
}
