package systems.grebe.devtools.mcp.modules.skills;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

/**
 * Ein Skill: wiederverwendbares Vorgehen für einen Aufgabentyp (Ablauf, Befehle, Fallstricke, Vorlieben des Nutzers),
 * das das LLM nach einer gelösten Aufgabe festhält und bei ähnlichen Aufgaben wieder lädt. Entspricht einem
 * {@code SKILL.md} mit optionalen Zusatzdateien ({@link SkillFile}); jede Änderung erzeugt eine {@link SkillRevision}.
 */
@Entity
@Table(name = "skill", uniqueConstraints = @UniqueConstraint(name = "uk_skill_name", columnNames = "name"))
public class Skill {

    /** Spaltenbreite für Markdown-Inhalte; die tatsächliche Obergrenze setzt die Modulkonfiguration. */
    static final int CONTENT_COLUMN = 200_000;

    @Id
    @GeneratedValue
    private Long id;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(nullable = false, length = 1024)
    private String description;

    @Column(length = 64)
    private String category;

    /** Kommagetrennt, normalisiert (klein, ohne Leerzeichen, ohne Duplikate). */
    @Column(length = 500)
    private String tags;

    @Column(nullable = false, length = CONTENT_COLUMN)
    private String content;

    /** Fachliche Revisionsnummer (1 = angelegt), steigt mit jeder Änderung. */
    @Column(nullable = false)
    private int revision;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    private Instant lastUsedAt;

    @Column(nullable = false)
    private long useCount;

    /** Optimistische Sperre gegen gleichzeitige Änderungen aus mehreren Client-Sessions. */
    @Version
    private long version;

    @OneToMany(mappedBy = "skill", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("path")
    private List<SkillFile> files = new ArrayList<>();

    @OneToMany(mappedBy = "skill", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("revision DESC")
    private List<SkillRevision> revisions = new ArrayList<>();

    protected Skill() {
        // JPA
    }

    Skill(String name, String description, String content, String category, String tags, Instant now) {
        this.name = name;
        this.description = description;
        this.content = content;
        this.category = category;
        this.tags = tags;
        this.createdAt = now;
        this.updatedAt = now;
        this.revision = 0;
    }

    /** Erhöht die Revision und hält den neuen Stand in der Historie fest. */
    SkillRevision recordRevision(String action, String note, Instant now) {
        revision++;
        updatedAt = now;
        SkillRevision r = new SkillRevision(this, revision, action, note, description, content, now);
        revisions.add(r);
        return r;
    }

    Optional<SkillFile> file(String path) {
        return files.stream().filter(f -> f.getPath().equals(path)).findFirst();
    }

    void addFile(SkillFile file) {
        files.add(file);
    }

    void removeFile(SkillFile file) {
        files.remove(file);
    }

    List<String> tagList() {
        return tags == null || tags.isBlank() ? List.of() : Arrays.asList(tags.split(","));
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    void setDescription(String description) {
        this.description = description;
    }

    public String getCategory() {
        return category;
    }

    void setCategory(String category) {
        this.category = category;
    }

    public String getTags() {
        return tags;
    }

    void setTags(String tags) {
        this.tags = tags;
    }

    public String getContent() {
        return content;
    }

    void setContent(String content) {
        this.content = content;
    }

    public int getRevision() {
        return revision;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public long getUseCount() {
        return useCount;
    }

    public List<SkillFile> getFiles() {
        return files;
    }

    public List<SkillRevision> getRevisions() {
        return revisions;
    }
}
