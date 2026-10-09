package systems.grebe.devtools.mcp.modules.memories;

import java.util.List;
import java.util.Locale;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.api.MediaTypes;
import systems.grebe.devtools.mcp.core.FileSource;
import systems.grebe.devtools.mcp.core.LocalFiles;
import systems.grebe.devtools.mcp.core.ShellHints;

/**
 * Schreibende Memory-Tools. Immer registriert; ohne den Schalter {@link MemoriesModule#ALLOW_WRITE} nur für temporäre
 * Memories ({@code temporaryOnly}).
 */
public class MemoryWriteTools {

    static final String TYPE = "PERMANENT (Standard), TEMPORARY – nur, wenn ausdrücklich gewünscht oder für kurzlebige "
            + "Zwischenstände – oder INVOCATION: Rückruf, kurz was zu tun ist, wenn eine lang laufende Aktion fertig "
            + "ist; die ID an das Tool der Aktion geben (z.B. share_send invocation=…), nach der Zustellung wird sie "
            + "automatisch gelöscht";

    private final MemoryBackend service;
    private final int maxContentChars;
    private final boolean temporaryOnly;
    private final LocalFiles files;

    /** @param files woher {@code source_path} lesen darf */
    MemoryWriteTools(MemoryBackend service, int maxContentChars, boolean temporaryOnly, LocalFiles files) {
        this.service = service;
        this.maxContentChars = maxContentChars;
        this.temporaryOnly = temporaryOnly;
        this.files = files;
    }

    @Tool(name = "save", description = "Hält eine abgeschlossene Aktion als Memory fest. Nach Ticket-Review, "
            + "Fehleranalyse, Fix, PR, Deployment oder Entscheidung: was getan wurde, Ergebnis, Begründung, offene "
            + "Punkte – mit Ticket-/PR-Nummern. Folgeaktion zur selben Sache: memories_update mit append. Standard "
            + "dauerhaft; type=TEMPORARY nur ausdrücklich – temporäre Memories dürfen ohne Freigabe geändert und "
            + "gelöscht werden. type=INVOCATION für Rückrufe nach lang laufenden Aktionen. Keine Geheimnisse."
            + ShellHints.MEMORIES)
    public String save(
            @ToolParam(description = "Eine Zeile, was getan wurde, z.B. 'Ticket ABC-123 reviewt: Kriterien fehlen'") String title,
            @ToolParam(description = "Markdown: Ergebnis, Begründung, offene Punkte") String content,
            @ToolParam(required = false, description = "Projekt aus projects_list") String project,
            @ToolParam(required = false, description = "Skill, nach dem gearbeitet wurde, z.B. 'ticket-review'") String skill,
            @ToolParam(required = false, description = "Ticket-Key, PR oder Commit, z.B. 'ABC-123'") String reference,
            @ToolParam(required = false, description = "Schlagwörter") List<String> tags,
            @ToolParam(required = false, description = TYPE) String type) {
        MemoryViews.Type t = type(type);
        if (temporaryOnly && (t == null || !t.ephemeral())) {
            throw new IllegalArgumentException("Dauerhafte Memories anzulegen ist nicht freigegeben (Schalter "
                    + "„Anlegen und Nachtragen erlauben“ aus). Nur mit type=TEMPORARY bzw. INVOCATION – oder die Freigabe mit "
                    + "permissions_request(module='memories', setting='" + MemoriesModule.ALLOW_WRITE + "') anfragen.");
        }
        return service.save(title, content, t, project, skill, reference, tags, maxContentChars);
    }

    @Tool(name = "update", description = "Ergänzt eine Memory um Nachtrag oder Korrektur. append hängt einen "
            + "datierten Nachtrag an, content ersetzt den Inhalt; übrige Felder einzeln (leer = entfernen). type "
            + "ändert die Lebensdauer." + ShellHints.MEMORIES)
    public String update(
            @ToolParam(description = MemoryReadTools.ID) long id,
            @ToolParam(required = false, description = "Nachtrag") String append,
            @ToolParam(required = false, description = "Neuer Inhalt statt append") String content,
            @ToolParam(required = false, description = "Neuer Titel") String title,
            @ToolParam(required = false, description = "Neues Projekt") String project,
            @ToolParam(required = false, description = "Neuer Skill") String skill,
            @ToolParam(required = false, description = "Neuer Bezug") String reference,
            @ToolParam(required = false, description = "Neue Tags") List<String> tags,
            @ToolParam(required = false, description = "Neuer Typ: PERMANENT, TEMPORARY oder INVOCATION") String type) {
        return service.update(id, title, content, append, type(type), project, skill, reference, tags, temporaryOnly,
                maxContentChars);
    }

    @Tool(name = "attach_file", description = "Hängt eine Datei an eine Memory (Screenshot, Log, Export, "
            + "Dokument …) – beliebiger Inhalt, ohne Größengrenze; gleicher file_path ersetzt. Genau eines: "
            + "source_path (lokale Datei, beliebig groß), content_base64 (kleine Binärdatei) oder file_content (Text)."
            + ShellHints.MEMORIES)
    public String attachFile(
            @ToolParam(description = MemoryReadTools.ID) long id,
            @ToolParam(required = false, description = "Lokale Datei (absolut, aus freigegebenem Verzeichnis)")
            String source_path,
            @ToolParam(required = false, description = "Name in der Memory, z.B. 'screenshot.png'; leer bei "
                    + "source_path = Dateiname") String file_path,
            @ToolParam(required = false, description = "Dateiinhalt als Base64") String content_base64,
            @ToolParam(required = false, description = "Dateiinhalt als Text") String file_content,
            @ToolParam(required = false, description = "Medientyp, z.B. 'image/png'; leer = aus dem Namen raten")
            String media_type) {
        try (FileSource source = FileSource.of(file_content, content_base64, source_path, files)) {
            if ((file_path == null || file_path.isBlank()) && source.name().isEmpty()) {
                throw new IllegalArgumentException("'file_path' fehlt, z.B. 'notiz.md' oder 'screenshot.png'.");
            }
            String type = media_type == null || media_type.isBlank() ? null
                    : MediaTypes.orGuess(media_type, file_path);
            return service.attachFile(id, file_path, source.path(), type, temporaryOnly);
        }
    }

    @Tool(name = "remove_file", description = "Entfernt eine angehängte Datei von einer Memory." + ShellHints.MEMORIES)
    public String removeFile(
            @ToolParam(description = MemoryReadTools.ID) long id,
            @ToolParam(description = MemoryReadTools.FILE) String file_path) {
        return service.removeFile(id, file_path, temporaryOnly);
    }

    /** Typ aus der Tool-Eingabe, großzügig gelesen; leer = {@code null}. */
    static MemoryViews.Type type(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return switch (raw.strip().toLowerCase(Locale.ROOT)) {
            case "permanent", "dauerhaft" -> MemoryViews.Type.PERMANENT;
            case "temporary", "temp", "temporär", "temporaer" -> MemoryViews.Type.TEMPORARY;
            case "invocation", "callback", "rückruf", "rueckruf" -> MemoryViews.Type.INVOCATION;
            default -> throw new IllegalArgumentException("Unbekannter Typ '" + raw
                    + "': PERMANENT, TEMPORARY oder INVOCATION.");
        };
    }
}
