package systems.grebe.devtools.mcp.modules.skills;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring-Data-Repository für die Änderungshistorie; angelegt werden Revisionen über {@link Skill} (Cascade). */
public interface SkillRevisionRepository extends JpaRepository<SkillRevision, Long> {

    List<SkillRevision> findBySkillOrderByRevisionDesc(Skill skill);

    Optional<SkillRevision> findBySkillAndRevision(Skill skill, int revision);
}
