package systems.grebe.devtools.mcp.modules.memories;

import java.nio.file.Path;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.api.MediaTypes;
import systems.grebe.devtools.mcp.core.LocalFiles;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.ToolHints;

/** Lesende Memory-Tools: suchen und laden. */
@ToolHints(readOnly = true, openWorld = false)
public class MemoryReadTools {

    static final String ID = "Nummer der Memory, z.B. 12 für #12";
    static final String FILE = "Angehängte Datei, z.B. 'screenshot.png'";
    /** Angehängte Textdateien bis zu dieser Größe (Bytes) zeigt memories_view direkt, sonst speichert es sie. */
    static final int VIEW_MAX = 200_000;

    private final MemoryBackend service;
    private final LocalFiles files;
    private final Path downloads;

    /**
     * @param files     wohin {@code target_path} schreiben darf
     * @param downloads Ablage für Dateien ohne {@code target_path} (Datenordner der App)
     */
    MemoryReadTools(MemoryBackend service, LocalFiles files, Path downloads) {
        this.service = service;
        this.files = files;
        this.downloads = downloads;
    }

    @Tool(name = "search", description = "VOR einer Aufgabe: frühere Aktionen des Nutzers suchen. Durchsucht die "
            + "Memories (was bei früheren Aufgaben getan, entschieden, herausgefunden wurde) nach Ticket-Key oder "
            + "Stichworten; genau ein Treffer kommt direkt vollständig, sonst memories_view(id). Mit skill=<name> "
            + "frühere Durchläufe eines Skills. Nennt ggf. auch passende Skills." + ShellHints.MEMORIES)
    public String search(
            @ToolParam(required = false, description = "Ticket-Key oder Stichworte, z.B. 'ABC-123'") String query,
            @ToolParam(required = false, description = "Nur dieses Projekt") String project,
            @ToolParam(required = false, description = "Nur zu diesem Skill") String skill,
            @ToolParam(required = false, description = "Nur mit diesem Tag") String tag,
            @ToolParam(required = false, description = "Nur die letzten N Tage") Integer days,
            @ToolParam(required = false, description = "Max. Treffer (Standard 5)") Integer limit,
            @ToolParam(required = false, description = "Nur PERMANENT, TEMPORARY oder INVOCATION") String type) {
        return service.search(query, project, skill, tag, MemoryWriteTools.type(type), days, limit);
    }

    @Tool(name = "view", description = "Lädt eine Memory (frühere Aktion) vollständig. Mit file_path eine "
            + "angehängte Datei: Text kommt direkt, Binäres (Bild, PDF …) wird als lokale Datei gespeichert und ihr "
            + "Pfad genannt – mit target_path an einen bestimmten Ort." + ShellHints.MEMORIES)
    public String view(
            @ToolParam(description = ID) long id,
            @ToolParam(required = false, description = FILE) String file_path,
            @ToolParam(required = false, description = "Datei lokal speichern: Datei oder Verzeichnis (freigegeben)")
            String target_path) {
        boolean save = target_path != null && !target_path.isBlank();
        if (file_path == null || file_path.isBlank()) {
            if (save) {
                throw new IllegalArgumentException("target_path nur zusammen mit file_path (welche Datei).");
            }
            return service.view(id);
        }
        if (save) {
            String p = file_path.strip().replace('\\', '/');
            return service.exportFile(id, file_path, files.writable(target_path, p.substring(p.lastIndexOf('/') + 1)));
        }
        MemoryViews.File f = service.file(id, file_path).orElse(null);
        if (f == null || (MediaTypes.textual(f.mediaType()) && f.size() <= VIEW_MAX)) {
            return service.viewFile(id, file_path); // Text – bzw. die Fehlermeldung mit den vorhandenen Dateien
        }
        Path target = downloads.resolve("memories").resolve(String.valueOf(id)).resolve(f.path()).normalize();
        return service.exportFile(id, file_path, target) + " Nicht als Text anzeigbar – die Datei mit einem "
                + "passenden Werkzeug öffnen (Bilder und PDFs kann z.B. das Read-Tool lesen).";
    }
}
