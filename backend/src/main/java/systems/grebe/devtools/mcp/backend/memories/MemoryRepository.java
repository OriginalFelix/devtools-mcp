package systems.grebe.devtools.mcp.backend.memories;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring-Data-Repository für {@link Memory}. Wie bei den Skills ist jede Abfrage auf den Eigentümer eingeschränkt –
 * bewusst kein {@code findById} im Service, damit kein Zugriff die Memories anderer Benutzer trifft. Die Suche baut
 * {@link MemoryService} als {@code Specification} (beliebig viele Suchbegriffe).
 */
public interface MemoryRepository extends JpaRepository<Memory, Long>, JpaSpecificationExecutor<Memory> {

    Optional<Memory> findByIdAndOwner(Long id, String owner);

    long countByOwner(String owner);

    @Query("select distinct lower(m.reference) from Memory m where m.owner = :owner and m.reference is not null")
    List<String> referencesOf(@Param("owner") String owner);
}
