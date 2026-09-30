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

/**
 * Zusatzdatei eines Skills, z.B. {@code references/api.md} oder {@code templates/config.yaml}: längere Details, die
 * nicht in jeden Skill-Aufruf gehören und bei Bedarf gezielt geladen werden.
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

    @Column(nullable = false)
    private Instant updatedAt;

    protected SkillFile() {
        // JPA
    }

    SkillFile(Skill skill, String path, String content, Instant now) {
        this.skill = skill;
        this.path = path;
        this.content = content;
        this.updatedAt = now;
    }

    void update(String content, Instant now) {
        this.content = content;
        this.updatedAt = now;
    }

    public String getPath() {
        return path;
    }

    public String getContent() {
        return content;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
