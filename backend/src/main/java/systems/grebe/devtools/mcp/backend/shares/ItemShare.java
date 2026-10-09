package systems.grebe.devtools.mcp.backend.shares;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import systems.grebe.devtools.mcp.backend.skills.SkillOwner;
import systems.grebe.devtools.mcp.modules.shares.ShareViews;

/**
 * Freigabe eines eigenen Skills oder einer eigenen Memory für einen Benutzer, eine Rolle oder alle. Liegt in der
 * Skill-Datenbank neben Skills und Memories; Benutzer und Rollen (Core-Datenbank) stehen als E-Mail bzw. Rollenname
 * darin – keine Fremdschlüssel über Datenbankgrenzen.
 */
@Entity
@Table(name = "item_share",
        uniqueConstraints = @UniqueConstraint(name = "uk_item_share",
                columnNames = {"kind", "itemId", "target", "name"}),
        indexes = {@Index(name = "ix_item_share_target", columnList = "kind, target, name"),
                @Index(name = "ix_item_share_item", columnList = "kind, itemId")})
public class ItemShare {

    /** Was geteilt ist. */
    public enum Kind { SKILL, MEMORY }

    @Id
    @GeneratedValue
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Kind kind;

    /** ID des Skills bzw. der Memory. */
    @Column(nullable = false)
    private Long itemId;

    /** E-Mail des Eigentümers, der geteilt hat. */
    @Column(nullable = false, length = SkillOwner.MAX_EMAIL)
    private String owner;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private ShareViews.Target target;

    /** E-Mail, Rollenname bzw. {@link ShareViews#EVERYONE}. */
    @Column(nullable = false, length = SkillOwner.MAX_EMAIL)
    private String name;

    @Column(nullable = false)
    private Instant createdAt;

    protected ItemShare() {
        // JPA
    }

    ItemShare(Kind kind, long itemId, String owner, ShareViews.Target target, String name, Instant now) {
        this.kind = kind;
        this.itemId = itemId;
        this.owner = owner;
        this.target = target;
        this.name = name;
        this.createdAt = now;
    }

    public ShareViews.Share view() {
        return new ShareViews.Share(target, name, createdAt);
    }

    public Long getItemId() {
        return itemId;
    }

    public ShareViews.Target getTarget() {
        return target;
    }

    public String getName() {
        return name;
    }
}
