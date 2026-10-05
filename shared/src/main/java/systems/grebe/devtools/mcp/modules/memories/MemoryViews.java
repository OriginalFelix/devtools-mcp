package systems.grebe.devtools.mcp.modules.memories;

import java.time.Instant;
import java.util.List;

/** Lesemodell für die Oberfläche – unabhängig von JPA-Entities. */
public final class MemoryViews {

    private MemoryViews() {
    }

    /**
     * Eine Memory: was bei einer früheren Aktion passiert ist.
     *
     * @param project   Projekt aus {@code projects_list} oder leer
     * @param skill     Name des Skills (Skill-Registrierung), nach dem gearbeitet wurde, oder leer
     * @param reference Bezug wie Ticket-Key, PR, Commit oder leer
     */
    public record Entry(long id, String title, String content, String project, String skill, String reference,
                        List<String> tags, Instant createdAt, Instant updatedAt) {
    }
}
