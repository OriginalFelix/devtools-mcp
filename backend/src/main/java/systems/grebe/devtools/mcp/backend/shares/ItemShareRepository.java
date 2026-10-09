package systems.grebe.devtools.mcp.backend.shares;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import systems.grebe.devtools.mcp.modules.shares.ShareViews;

/** Spring-Data-Repository für {@link ItemShare}. */
public interface ItemShareRepository extends JpaRepository<ItemShare, Long> {

    List<ItemShare> findByKindAndItemIdOrderByTargetAscNameAsc(ItemShare.Kind kind, Long itemId);

    Optional<ItemShare> findByKindAndItemIdAndTargetAndName(ItemShare.Kind kind, Long itemId,
                                                            ShareViews.Target target, String name);

    /**
     * IDs der Skills bzw. Memories anderer Eigentümer, die für den Benutzer freigegeben sind: direkt, über eine seiner
     * Rollen oder für alle. {@code roles} darf nicht leer sein (Platzhalter übergeben).
     */
    @Query("""
            select distinct s.itemId from ItemShare s
            where s.kind = :kind and s.owner <> :user
              and (s.target = :all
                   or (s.target = :userTarget and s.name = :user)
                   or (s.target = :roleTarget and s.name in :roles))""")
    List<Long> visibleItems(@Param("kind") ItemShare.Kind kind, @Param("user") String user,
                            @Param("roles") List<String> roles, @Param("all") ShareViews.Target all,
                            @Param("userTarget") ShareViews.Target userTarget,
                            @Param("roleTarget") ShareViews.Target roleTarget);

    @Modifying
    @Query("delete from ItemShare s where s.kind = :kind and s.itemId = :itemId")
    int deleteItem(@Param("kind") ItemShare.Kind kind, @Param("itemId") Long itemId);

    /** Rolle umbenannt: Freigaben wandern mit. */
    @Modifying
    @Transactional
    @Query("update ItemShare s set s.name = :to where s.target = :roleTarget and s.name = :from")
    int renameRole(@Param("from") String from, @Param("to") String to,
                   @Param("roleTarget") ShareViews.Target roleTarget);

    /** Rolle gelöscht: ihre Freigaben entfallen. */
    @Modifying
    @Transactional
    @Query("delete from ItemShare s where s.target = :roleTarget and s.name = :role")
    int deleteRole(@Param("role") String role, @Param("roleTarget") ShareViews.Target roleTarget);
}
