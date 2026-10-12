package systems.grebe.devtools.mcp.modules.decide;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ToolHints;

/** Löschen gespeicherter Aufgaben (eigener Schalter). */
class DecideDeleteTools {

    private final TaskStore store;

    DecideDeleteTools(TaskStore store) {
        this.store = store;
    }

    @Tool(name = "task_delete", description = "Löscht eine gespeicherte Entscheidungsaufgabe samt Beispielen und "
            + "Kalibrierung endgültig.")
    @ToolHints(destructive = true, openWorld = false)
    public String taskDelete(@ToolParam(description = "Name der Aufgabe") String name) {
        store.require(name);
        store.delete(name);
        return "Aufgabe " + TaskStore.normalize(name) + " gelöscht.";
    }
}
