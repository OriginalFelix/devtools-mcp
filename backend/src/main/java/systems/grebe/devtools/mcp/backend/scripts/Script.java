package systems.grebe.devtools.mcp.backend.scripts;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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
import systems.grebe.devtools.mcp.backend.skills.SkillOwner;
import systems.grebe.devtools.mcp.modules.scripts.ScriptViews;

/**
 * Ein Groovy-Skript, aus dem die Desktop-App zur Laufzeit ein Modul mit eigenen Tools macht. Der Name ist zugleich die
 * Modul-ID und damit das Tool-Präfix. Das Backend speichert nur Quelltext und die beim Speichern ermittelte
 * Beschreibung; jede Änderung erzeugt eine {@link ScriptRevision}.
 *
 * <p>Eigentümer wie bei den Skills: ein Benutzer ({@link #owner} = E-Mail) oder {@link SkillOwner#GLOBAL} für Vorlagen,
 * die alle Benutzer bekommen. Der Name ist je Eigentümer eindeutig; ein eigenes Skript verdeckt die Vorlage gleichen
 * Namens.
 */
@Entity
@Table(name = "script",
        uniqueConstraints = @UniqueConstraint(name = "uk_script_owner_name", columnNames = {"owner", "name"}),
        indexes = @Index(name = "ix_script_owner", columnList = "owner"))
public class Script {

    /** Spaltenbreite für den Quelltext. */
    public static final int CONTENT_COLUMN = 200_000;

    @Id
    @GeneratedValue
    private Long id;

    /** E-Mail des Benutzers (klein geschrieben) oder {@link SkillOwner#GLOBAL}. */
    @Column(nullable = false, length = SkillOwner.MAX_EMAIL)
    private String owner;

    @Column(nullable = false, length = 32)
    private String name;

    @Column(nullable = false, length = 1024)
    private String description;

    @Column(nullable = false, length = CONTENT_COLUMN)
    private String content;

    /** GROOVY, JAVA oder GHERKIN; leer bei Skripten von vor der Java-Unterstützung (= Groovy). */
    @Column(length = 10)
    private String language;

    /** Fachliche Revisionsnummer (1 = angelegt), steigt mit jeder Änderung. */
    @Column(nullable = false)
    private int revision;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Column(length = SkillOwner.MAX_EMAIL)
    private String updatedBy;

    /** Optimistische Sperre gegen gleichzeitige Änderungen aus mehreren Desktop-Apps. */
    @Version
    private long version;

    @OneToMany(mappedBy = "script", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("revision DESC")
    private List<ScriptRevision> revisions = new ArrayList<>();

    protected Script() {
        // JPA
    }

    Script(String owner, String name, ScriptViews.Language language, String description, String content, Instant now) {
        this.owner = owner;
        this.name = name;
        this.language = language.name();
        this.description = description;
        this.content = content;
        this.createdAt = now;
        this.updatedAt = now;
    }

    boolean isGlobal() {
        return SkillOwner.GLOBAL.equals(owner);
    }

    void change(ScriptViews.Language language, String description, String content) {
        this.language = language.name();
        this.description = description;
        this.content = content;
    }

    public ScriptViews.Language getLanguage() {
        return language == null ? ScriptViews.Language.GROOVY : ScriptViews.Language.valueOf(language);
    }

    /** Erhöht die Revision und hält den neuen Stand in der Historie fest. */
    ScriptRevision recordRevision(String action, String note, String changedBy, Instant now) {
        revision++;
        updatedAt = now;
        updatedBy = changedBy;
        ScriptRevision r = new ScriptRevision(this, revision, action, note, changedBy, content, now);
        revisions.add(r);
        return r;
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

    public String getDescription() {
        return description;
    }

    public String getContent() {
        return content;
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

    public String getUpdatedBy() {
        return updatedBy;
    }

    public List<ScriptRevision> getRevisions() {
        return revisions;
    }
}
