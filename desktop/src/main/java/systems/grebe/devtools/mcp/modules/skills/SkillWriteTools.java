package systems.grebe.devtools.mcp.modules.skills;

import java.util.List;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;

/** Schreibende Skill-Tools (nur registriert, wenn in der Konfiguration erlaubt). */
public class SkillWriteTools {

    private static final String NOTE = "Kurz, warum geändert (für skills_history)";
    private static final String EXPECTED = "Revision aus skills_view; weicht sie ab, wird abgelehnt";
    private static final String TRIGGERS = "Tools, bei deren Aufruf der Server auf den Skill hinweist, z.B. "
            + "['ticket_get','pr_*']";

    private final SkillBackend service;
    private final int maxContentChars;

    SkillWriteTools(SkillBackend service, int maxContentChars) {
        this.service = service;
        this.maxContentChars = maxContentChars;
    }

    @Tool(name = "create", description = "Speichert neu Gelerntes dauerhaft als Skill (Ablauf, Fix). Registriert "
            + "einen Aufgabentyp (z.B. 'ticket-review'), keinen Einzelfall – der gehört in memories_save. Vorher "
            + "skills_list; passt ein Skill, skills_patch. Inhalt (Markdown): wann, Schritte, Tool-Aufrufe, "
            + "Fallstricke, Prüfung. Keine Geheimnisse." + ShellHints.SKILLS)
    public String create(
            @ToolParam(description = "Name des Aufgabentyps, z.B. 'ticket-review' (a-z0-9._-)") String name,
            @ToolParam(description = "Ein Satz, wann der Skill greift") String description,
            @ToolParam(description = "Inhalt als Markdown") String content,
            @ToolParam(required = false, description = "Kategorie, z.B. 'software-development'") String category,
            @ToolParam(required = false, description = "Schlagwörter für die Suche") List<String> tags,
            @ToolParam(required = false, description = TRIGGERS) List<String> triggers) {
        return service.create(name, description, content, category, tags, triggers, maxContentChars);
    }

    @Tool(name = "patch", description = "Ergänzt einen Skill um Korrekturen und Workarounds. Ersetzt old_string "
            + "(exakt und eindeutig, sonst replace_all) durch new_string im Inhalt oder in file_path. Bei einer "
            + "globalen Vorlage entsteht automatisch eine persönliche Kopie." + ShellHints.SKILLS)
    public String patch(
            @ToolParam(description = SkillReadTools.NAME) String name,
            @ToolParam(description = "Zu ersetzender Text, exakt wie in skills_view") String old_string,
            @ToolParam(description = "Neuer Text; leer = löschen") String new_string,
            @ToolParam(required = false, description = "true = alle Vorkommen") Boolean replace_all,
            @ToolParam(required = false, description = SkillReadTools.FILE) String file_path,
            @ToolParam(required = false, description = NOTE) String note,
            @ToolParam(required = false, description = EXPECTED) Integer expected_revision) {
        return service.patch(name, old_string, new_string, replace_all, file_path, note, expected_revision,
                maxContentChars);
    }

    @Tool(name = "update", description = "Ersetzt Beschreibung, Inhalt, Kategorie, Tags und/oder Trigger eines "
            + "Skills; fehlende Felder bleiben. Für einzelne Stellen skills_patch." + ShellHints.SKILLS)
    public String update(
            @ToolParam(description = SkillReadTools.NAME) String name,
            @ToolParam(required = false, description = "Neue Beschreibung") String description,
            @ToolParam(required = false, description = "Neuer vollständiger Inhalt") String content,
            @ToolParam(required = false, description = "Neue Kategorie; leer = entfernen") String category,
            @ToolParam(required = false, description = "Neue Tags; leere Liste = entfernen") List<String> tags,
            @ToolParam(required = false, description = TRIGGERS + "; leere Liste = entfernen") List<String> triggers,
            @ToolParam(required = false, description = NOTE) String note,
            @ToolParam(required = false, description = EXPECTED) Integer expected_revision) {
        return service.update(name, description, content, category, tags, triggers, note, expected_revision,
                maxContentChars);
    }

    @Tool(name = "write_file", description = "Legt eine Zusatzdatei eines Skills an oder überschreibt sie (unter "
            + "references/, templates/, scripts/ oder assets/) – für lange Details; im Inhalt darauf verweisen."
            + ShellHints.SKILLS)
    public String writeFile(
            @ToolParam(description = SkillReadTools.NAME) String name,
            @ToolParam(description = "Pfad, z.B. 'references/jdbc-urls.md'") String file_path,
            @ToolParam(description = "Dateiinhalt") String file_content,
            @ToolParam(required = false, description = NOTE) String note) {
        return service.writeFile(name, file_path, file_content, note, maxContentChars);
    }

    @Tool(name = "remove_file", description = "Entfernt eine Zusatzdatei aus einem Skill." + ShellHints.SKILLS)
    public String removeFile(
            @ToolParam(description = SkillReadTools.NAME) String name,
            @ToolParam(description = "Pfad der Datei") String file_path,
            @ToolParam(required = false, description = NOTE) String note) {
        return service.removeFile(name, file_path, note);
    }
}
