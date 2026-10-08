package systems.grebe.devtools.mcp.backend.skills;

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
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

/**
 * Ein Skill: wiederverwendbares Vorgehen für einen Aufgabentyp (Ablauf, Befehle, Fallstricke, Vorlieben des Nutzers),
 * das das LLM nach einer gelösten Aufgabe festhält und bei ähnlichen Aufgaben wieder lädt. Entspricht einem
 * {@code SKILL.md} mit optionalen Zusatzdateien ({@link SkillFile}); jede Änderung erzeugt eine {@link SkillRevision}.
 *
 * <p>Jeder Skill gehört einem Benutzer ({@link #owner} = E-Mail) oder ist eine globale, schreibgeschützte Vorlage
 * ({@link SkillOwner#GLOBAL}). Der Name ist je Eigentümer eindeutig: Eine persönliche Kopie verdeckt die Vorlage
 * gleichen Namens.
 */
@Entity
@Table(name = "skill",
        uniqueConstraints = @UniqueConstraint(name = "uk_skill_owner_name", columnNames = {"owner", "name"}),
        indexes = @Index(name = "ix_skill_owner", columnList = "owner"))
public class Skill {

    /** Spaltenbreite für Markdown-Inhalte; die tatsächliche Obergrenze setzt die Modulkonfiguration. */
    public static final int CONTENT_COLUMN = 200_000;

    @Id
    @GeneratedValue
    private Long id;

    /** E-Mail des Benutzers (klein geschrieben) oder {@link SkillOwner#GLOBAL}. */
    @Column(nullable = false, length = SkillOwner.MAX_EMAIL)
    private String owner;

    @Column(nullable = false, length = 64)
    private String name;

    /** Bei einer persönlichen Kopie: Revision der globalen Vorlage, aus der sie entstanden ist. */
    private Integer templateRevision;

    @Column(nullable = false, length = 1024)
    private String description;

    @Column(length = 64)
    private String category;

    /** Kommagetrennt, normalisiert (klein, ohne Leerzeichen, ohne Duplikate). */
    @Column(length = 500)
    private String tags;

    /**
     * Registrierung: Tool-Namen, bei deren Aufruf der Skill greift, z.B. {@code ticket_get} oder {@code pr_*}
     * (kommagetrennt). Ruft das LLM ein solches Tool auf, weist der Server einmal je Session auf den Skill hin.
     */
    @Column(length = 500)
    private String triggers;

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

    Skill(String owner, String name, String description, String content, String category, String tags, Instant now) {
        this.owner = owner;
        this.name = name;
        this.description = description;
        this.content = content;
        this.category = category;
        this.tags = tags;
        this.createdAt = now;
        this.updatedAt = now;
        this.revision = 0;
    }

    /** Persönliche Kopie einer globalen Vorlage samt Zusatzdateien; die Historie beginnt neu. */
    Skill copyFor(String newOwner, Instant now) {
        Skill copy = new Skill(newOwner, name, description, content, category, tags, now);
        copy.triggers = triggers;
        copy.templateRevision = revision;
        files.forEach(f -> copy.addFile(f.copyTo(copy, now)));
        return copy;
    }

    /** Übernimmt Inhalt, Metadaten und Dateien eines anderen Skills (erneutes Veröffentlichen einer Vorlage). */
    void replaceWith(Skill source, Instant now) {
        description = source.description;
        content = source.content;
        category = source.category;
        tags = source.tags;
        triggers = source.triggers;
        // Dateien gleichen Pfads in place ändern: Hibernate fügt neue Zeilen vor dem Löschen der alten ein – ein
        // Leeren und Neuanlegen verletzte sonst uk_skill_file_path
        files.removeIf(f -> source.file(f.getPath()).isEmpty());
        for (SkillFile f : source.files) {
            file(f.getPath()).ifPresentOrElse(mine -> mine.replaceWith(f, now), () -> addFile(f.copyTo(this, now)));
        }
    }

    /** Macht aus einem persönlichen Skill eine globale Vorlage (Historie bleibt erhalten). */
    void makeGlobal() {
        owner = SkillOwner.GLOBAL;
        templateRevision = null;
    }

    /** Verknüpft einen eigenen Skill nach dem Veröffentlichen mit der Vorlage (er gilt dann als deren Kopie). */
    void linkTemplate(int templateRevision) {
        this.templateRevision = templateRevision;
    }

    boolean isGlobal() {
        return SkillOwner.GLOBAL.equals(owner);
    }

    /** Erhöht die Revision und hält den neuen Stand in der Historie fest. */
    SkillRevision recordRevision(String action, String note, String changedBy, Instant now) {
        revision++;
        updatedAt = now;
        SkillRevision r = new SkillRevision(this, revision, action, note, changedBy, description, content, now);
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

    List<String> triggerList() {
        return triggers == null || triggers.isBlank() ? List.of() : Arrays.asList(triggers.split(","));
    }

    public String getTriggers() {
        return triggers;
    }

    void setTriggers(String triggers) {
        this.triggers = triggers;
    }

    public Long getId() {
        return id;
    }

    public String getOwner() {
        return owner;
    }

    public String getName() {
        return name;
    }

    public Integer getTemplateRevision() {
        return templateRevision;
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
