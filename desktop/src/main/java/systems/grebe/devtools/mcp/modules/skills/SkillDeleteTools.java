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

    @Tool(name = "delete", description = "Löscht einen eigenen Skill samt Zusatzdateien und Historie endgültig; bei "
            + "einer persönlichen Kopie gilt danach wieder die globale Vorlage. Globale Vorlagen sind nicht löschbar. "
            + "Nur auf ausdrücklichen Wunsch des Nutzers; veraltete Skills besser mit skills_patch korrigieren."
            + ShellHints.SKILLS)
    public String delete(@ToolParam(description = SkillReadTools.NAME) String name) {
        return service.delete(name);
    }
}
