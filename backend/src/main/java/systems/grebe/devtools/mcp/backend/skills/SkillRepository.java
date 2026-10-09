package systems.grebe.devtools.mcp.backend.skills;

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
     * Sucht in den Skills der angegebenen Eigentümer (Benutzer und {@link SkillOwner#GLOBAL}) über Name, Beschreibung,
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
     * Wie {@link #search}, aber über freigegebene Skills anderer Eigentümer ({@code ids} nicht leer); bei gleichem
     * Namen zuerst der Skill des alphabetisch ersten Eigentümers.
     */
    @Query("""
            select k from Skill k
            where k.id in :ids
              and (:category is null or k.category = :category)
              and (:pattern is null
                   or lower(k.name) like :pattern or lower(k.description) like :pattern
                   or lower(k.tags) like :pattern or lower(k.content) like :pattern)
            order by k.name, k.owner""")
    List<Skill> searchShared(@Param("ids") List<Long> ids, @Param("pattern") String pattern,
                             @Param("category") String category);

    @Query("select k from Skill k where k.id in :ids and k.name = :name order by k.owner")
    List<Skill> findShared(@Param("ids") List<Long> ids, @Param("name") String name);

    /** Eigentümer der freigegebenen Skills ({@code ids}), deren Zusatzdateien auf diesen Inhalt verweisen. */
    @Query("select distinct f.skill.owner from SkillFile f where f.blob = :blob and f.skill.id in :ids")
    List<String> ownersOfBlob(@Param("ids") List<Long> ids, @Param("blob") String blob);

    /**
     * Zählt eine Nutzung per Bulk-Update: berührt die {@code @Version} nicht, damit Lesen nie mit einer gleichzeitigen
     * Änderung kollidiert – auch nicht bei globalen Vorlagen, die viele Benutzer gleichzeitig laden.
     */
    /** Wie oft Zusatzdateien von Skills des Eigentümers auf diesen Inhalt der Dateiablage verweisen. */
    @Query("select count(f) from SkillFile f where f.skill.owner = :owner and f.blob = :blob")
    long countFileReferences(@Param("owner") String owner, @Param("blob") String blob);

    /** Alle verwendeten Inhalte der Dateiablage als Paare (Eigentümer, SHA-256). */
    @Query("select f.skill.owner, f.blob from SkillFile f where f.blob is not null")
    List<Object[]> fileReferences();

    @Modifying
    @Query("update Skill k set k.useCount = k.useCount + 1, k.lastUsedAt = :now where k.id = :id")
    int markUsed(@Param("id") Long id, @Param("now") Instant now);
}
