package systems.grebe.devtools.mcp.modules.skills;

import java.util.List;
import java.util.Optional;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.api.MediaTypes;
import systems.grebe.devtools.mcp.core.FileSource;
import systems.grebe.devtools.mcp.core.LocalFiles;
import systems.grebe.devtools.mcp.core.ShellHints;

/** Schreibende Skill-Tools (nur registriert, wenn in der Konfiguration erlaubt). */
public class SkillWriteTools {

    private static final String NOTE = "Kurz, warum geändert (für skills_history)";
    private static final String EXPECTED = "Revision aus skills_view; weicht sie ab, wird abgelehnt";
    private static final String TRIGGERS = "Tools, bei deren Aufruf der Server auf den Skill hinweist, z.B. "
            + "['ticket_get','pr_*']";

    private final SkillBackend service;
    private final int maxContentChars;
    private final LocalFiles files;

    /** @param files woher {@code source_path} lesen darf */
    SkillWriteTools(SkillBackend service, int maxContentChars, LocalFiles files) {
        this.service = service;
        this.maxContentChars = maxContentChars;
        this.files = files;
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
            + "references/, templates/, scripts/ oder assets/) – lange Details, Vorlagen oder beliebige Dateien "
            + "(Bilder, PDFs, Office, Archive …) ohne Größengrenze; im Inhalt darauf verweisen. Genau eines: "
            + "file_content (Text), content_base64 (kleine Binärdatei) oder source_path (lokale Datei, beliebig "
            + "groß). Text bleibt mit skills_patch änderbar, Binäres wird als Anhang gespeichert." + ShellHints.SKILLS)
    public String writeFile(
            @ToolParam(description = SkillReadTools.NAME) String name,
            @ToolParam(required = false, description = "Pfad, z.B. 'references/jdbc-urls.md' oder "
                    + "'assets/logo.png'; leer bei source_path = assets/<Dateiname>") String file_path,
            @ToolParam(required = false, description = "Dateiinhalt als Text") String file_content,
            @ToolParam(required = false, description = "Lokale Datei (absolut, aus freigegebenem Verzeichnis)")
            String source_path,
            @ToolParam(required = false, description = "Dateiinhalt als Base64") String content_base64,
            @ToolParam(required = false, description = "Medientyp, z.B. 'image/png'; leer = aus dem Pfad raten")
            String media_type,
            @ToolParam(required = false, description = NOTE) String note) {
        boolean textOnly = (source_path == null || source_path.isBlank())
                && (content_base64 == null || content_base64.isBlank());
        if (textOnly && file_content != null && file_content.length() <= maxContentChars
                && (media_type == null || media_type.isBlank())) {
            return service.writeFile(name, file_path, file_content, note, maxContentChars);
        }
        try (FileSource source = FileSource.of(file_content, content_base64, source_path, files)) {
            String path = file_path == null || file_path.isBlank() ? defaultPath(source.name()) : file_path;
            String type = MediaTypes.orGuess(media_type, path);
            Optional<String> text = source.text(type, maxContentChars);
            return text.isPresent() ? service.writeFile(name, path, text.get(), note, maxContentChars)
                    : service.attachFile(name, path, source.path(), type, note);
        }
    }

    private static String defaultPath(Optional<String> fileName) {
        String n = fileName.orElseThrow(() -> new IllegalArgumentException("'file_path' fehlt, z.B. "
                + "'references/api.md' oder 'assets/logo.png'."));
        return "assets/" + n.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    @Tool(name = "remove_file", description = "Entfernt eine Zusatzdatei aus einem Skill." + ShellHints.SKILLS)
    public String removeFile(
            @ToolParam(description = SkillReadTools.NAME) String name,
            @ToolParam(description = "Pfad der Datei") String file_path,
            @ToolParam(required = false, description = NOTE) String note) {
        return service.removeFile(name, file_path, note);
    }
}
