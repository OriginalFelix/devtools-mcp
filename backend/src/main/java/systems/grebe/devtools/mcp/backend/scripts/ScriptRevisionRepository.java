package systems.grebe.devtools.mcp.backend.scripts;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring-Data-Repository für die Historie; angelegt werden Revisionen über {@link Script} (Cascade). */
public interface ScriptRevisionRepository extends JpaRepository<ScriptRevision, Long> {

    List<ScriptRevision> findByScriptOrderByRevisionDesc(Script script);
}
