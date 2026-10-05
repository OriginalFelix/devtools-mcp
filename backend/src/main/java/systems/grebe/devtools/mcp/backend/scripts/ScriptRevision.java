package systems.grebe.devtools.mcp.backend.scripts;

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
import systems.grebe.devtools.mcp.backend.skills.SkillOwner;

/** Stand eines Skripts nach einer Änderung, damit sich nachvollziehen (und zurückholen) lässt, was geändert wurde. */
@Entity
@Table(name = "script_revision",
        uniqueConstraints = @UniqueConstraint(name = "uk_script_revision", columnNames = {"script_id", "revision"}))
public class ScriptRevision {

    @Id
    @GeneratedValue
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "script_id", foreignKey = @ForeignKey(name = "fk_script_revision_script"))
    private Script script;

    @Column(nullable = false)
    private int revision;

    /** create, update, publish */
    @Column(nullable = false, length = 20)
    private String action;

    @Column(length = 500)
    private String note;

    @Column(length = SkillOwner.MAX_EMAIL)
    private String changedBy;

    @Column(nullable = false, length = Script.CONTENT_COLUMN)
    private String content;

    @Column(nullable = false)
    private Instant changedAt;

    protected ScriptRevision() {
        // JPA
    }

    ScriptRevision(Script script, int revision, String action, String note, String changedBy, String content,
                   Instant changedAt) {
        this.script = script;
        this.revision = revision;
        this.action = action;
        this.note = note;
        this.changedBy = changedBy;
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

    public String getContent() {
        return content;
    }

    public Instant getChangedAt() {
        return changedAt;
    }
}
