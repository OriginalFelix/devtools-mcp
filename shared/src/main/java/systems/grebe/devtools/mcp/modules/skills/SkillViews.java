package systems.grebe.devtools.mcp.modules.skills;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import systems.grebe.devtools.mcp.api.MediaTypes;

/** Lesemodell für die Oberfläche – unabhängig von JPA-Entities und Lazy Loading. */
public final class SkillViews {

    private SkillViews() {
    }

    /** Herkunft eines sichtbaren Skills aus Sicht des aktuellen Benutzers. */
    public enum Scope {
        /** Eigener Skill. */
        OWN,
        /** Persönliche Kopie einer globalen Vorlage (verdeckt die Vorlage). */
        COPY,
        /** Globale, schreibgeschützte Vorlage. */
        GLOBAL
    }

    /**
     * Zeile der Übersicht. {@code templateRevision}: bei {@link Scope#COPY} die Revision der Vorlage beim Kopieren;
     * {@code currentTemplateRevision}: die aktuelle Revision der Vorlage (oder {@code null}, wenn sie inzwischen
     * zurückgezogen wurde); {@code triggers}: Tool-Namen bzw. Präfixe ({@code pr_*}), für die der Skill registriert ist.
     */
    public record Summary(String name, String description, String category, List<String> tags, int revision,
                          long useCount, Instant lastUsedAt, Instant updatedAt, int fileCount, Scope scope,
                          Integer templateRevision, Integer currentTemplateRevision, List<String> triggers) {

        public Summary {
            triggers = triggers == null ? List.of() : List.copyOf(triggers);
        }

        /** Ist der Skill für dieses Tool registriert (exakter Name oder Präfix mit {@code *})? */
        public boolean triggeredBy(String toolName) {
            return triggers.stream().anyMatch(t -> t.endsWith("*")
                    ? toolName.startsWith(t.substring(0, t.length() - 1)) : t.equals(toolName));
        }

        public boolean global() {
            return scope == Scope.GLOBAL;
        }

        /** Die Vorlage wurde nach dem Kopieren weiterentwickelt. */
        public boolean templateUpdated() {
            return scope == Scope.COPY && templateRevision != null && currentTemplateRevision != null
                    && currentTemplateRevision > templateRevision;
        }
    }

    /**
     * Zusatzdatei: Text direkt im Skill ({@code content}, mit {@code skills_patch} änderbar) oder ein Anhang mit
     * beliebigem Inhalt in der Dateiablage des Backends ({@code blob} = SHA-256, {@code content} leer).
     *
     * @param size Größe in Bytes
     */
    public record File(String path, String content, Instant updatedAt, long size, String mediaType, String blob) {

        /** Textdatei im Skill. */
        public File(String path, String content, Instant updatedAt) {
            this(path, content, updatedAt, content == null ? 0 : content.getBytes(StandardCharsets.UTF_8).length,
                    MediaTypes.guess(path), null);
        }

        /** Text im Skill statt Anhang in der Dateiablage. */
        public boolean inline() {
            return blob == null;
        }
    }

    /** Eintrag der Änderungshistorie. */
    public record Revision(int revision, String action, String note, String changedBy, Instant changedAt,
                           String description, String content) {
    }

    /** Vollständiger Skill für die Detailansicht. */
    public record Details(Summary summary, String content, Instant createdAt, List<File> files,
                          List<Revision> revisions) {
    }
}
