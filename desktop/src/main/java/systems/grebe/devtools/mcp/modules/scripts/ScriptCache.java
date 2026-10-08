package systems.grebe.devtools.mcp.modules.scripts;

import java.util.List;

/**
 * Letzter Stand der Skripte für den Start ohne erreichbaren Team-Server: {@link ScriptManager} speichert nach jedem
 * erfolgreichen Abgleich und lädt beim Start daraus, solange das Backend nicht antwortet.
 */
public interface ScriptCache {

    /** Skript mit Quelltext, wie zuletzt vom Backend geladen. */
    record Entry(ScriptViews.Summary summary, String content) {
    }

    void store(List<Entry> scripts);

    /** Gespeicherter Stand; leer, wenn es keinen gibt (oder er zu einem anderen Server gehört). */
    List<Entry> load();
}
