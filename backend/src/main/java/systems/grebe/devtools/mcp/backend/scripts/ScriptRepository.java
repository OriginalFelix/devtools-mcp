package systems.grebe.devtools.mcp.backend.scripts;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring-Data-Repository für {@link Script}; wie bei den Skills ist jede Abfrage auf Eigentümer eingeschränkt. */
public interface ScriptRepository extends JpaRepository<Script, Long> {

    Optional<Script> findByOwnerAndName(String owner, String name);

    @Query("select s from Script s where s.owner in :owners order by s.name")
    List<Script> findByOwners(@Param("owners") List<String> owners);
}
