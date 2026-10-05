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
                Memories sind dein Gedächtnis für frühere Aktionen: was bei einer konkreten Aufgabe getan, \
                entschieden und herausgefunden wurde – mit Ticket-/PR-Nummern, Ergebnis und Datum. Skills \
                beschreiben dagegen, *wie* ein Aufgabentyp abläuft (z.B. `ticket-review`); eine Memory hält einen \
                einzelnen Durchlauf fest und verweist mit `skill` auf den Skill, nach dem gearbeitet wurde.

                Vor einer Aufgabe:
                - Geht es um ein Ticket, einen PR, einen Fehler oder ein Thema, das schon einmal vorkam, mit \
                `memories_search` (Ticket-Key, Stichworte, `project`) nach früheren Aktionen suchen und Treffer mit \
                `memories_view` laden. Nach `skills_view` eines Skills liefert `memories_search` mit `skill=<name>` \
                frühere Durchläufe dieses Ablaufs.

                Nach einer Aufgabe – mit `memories_save` festhalten, wenn eine nennenswerte Aktion abgeschlossen \
                ist: Ticket reviewt oder bearbeitet, Fehler analysiert oder behoben, PR erstellt, Deployment, \
                Entscheidung mit dem Nutzer. `title` = eine Zeile, was getan wurde; `content` = Ausgangslage, \
                Vorgehen, Ergebnis, Begründung, offene Punkte; dazu `project`, `skill` und `reference` (Ticket-Key, \
                PR, Commit), soweit bekannt. Folgeaktionen zur selben Sache mit `memories_update` und `append` \
                nachtragen statt eine neue Memory anzulegen. Niemals Passwörter, Tokens oder andere Geheimnisse. \
                `memories_delete` nur auf ausdrücklichen Wunsch.""";
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
