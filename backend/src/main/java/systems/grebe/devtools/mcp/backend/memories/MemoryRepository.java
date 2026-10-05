package systems.grebe.devtools.mcp.backend.memories;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Spring-Data-Repository für {@link Memory}. Wie bei den Skills ist jede Abfrage auf den Eigentümer eingeschränkt –
 * bewusst kein {@code findById} im Service, damit kein Zugriff die Memories anderer Benutzer trifft. Die Suche baut
 * {@link MemoryService} als {@code Specification} (beliebig viele Suchbegriffe).
 */
public interface MemoryRepository extends JpaRepository<Memory, Long>, JpaSpecificationExecutor<Memory> {

    Optional<Memory> findByIdAndOwner(Long id, String owner);

    long countByOwner(String owner);
}
