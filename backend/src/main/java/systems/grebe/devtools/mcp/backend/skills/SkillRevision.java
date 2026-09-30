package systems.grebe.devtools.mcp.backend.skills;

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

/** Stand eines Skills nach einer Änderung (Beschreibung und Inhalt), damit Änderungen des LLM nachvollziehbar bleiben. */
@Entity
@Table(name = "skill_revision",
        uniqueConstraints = @UniqueConstraint(name = "uk_skill_revision", columnNames = {"skill_id", "revision"}))
public class SkillRevision {

    @Id
    @GeneratedValue
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "skill_id", foreignKey = @ForeignKey(name = "fk_skill_revision_skill"))
    private Skill skill;

    @Column(nullable = false)
    private int revision;

    /** create, update, patch, write_file, remove_file, adopt, publish */
    @Column(nullable = false, length = 20)
    private String action;

    @Column(length = 500)
    private String note;

    /** Wer geändert hat (E-Mail); bei Revisionen vor dem User-Scoping leer. */
    @Column(length = SkillOwner.MAX_EMAIL)
    private String changedBy;

    @Column(nullable = false, length = 1024)
    private String description;

    @Column(nullable = false, length = Skill.CONTENT_COLUMN)
    private String content;

    @Column(nullable = false)
    private Instant changedAt;

    protected SkillRevision() {
        // JPA
    }

    SkillRevision(Skill skill, int revision, String action, String note, String changedBy, String description,
                  String content, Instant changedAt) {
        this.skill = skill;
        this.revision = revision;
        this.action = action;
        this.note = note;
        this.changedBy = changedBy;
        this.description = description;
        this.content = content;
        this.changedAt = changedAt;
    }

    public int getRevision() {
        return revision;
    }

    public String getAction() {
        return action;
    }

    public String getNote() {
        return note;
    }

    public String getChangedBy() {
        return changedBy;
    }

    public String getDescription() {
        return description;
    }

    public String getContent() {
        return content;
    }

    public Instant getChangedAt() {
        return changedAt;
    }
}
