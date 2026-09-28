package systems.grebe.devtools.mcp.modules.skills;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring-Data-Repository für {@link Skill}. */
public interface SkillRepository extends JpaRepository<Skill, Long> {

    Optional<Skill> findByName(String name);

    boolean existsByName(String name);

    /**
     * Sucht über Name, Beschreibung, Tags und Inhalt (Groß-/Kleinschreibung egal), optional eingeschränkt auf eine
     * Kategorie. {@code null} bedeutet jeweils „kein Filter“; {@code pattern} ist bereits klein und mit {@code %}.
     */
    @Query("""
            select k from Skill k
            where (:category is null or k.category = :category)
              and (:pattern is null
                   or lower(k.name) like :pattern or lower(k.description) like :pattern
                   or lower(k.tags) like :pattern or lower(k.content) like :pattern)
            order by k.category nulls first, k.name""")
    List<Skill> search(@Param("pattern") String pattern, @Param("category") String category);

    /**
     * Zählt eine Nutzung per Bulk-Update: berührt die {@code @Version} nicht, damit Lesen nie mit einer gleichzeitigen
     * Änderung kollidiert.
     */
    @Modifying
    @Query("update Skill k set k.useCount = k.useCount + 1, k.lastUsedAt = :now where k.id = :id")
    int markUsed(@Param("id") Long id, @Param("now") java.time.Instant now);
}
