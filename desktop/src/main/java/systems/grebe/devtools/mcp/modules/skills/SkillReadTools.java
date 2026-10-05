package systems.grebe.devtools.mcp.modules.skills;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;

/** Lesende Skill-Tools: auflisten/suchen, laden, Historie. */
public class SkillReadTools {

    static final String NAME = "Skill-Name, z.B. 'ticket-review'";
    static final String FILE = "Zusatzdatei, z.B. 'references/api.md'; leer = Hauptinhalt";

    private final SkillBackend service;

    SkillReadTools(SkillBackend service) {
        this.service = service;
    }

    @Tool(name = "list", description = "VOR einer Aufgabe: gespeicherte Skills des Nutzers suchen. "
            + "Skills sind registrierte Abläufe je Aufgabentyp (Schritte, Tool-Aufrufe, Fallstricke, Vorlieben). "
            + "Sucht in Name, Beschreibung, Tags und Inhalt; genau ein Treffer kommt direkt mit Inhalt, sonst den "
            + "passenden mit skills_view laden. Nennt ggf. auch passende Memories." + ShellHints.SKILLS)
    public String list(
            @ToolParam(required = false, description = "1–3 Stichworte, z.B. 'ticket review'") String query,
            @ToolParam(required = false, description = "Nur diese Kategorie") String category) {
        return service.list(query, category);
    }

    @Tool(name = "view", description = "Lädt einen gespeicherten Skill (erprobter Ablauf). Anweisungen befolgen; "
            + "Fehler oder Lücken danach mit skills_patch korrigieren." + ShellHints.SKILLS)
    public String view(
            @ToolParam(description = NAME) String name,
            @ToolParam(required = false, description = FILE) String file_path) {
        return service.view(name, file_path);
    }

    @Tool(name = "history", description = "Änderungshistorie eines Skills oder mit revision der damalige Stand."
            + ShellHints.SKILLS)
    public String history(
            @ToolParam(description = NAME) String name,
            @ToolParam(required = false, description = "Revision; leer = Übersicht") Integer revision) {
        return service.history(name, revision);
    }
}
