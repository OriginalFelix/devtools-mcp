package systems.grebe.devtools.mcp.backend.memories;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import systems.grebe.devtools.mcp.backend.skills.SkillOwner;

/**
 * Eine Memory: was bei einer früheren Aktion passiert ist – z.B. „Ticket ABC-123 reviewt: Akzeptanzkriterien fehlen,
 * an PO zurück“. Anders als ein Skill (wiederverwendbarer Ablauf für einen Aufgabentyp, etwa {@code ticket-review})
 * hält eine Memory einen konkreten Durchlauf mit seinen Einmal-Details fest: Ticket, Ergebnis, Entscheidung, Datum.
 * Über {@link #skill} verweist sie auf die Skill-Registrierung, nach der gearbeitet wurde.
 *
 * <p>Jede Memory gehört genau einem Benutzer ({@link #owner} = E-Mail, wie bei den Skills); globale Memories gibt es
 * nicht.
 */
@Entity
@Table(name = "memory", indexes = {
        @Index(name = "ix_memory_owner_created", columnList = "owner, createdAt"),
        @Index(name = "ix_memory_owner_skill", columnList = "owner, skill")})
public class Memory {

    /** Spaltenbreite für den Inhalt; die tatsächliche Obergrenze setzt die Modulkonfiguration. */
    public static final int CONTENT_COLUMN = 50_000;

    @Id
    @GeneratedValue
    private Long id;

    /** E-Mail des Benutzers (klein geschrieben). */
    @Column(nullable = false, length = SkillOwner.MAX_EMAIL)
    private String owner;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = CONTENT_COLUMN)
    private String content;

    /** Projektname aus {@code projects_list} (bei fremden Projekten {@code name@eigentümer}). */
    @Column(length = 100)
    private String project;

    /** Name des Skills, nach dem gearbeitet wurde, z.B. {@code ticket-review}. */
    @Column(length = 64)
    private String skill;

    /** Bezug wie Ticket-Key, PR-Nummer/-URL oder Commit. */
    @Column(length = 200)
    private String reference;

    /** Kommagetrennt, normalisiert (klein, ohne Leerzeichen, ohne Duplikate). */
    @Column(length = 500)
    private String tags;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Memory() {
        // JPA
    }

    Memory(String owner, String title, String content, String project, String skill, String reference, String tags,
           Instant now) {
        this.owner = owner;
        this.title = title;
        this.content = content;
        this.project = project;
        this.skill = skill;
        this.reference = reference;
        this.tags = tags;
        this.createdAt = now;
        this.updatedAt = now;
    }

    List<String> tagList() {
        return tags == null || tags.isBlank() ? List.of() : Arrays.asList(tags.split(","));
    }

    void touch(Instant now) {
        updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getOwner() {
        return owner;
    }

    public String getTitle() {
        return title;
    }

    void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    void setContent(String content) {
        this.content = content;
    }

    public String getProject() {
        return project;
    }

    void setProject(String project) {
        this.project = project;
    }

    public String getSkill() {
        return skill;
    }

    void setSkill(String skill) {
        this.skill = skill;
    }

    public String getReference() {
        return reference;
    }

    void setReference(String reference) {
        this.reference = reference;
    }

    public String getTags() {
        return tags;
    }

    void setTags(String tags) {
        this.tags = tags;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
