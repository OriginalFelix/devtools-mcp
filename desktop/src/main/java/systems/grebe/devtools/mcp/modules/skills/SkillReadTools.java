package systems.grebe.devtools.mcp.modules.skills;

import java.nio.file.Path;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.api.MediaTypes;
import systems.grebe.devtools.mcp.core.LocalFiles;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.ToolHints;

/** Lesende Skill-Tools: auflisten/suchen, laden, Historie. */
@ToolHints(readOnly = true, openWorld = false)
public class SkillReadTools {

    static final String NAME = "Skill-Name, z.B. 'ticket-review'";
    static final String FILE = "Zusatzdatei, z.B. 'references/api.md'; leer = Hauptinhalt";
    /** Anhänge mit Text bis zu dieser Größe (Bytes) zeigt skills_view direkt, größere bzw. binäre speichert es. */
    static final int VIEW_MAX = 200_000;

    private final SkillBackend service;
    private final LocalFiles files;
    private final Path downloads;

    /**
     * @param files     wohin {@code target_path} schreiben darf
     * @param downloads Ablage für Anhänge ohne {@code target_path} (Datenordner der App)
     */
    SkillReadTools(SkillBackend service, LocalFiles files, Path downloads) {
        this.service = service;
        this.files = files;
        this.downloads = downloads;
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
            + "Fehler oder Lücken danach mit skills_patch korrigieren. Mit file_path eine Zusatzdatei: Text kommt "
            + "direkt, Binäres (Bild, PDF, Office …) wird als lokale Datei gespeichert und ihr Pfad genannt – mit "
            + "target_path an einen bestimmten Ort." + ShellHints.SKILLS)
    public String view(
            @ToolParam(description = NAME) String name,
            @ToolParam(required = false, description = FILE) String file_path,
            @ToolParam(required = false, description = "Zusatzdatei lokal speichern: Datei oder Verzeichnis "
                    + "(freigegeben)") String target_path) {
        boolean save = target_path != null && !target_path.isBlank();
        if (file_path == null || file_path.isBlank()) {
            if (save) {
                throw new IllegalArgumentException("target_path nur zusammen mit file_path (welche Zusatzdatei).");
            }
            return service.view(name, null);
        }
        if (save) {
            return service.exportFile(name, file_path, files.writable(target_path, fileName(file_path)));
        }
        SkillViews.File f = service.file(name, file_path).orElse(null);
        if (f == null || f.inline() || (MediaTypes.textual(f.mediaType()) && f.size() <= VIEW_MAX)) {
            return service.view(name, file_path); // Text – bzw. die Fehlermeldung mit den vorhandenen Dateien
        }
        Path target = downloads.resolve("skills").resolve(name.strip()).resolve(f.path()).normalize();
        return service.exportFile(name, file_path, target) + " Nicht als Text anzeigbar – die Datei mit einem "
                + "passenden Werkzeug öffnen (Bilder und PDFs kann z.B. das Read-Tool lesen).";
    }

    static String fileName(String filePath) {
        String p = filePath.strip().replace('\\', '/');
        return p.substring(p.lastIndexOf('/') + 1);
    }

    @Tool(name = "history", description = "Änderungshistorie eines Skills oder mit revision der damalige Stand."
            + ShellHints.SKILLS)
    public String history(
            @ToolParam(description = NAME) String name,
            @ToolParam(required = false, description = "Revision; leer = Übersicht") Integer revision) {
        return service.history(name, revision);
    }
}
