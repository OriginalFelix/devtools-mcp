package systems.grebe.devtools.mcp.modules.memories;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.backend.memories.Memory;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolModule;

/**
 * Episodisches Gedächtnis für das LLM: Memories halten fest, was bei früheren Aufgaben konkret passiert ist (Ticket
 * reviewt, Fehler behoben, Entscheidung getroffen). Ergänzt die Skills – die registrierten, wiederverwendbaren Abläufe
 * je Aufgabentyp (z.B. {@code ticket-review}) – um die einzelnen Durchläufe; eine Memory kann auf den Skill verweisen,
 * nach dem gearbeitet wurde. Die Memories liegen im Backend ({@link MemoryBackend}) und gehören der E-Mail des
 * Benutzerkontos.
 */
@Component
public class MemoriesModule implements ToolModule {

    public static final String ID = "memories";

    static final String ALLOW_WRITE = "allowWrite";
    static final String ALLOW_DELETE = "allowDelete";
    static final String MAX_CONTENT = "maxContentChars";
    static final int DEFAULT_MAX_CONTENT = 20_000;

    private final MemoryBackend memories;

    public MemoriesModule(MemoryBackend memories) {
        this.memories = memories;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Memories";
    }

    @Override
    public String description() {
        return "Gedächtnis für frühere Aktionen: Das LLM hält nach einer Aufgabe fest, was getan, entschieden und "
                + "herausgefunden wurde (z.B. ein Ticket-Review mit Ergebnis), und findet es später per Suche wieder. "
                + "Gespeichert im Backend (eingebettet oder Team-Server) je Benutzerkonto.";
    }

    @Override
    public String instructions() {
        return """
                Memories = was bei früheren Aufgaben konkret passiert ist (Ticket, Ergebnis, Entscheidung); `skill` \
                verweist auf den Ablauf (z.B. `ticket-review`), nach dem gearbeitet wurde.
                - Vor einer Aufgabe zu einem Ticket, PR, Fehler oder bekannten Thema: `memories_search` mit \
                Ticket-Key oder Stichworten. Genau ein Treffer kommt direkt vollständig, sonst `memories_view`. Mit \
                `skill=<name>` frühere Durchläufe eines Skills.
                - Nennt der Server „[DevTools] Frühere Aktionen …“ oder „Frühere Durchläufe …“, diese Memory laden \
                statt neu zu recherchieren.
                - Nach einer abgeschlossenen Aktion: `memories_save` (title = eine Zeile; content = Ergebnis, \
                Begründung, offene Punkte; dazu project, skill, reference). Folgeaktion zur selben Sache: \
                `memories_update` mit `append`. Keine Geheimnisse; `memories_delete` nur auf Wunsch.""";
    }

    @Override
    public boolean enabledByDefault() {
        return true;
    }

    @Override
    public int order() {
        return 55;
    }

    @Override
    public List<ConfigField> configSchema() {
        return List.of(
                ConfigField.of(ALLOW_WRITE, "Anlegen und Nachtragen erlauben", FieldType.BOOLEAN).withDefault("true")
                        .withHelp("save, update."),
                ConfigField.of(ALLOW_DELETE, "Löschen erlauben", FieldType.BOOLEAN).withDefault("false"),
                ConfigField.of(MAX_CONTENT, "Max. Zeichen je Memory", FieldType.INT)
                        .withDefault(String.valueOf(DEFAULT_MAX_CONTENT))
                        .withHelp("Obergrenze für den Inhalt einer Memory (inkl. Nachträgen)."));
    }

    @Override
    public List<ToolCallback> createTools(ModuleConfig config) {
        List<ToolCallback> tools = new ArrayList<>(List.of(ToolCallbacks.from(new MemoryReadTools(memories))));
        if (config.getBoolean(ALLOW_WRITE)) {
            tools.addAll(List.of(ToolCallbacks.from(new MemoryWriteTools(memories, maxContent(config)))));
        }
        if (config.getBoolean(ALLOW_DELETE)) {
            tools.addAll(List.of(ToolCallbacks.from(new MemoryDeleteTools(memories))));
        }
        return tools;
    }

    private static int maxContent(ModuleConfig config) {
        return Math.max(1_000, Math.min(Memory.CONTENT_COLUMN, config.getInt(MAX_CONTENT, DEFAULT_MAX_CONTENT)));
    }
}
