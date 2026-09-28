package systems.grebe.devtools.mcp.modules.skills;

import java.time.Instant;
import java.util.List;

/** Lesemodell für die Oberfläche – unabhängig von JPA-Entities und Lazy Loading. */
public final class SkillViews {

    private SkillViews() {
    }

    /** Zeile der Übersicht. */
    public record Summary(String name, String description, String category, List<String> tags, int revision,
                          long useCount, Instant lastUsedAt, Instant updatedAt, int fileCount) {
    }

    /** Zusatzdatei. */
    public record File(String path, String content, Instant updatedAt) {
    }

    /** Eintrag der Änderungshistorie. */
    public record Revision(int revision, String action, String note, Instant changedAt, String description,
                           String content) {
    }

    /** Vollständiger Skill für die Detailansicht. */
    public record Details(Summary summary, String content, Instant createdAt, List<File> files,
                          List<Revision> revisions) {
    }
}
