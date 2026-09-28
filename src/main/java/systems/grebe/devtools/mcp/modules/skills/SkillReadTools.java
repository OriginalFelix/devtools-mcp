package systems.grebe.devtools.mcp.modules.skills;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;

/** Lesende Skill-Tools: auflisten/suchen, laden, Historie. */
public class SkillReadTools {

    static final String NAME = "Skill-Name, z.B. 'wildfly-heap-leak' (Kleinbuchstaben, Ziffern, . _ -)";

    private final SkillService service;

    SkillReadTools(SkillService service) {
        this.service = service;
    }

    @Tool(name = "list", description = "Listet gespeicherte Skills (Name, Beschreibung, Kategorie, Tags), optional "
            + "gefiltert per Suchtext über Name, Beschreibung, Tags und Inhalt. VOR Beginn einer Aufgabe aufrufen und "
            + "passende Skills mit skills_view laden – sie enthalten erprobte Abläufe, Befehle, Fallstricke und "
            + "Vorlieben des Nutzers." + ShellHints.SKILLS)
    public String list(
            @ToolParam(required = false, description = "Suchtext (Groß-/Kleinschreibung egal), z.B. 'heap' oder 'gradle'") String query,
            @ToolParam(required = false, description = "Nur diese Kategorie, z.B. 'software-development'") String category) {
        return service.list(query, category);
    }

    @Tool(name = "view", description = "Lädt einen Skill vollständig (Metadaten, Inhalt, Liste der Zusatzdateien) "
            + "oder mit file_path eine einzelne Zusatzdatei. Die Anweisungen des Skills befolgen; ist er veraltet oder "
            + "lückenhaft, nach der Aufgabe mit skills_patch korrigieren." + ShellHints.SKILLS)
    public String view(
            @ToolParam(description = NAME) String name,
            @ToolParam(required = false, description = "Zusatzdatei, z.B. 'references/api.md'; leer = Hauptinhalt") String file_path) {
        return service.view(name, file_path);
    }

    @Tool(name = "history", description = "Änderungshistorie eines Skills (Revision, Zeitpunkt, Aktion, Notiz) oder "
            + "mit revision den damaligen Stand von Beschreibung und Inhalt." + ShellHints.SKILLS)
    public String history(
            @ToolParam(description = NAME) String name,
            @ToolParam(required = false, description = "Revisionsnummer; leer = Übersicht") Integer revision) {
        return service.history(name, revision);
    }
}
