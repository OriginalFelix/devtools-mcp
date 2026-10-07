package systems.grebe.devtools.mcp.modules.memories;

import java.util.List;
import java.util.Locale;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;

/**
 * Schreibende Memory-Tools. Immer registriert; ohne den Schalter {@link MemoriesModule#ALLOW_WRITE} nur für temporäre
 * Memories ({@code temporaryOnly}).
 */
public class MemoryWriteTools {

    static final String TYPE = "PERMANENT (Standard) oder TEMPORARY – temporär nur, wenn ausdrücklich gewünscht oder "
            + "für kurzlebige Zwischenstände";

    private final MemoryBackend service;
    private final int maxContentChars;
    private final boolean temporaryOnly;

    MemoryWriteTools(MemoryBackend service, int maxContentChars, boolean temporaryOnly) {
        this.service = service;
        this.maxContentChars = maxContentChars;
        this.temporaryOnly = temporaryOnly;
    }

    @Tool(name = "save", description = "Hält eine abgeschlossene Aktion als Memory fest. Nach Ticket-Review, "
            + "Fehleranalyse, Fix, PR, Deployment oder Entscheidung: was getan wurde, Ergebnis, Begründung, offene "
            + "Punkte – mit Ticket-/PR-Nummern. Folgeaktion zur selben Sache: memories_update mit append. Standard "
            + "dauerhaft; type=TEMPORARY nur ausdrücklich – temporäre Memories dürfen ohne Freigabe geändert und "
            + "gelöscht werden. Keine Geheimnisse." + ShellHints.MEMORIES)
    public String save(
            @ToolParam(description = "Eine Zeile, was getan wurde, z.B. 'Ticket ABC-123 reviewt: Kriterien fehlen'") String title,
            @ToolParam(description = "Markdown: Ergebnis, Begründung, offene Punkte") String content,
            @ToolParam(required = false, description = "Projekt aus projects_list") String project,
            @ToolParam(required = false, description = "Skill, nach dem gearbeitet wurde, z.B. 'ticket-review'") String skill,
            @ToolParam(required = false, description = "Ticket-Key, PR oder Commit, z.B. 'ABC-123'") String reference,
            @ToolParam(required = false, description = "Schlagwörter") List<String> tags,
            @ToolParam(required = false, description = TYPE) String type) {
        MemoryViews.Type t = type(type);
        if (temporaryOnly && t != MemoryViews.Type.TEMPORARY) {
            throw new IllegalArgumentException("Dauerhafte Memories anzulegen ist nicht freigegeben (Schalter "
                    + "„Anlegen und Nachtragen erlauben“ aus). Nur mit type=TEMPORARY – oder die Freigabe mit "
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
            @ToolParam(required = false, description = "Neuer Typ: PERMANENT oder TEMPORARY") String type) {
        return service.update(id, title, content, append, type(type), project, skill, reference, tags, temporaryOnly,
                maxContentChars);
    }

    /** Typ aus der Tool-Eingabe, großzügig gelesen; leer = {@code null}. */
    static MemoryViews.Type type(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return switch (raw.strip().toLowerCase(Locale.ROOT)) {
            case "permanent", "dauerhaft" -> MemoryViews.Type.PERMANENT;
            case "temporary", "temp", "temporär", "temporaer" -> MemoryViews.Type.TEMPORARY;
            default -> throw new IllegalArgumentException("Unbekannter Typ '" + raw + "': PERMANENT oder TEMPORARY.");
        };
    }
}
