package systems.grebe.devtools.mcp.backend.skills;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import systems.grebe.devtools.mcp.api.MediaTypes;
import systems.grebe.devtools.mcp.backend.blobs.BlobStore;

/**
 * Zusatzdatei eines Skills, z.B. {@code references/api.md} oder {@code assets/logo.png}: längere Details, Vorlagen oder
 * beliebige Dateien, die nicht in jeden Skill-Aufruf gehören und bei Bedarf gezielt geladen werden.
 *
 * <p>Text liegt direkt in {@link #content} und lässt sich patchen. Ein <em>Anhang</em> (beliebiger Inhalt, ohne
 * Größengrenze) liegt in der {@link BlobStore Dateiablage}; dann verweist {@link #blob} auf ihn und {@code content}
 * bleibt leer (die Spalte ist in älteren Datenbanken {@code not null}).
 */
@Entity
@Table(name = "skill_file",
        uniqueConstraints = @UniqueConstraint(name = "uk_skill_file_path", columnNames = {"skill_id", "path"}))
public class SkillFile {

    @Id
    @GeneratedValue
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "skill_id", foreignKey = @ForeignKey(name = "fk_skill_file_skill"))
    private Skill skill;

    @Column(nullable = false, length = 200)
    private String path;

    @Column(nullable = false, length = Skill.CONTENT_COLUMN)
    private String content;

    /** SHA-256 des Inhalts in der Dateiablage; leer bei Text im Skill. */
    @Column(length = 64)
    private String blob;

    /** Größe des Anhangs in Bytes; leer bei Text im Skill. */
    @Column(name = "byte_size")
    private Long size;

    /** Medientyp des Anhangs; leer bei Text im Skill. */
    @Column(length = MediaTypes.MAX_LENGTH)
    private String mediaType;

    @Column(nullable = false)
    private Instant updatedAt;

    protected SkillFile() {
        // JPA
    }

    /** Textdatei. */
    SkillFile(Skill skill, String path, String content, Instant now) {
        this.skill = skill;
        this.path = path;
        update(content, now);
    }

    /** Anhang in der Dateiablage. */
    SkillFile(Skill skill, String path, BlobStore.Blob blob, String mediaType, Instant now) {
        this.skill = skill;
        this.path = path;
        attach(blob, mediaType, now);
    }

    /** Gleiche Datei für einen anderen Skill (Kopie einer Vorlage, Veröffentlichen). */
    SkillFile copyTo(Skill target, Instant now) {
        return inline() ? new SkillFile(target, path, content, now)
                : new SkillFile(target, path, new BlobStore.Blob(blob, size), mediaType, now);
    }

    /** Übernimmt den Inhalt einer anderen Datei (Text oder Anhang). */
    void replaceWith(SkillFile source, Instant now) {
        if (source.inline()) {
            update(source.content, now);
        } else {
            attach(new BlobStore.Blob(source.blob, source.size), source.mediaType, now);
        }
    }

    /** Ersetzt den Inhalt durch Text (auch einen bisherigen Anhang). */
    void update(String content, Instant now) {
        this.content = content;
        this.blob = null;
        this.size = null;
        this.mediaType = null;
        this.updatedAt = now;
    }

    /** Ersetzt den Inhalt durch einen Anhang (auch bisherigen Text). */
    void attach(BlobStore.Blob blob, String mediaType, Instant now) {
        this.content = "";
        this.blob = blob.sha();
        this.size = blob.size();
        this.mediaType = mediaType;
        this.updatedAt = now;
    }

    /** Text im Skill statt Anhang. */
    public boolean inline() {
        return blob == null;
    }

    public String getPath() {
        return path;
    }

    /** Text der Datei; leer bei einem Anhang. */
    public String getContent() {
        return content;
    }

    public String getBlob() {
        return blob;
    }

    /** Größe in Bytes (Text: UTF-8). */
    public long getSize() {
        return inline() ? content.getBytes(StandardCharsets.UTF_8).length : size;
    }

    public String getMediaType() {
        return mediaType != null ? mediaType : MediaTypes.guess(path);
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
