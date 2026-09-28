package systems.grebe.devtools.mcp.modules.skills;

import java.util.List;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;

/** Schreibende Skill-Tools (nur registriert, wenn in der Konfiguration erlaubt). */
public class SkillWriteTools {

    private static final String NOTE = "Kurz, warum geändert wurde (erscheint in skills_history)";
    private static final String EXPECTED = "Optional: Revision, auf der die Änderung beruht (aus skills_view). "
            + "Weicht sie ab, wird abgelehnt statt fremde Änderungen zu überschreiben.";

    private final SkillService service;

    SkillWriteTools(SkillService service) {
        this.service = service;
    }

    @Tool(name = "create", description = "Legt einen neuen Skill an. Anlegen, wenn eine Aufgabe schwierig oder "
            + "mehrstufig war, Fehlversuche nötig waren, der Nutzer korrigiert hat oder ein nicht offensichtlicher "
            + "Ablauf gefunden wurde, der wiederkommen wird. Vorher mit skills_list prüfen, ob es schon einen "
            + "passenden gibt – dann skills_patch. Inhalt als Markdown: wann verwenden, Schritte, konkrete "
            + "Befehle/Tool-Aufrufe, Fallstricke, Prüfung. Lehren statt Protokoll, keine Geheimnisse."
            + ShellHints.SKILLS)
    public String create(
            @ToolParam(description = SkillReadTools.NAME) String name,
            @ToolParam(description = "Ein Satz, wann der Skill greift (max. 1024 Zeichen), z.B. 'Verwenden, wenn "
                    + "ein WildFly-Heap wächst: Leck mit jvm_heap/visualvm_heap_analyze eingrenzen.'") String description,
            @ToolParam(description = "Inhalt als Markdown (ohne Frontmatter)") String content,
            @ToolParam(required = false, description = "Kategorie, z.B. 'software-development', 'devops'") String category,
            @ToolParam(required = false, description = "Schlagwörter für die Suche, z.B. ['wildfly','heap']") List<String> tags) {
        return service.create(name, description, content, category, tags);
    }

    @Tool(name = "patch", description = "Ersetzt gezielt eine Textstelle im Skill-Inhalt oder – mit file_path – in "
            + "einer Zusatzdatei. Bevorzugter Weg für Ergänzungen und Korrekturen: old_string muss exakt und "
            + "eindeutig vorkommen (sonst replace_all=true). Zum Anhängen den letzten Abschnitt als old_string "
            + "nehmen und erweitert als new_string übergeben." + ShellHints.SKILLS)
    public String patch(
            @ToolParam(description = SkillReadTools.NAME) String name,
            @ToolParam(description = "Zu ersetzender Text, exakt wie in skills_view") String old_string,
            @ToolParam(description = "Neuer Text; leer = Stelle löschen") String new_string,
            @ToolParam(required = false, description = "true = alle Vorkommen ersetzen") Boolean replace_all,
            @ToolParam(required = false, description = "Zusatzdatei, z.B. 'references/api.md'; leer = Hauptinhalt") String file_path,
            @ToolParam(required = false, description = NOTE) String note,
            @ToolParam(required = false, description = EXPECTED) Integer expected_revision) {
        return service.patch(name, old_string, new_string, replace_all, file_path, note, expected_revision);
    }

    @Tool(name = "update", description = "Ersetzt Beschreibung, gesamten Inhalt, Kategorie und/oder Tags eines "
            + "Skills; nicht angegebene Felder bleiben. Für einzelne Stellen skills_patch verwenden – update nur für "
            + "grundlegende Überarbeitungen und nach vorherigem skills_view." + ShellHints.SKILLS)
    public String update(
            @ToolParam(description = SkillReadTools.NAME) String name,
            @ToolParam(required = false, description = "Neue Beschreibung") String description,
            @ToolParam(required = false, description = "Neuer vollständiger Inhalt (Markdown)") String content,
            @ToolParam(required = false, description = "Neue Kategorie; leerer Text entfernt sie") String category,
            @ToolParam(required = false, description = "Neue Tags (ersetzt alle); leere Liste entfernt sie") List<String> tags,
            @ToolParam(required = false, description = NOTE) String note,
            @ToolParam(required = false, description = EXPECTED) Integer expected_revision) {
        return service.update(name, description, content, category, tags, note, expected_revision);
    }

    @Tool(name = "write_file", description = "Legt eine Zusatzdatei eines Skills an oder überschreibt sie – für "
            + "längere Details, die nicht in jeden Aufruf gehören (API-Referenzen, Vorlagen, Skripte). Pfad beginnt "
            + "mit references/, templates/, scripts/ oder assets/. Im Hauptinhalt kurz darauf verweisen."
            + ShellHints.SKILLS)
    public String writeFile(
            @ToolParam(description = SkillReadTools.NAME) String name,
            @ToolParam(description = "Relativer Pfad, z.B. 'references/jdbc-urls.md'") String file_path,
            @ToolParam(description = "Dateiinhalt") String file_content,
            @ToolParam(required = false, description = NOTE) String note) {
        return service.writeFile(name, file_path, file_content, note);
    }

    @Tool(name = "remove_file", description = "Entfernt eine Zusatzdatei aus einem Skill." + ShellHints.SKILLS)
    public String removeFile(
            @ToolParam(description = SkillReadTools.NAME) String name,
            @ToolParam(description = "Relativer Pfad der Datei") String file_path,
            @ToolParam(required = false, description = NOTE) String note) {
        return service.removeFile(name, file_path, note);
    }
}
