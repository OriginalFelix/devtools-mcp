package systems.grebe.devtools.mcp.modules.skills;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring-Data-Repository für {@link Skill}. Jede Abfrage ist auf Eigentümer eingeschränkt – es gibt bewusst kein
 * {@code findByName} ohne Eigentümer, damit kein Zugriff versehentlich die Skills anderer Benutzer trifft.
 */
public interface SkillRepository extends JpaRepository<Skill, Long> {

    Optional<Skill> findByOwnerAndName(String owner, String name);

    boolean existsByOwnerAndName(String owner, String name);

    long countByOwner(String owner);

    @Query("select k.name from Skill k where k.owner = :owner")
    List<String> namesOf(@Param("owner") String owner);

    /**
     * Sucht in den Skills der angegebenen Eigentümer (Benutzer und {@link SkillUser#GLOBAL}) über Name, Beschreibung,
     * Tags und Inhalt (Groß-/Kleinschreibung egal), optional eingeschränkt auf eine Kategorie. {@code null} bedeutet
     * jeweils „kein Filter“; {@code pattern} ist bereits klein und mit {@code %}. Verdeckte Vorlagen filtert der
     * Service.
     */
    @Query("""
            select k from Skill k
            where k.owner in :owners
              and (:category is null or k.category = :category)
              and (:pattern is null
                   or lower(k.name) like :pattern or lower(k.description) like :pattern
                   or lower(k.tags) like :pattern or lower(k.content) like :pattern)
            order by k.category nulls first, k.name""")
    List<Skill> search(@Param("owners") List<String> owners, @Param("pattern") String pattern,
                       @Param("category") String category);

    /**
     * Zählt eine Nutzung per Bulk-Update: berührt die {@code @Version} nicht, damit Lesen nie mit einer gleichzeitigen
     * Änderung kollidiert – auch nicht bei globalen Vorlagen, die viele Benutzer gleichzeitig laden.
     */
    @Modifying
    @Query("update Skill k set k.useCount = k.useCount + 1, k.lastUsedAt = :now where k.id = :id")
    int markUsed(@Param("id") Long id, @Param("now") Instant now);
}
