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

    /** Wie oft angehängte Dateien von Memories des Eigentümers auf diesen Inhalt der Dateiablage verweisen. */
    @Query("select count(f) from MemoryFile f where f.memory.owner = :owner and f.blob = :blob")
    long countFileReferences(@Param("owner") String owner, @Param("blob") String blob);

    /** Eigentümer der freigegebenen Memories ({@code ids}), deren angehängte Dateien auf diesen Inhalt verweisen. */
    @Query("select distinct f.memory.owner from MemoryFile f where f.blob = :blob and f.memory.id in :ids")
    List<String> ownersOfBlob(@Param("ids") List<Long> ids, @Param("blob") String blob);

    /** Alle verwendeten Inhalte der Dateiablage als Paare (Eigentümer, SHA-256). */
    @Query("select f.memory.owner, f.blob from MemoryFile f")
    List<Object[]> fileReferences();
}
