package systems.grebe.devtools.mcp.modules.skills;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;

/** Löschen eines Skills (eigener Schalter, standardmäßig aus). */
public class SkillDeleteTools {

    private final SkillBackend service;

    SkillDeleteTools(SkillBackend service) {
        this.service = service;
    }

    @Tool(name = "delete", description = "Löscht einen eigenen Skill endgültig (bei einer Kopie gilt wieder die "
            + "Vorlage). Nur auf ausdrücklichen Wunsch; sonst skills_patch." + ShellHints.SKILLS)
    public String delete(@ToolParam(description = SkillReadTools.NAME) String name) {
        return service.delete(name);
    }
}
