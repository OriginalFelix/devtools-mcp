package systems.grebe.devtools.mcp.modules.memories;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.backend.memories.Memory;
import systems.grebe.devtools.mcp.config.DataHome;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.DelegatingToolCallback;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.core.LocalFiles;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolModule;
import systems.grebe.devtools.mcp.modules.skills.SkillsModule;
import systems.grebe.devtools.mcp.core.ToolBeans;

/**
 * Episodisches Gedächtnis für das LLM: Memories halten fest, was bei früheren Aufgaben konkret passiert ist (Ticket
 * reviewt, Fehler behoben, Entscheidung getroffen). Ergänzt die Skills – die registrierten, wiederverwendbaren Abläufe
 * je Aufgabentyp (z.B. {@code ticket-review}) – um die einzelnen Durchläufe; eine Memory kann auf den Skill verweisen,
 * nach dem gearbeitet wurde. Die Memories liegen im Backend ({@link MemoryBackend}) und gehören der E-Mail des
 * Benutzerkontos.
 *
 * <p>Memories sind dauerhaft, außer LLM oder Nutzer geben ausdrücklich {@code TEMPORARY} an – oder {@code INVOCATION}
 * für einen Rückruf nach einer lang laufenden Aktion ({@link systems.grebe.devtools.mcp.core.InvocationService}).
 * Temporäre und Rückruf-Memories dürfen ohne Freigabe angelegt, geändert und gelöscht werden: {@code save},
 * {@code update} und {@code delete} sind deshalb immer registriert; ohne den jeweiligen Schalter nur für diese.
 */
@Component
public class MemoriesModule implements ToolModule {

    public static final String ID = "memories";

    static final String ALLOW_WRITE = "allowWrite";
    static final String ALLOW_DELETE = "allowDelete";
    static final String MAX_CONTENT = "maxContentChars";
    static final int DEFAULT_MAX_CONTENT = 20_000;
    static final String FILE_DIRS = "fileDirectories";

    private final MemoryBackend memories;
    private final DataHome home;

    public MemoriesModule(MemoryBackend memories, DataHome home) {
        this.memories = memories;
        this.home = home;
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
                + "herausgefunden wurde (z.B. ein Ticket-Review mit Ergebnis), samt angehängter Dateien (Screenshots, "
                + "Logs … ohne Größengrenze), und findet es später per Suche wieder. "
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
                `memories_update` mit `append`. Keine Geheimnisse. Belege wie Screenshots, Logs oder Exporte mit \
                `memories_attach_file` anhängen (`source_path` für lokale Dateien, beliebig groß); `memories_view` mit \
                `file_path` liefert Text bzw. speichert Binäres lokal.
                - Typ: Standard dauerhaft. `type=TEMPORARY` nur, wenn der Nutzer es so will oder für kurzlebige \
                Zwischenstände; temporäre Memories ohne Rückfrage ändern und löschen (z.B. wenn erledigt), \
                dauerhafte mit `memories_delete` nur auf Wunsch.
                - `type=INVOCATION` (Rückruf): vor einer lang laufenden Aktion kurz festhalten, was zu tun ist, wenn                 sie fertig ist, und die ID an das Tool der Aktion geben (z.B. `share_send invocation=<id>`). Die App                 meldet das Ergebnis samt dieser Memory per Channel – auch an eine später gestartete Sitzung – und                 löscht sie danach selbst.""";
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
                        .withHelp("save, update, attach_file, remove_file für dauerhafte Memories; temporäre und "
                                + "Rückrufe gehen immer."),
                ConfigField.of(ALLOW_DELETE, "Löschen erlauben", FieldType.BOOLEAN).withDefault("false")
                        .withHelp("Dauerhafte Memories löschen; temporäre und Rückrufe gehen immer."),
                ConfigField.of(MAX_CONTENT, "Max. Zeichen je Memory", FieldType.INT)
                        .withDefault(String.valueOf(DEFAULT_MAX_CONTENT))
                        .withHelp("Obergrenze für den Inhalt einer Memory (inkl. Nachträgen). Angehängte Dateien "
                                + "haben keine Grenze."),
                ConfigField.of(FILE_DIRS, "Dateien anhängen aus und speichern in", FieldType.DIRECTORY_LIST)
                        .withHelp("Aus diesen Verzeichnissen (inkl. Unterverzeichnissen) darf das LLM Dateien an "
                                + "Memories hängen (source_path) und angehängte hineinspeichern (target_path); dazu "
                                + "die globalen „Freigaben“. Ohne target_path landen binäre Dateien in "
                                + "„attachments“ im Datenordner der App."));
    }

    @Override
    public Set<String> sharedDirectoryFields() {
        return Set.of(FILE_DIRS);
    }

    @Override
    public List<ToolCallback> createTools(ModuleConfig config) {
        LocalFiles files = new LocalFiles(config.getList(FILE_DIRS),
                "Module → Memories → „Dateien anhängen aus und speichern in“ oder global unter „Freigaben“");
        List<ToolCallback> tools = new ArrayList<>(ToolBeans.callbacks(
                new MemoryReadTools(memories, files, SkillsModule.attachments(home))));
        boolean write = config.getBoolean(ALLOW_WRITE);
        boolean delete = config.getBoolean(ALLOW_DELETE);
        // ohne Schalter nur temporäre Memories – die brauchen keine Freigabe
        noted(tools, ToolBeans.callbacks(new MemoryWriteTools(memories, maxContent(config), !write, files)), write,
                ALLOW_WRITE);
        noted(tools, ToolBeans.callbacks(new MemoryDeleteTools(memories, !delete)), delete, ALLOW_DELETE);
        return tools;
    }

    private static void noted(List<ToolCallback> tools, List<ToolCallback> callbacks, boolean permitted, String setting) {
        for (ToolCallback cb : callbacks) {
            tools.add(permitted ? cb : new TemporaryOnly(cb, " NUR temporäre Memories und Rückrufe – dauerhafte sind "
                    + "nicht freigegeben (permissions_request mit module='memories', setting='" + setting + "')."));
        }
    }

    /** Hängt an die Beschreibung an, dass das Tool nur temporäre Memories anfasst. */
    private record TemporaryOnly(ToolCallback delegate, String note) implements DelegatingToolCallback {
        @Override
        public ToolDefinition getToolDefinition() {
            ToolDefinition d = delegate.getToolDefinition();
            return ToolDefinition.builder().name(d.name()).description(d.description() + note)
                    .inputSchema(d.inputSchema()).build();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return delegate.getToolMetadata();
        }

        @Override
        public String call(String toolInput) {
            return delegate.call(toolInput);
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            return delegate.call(toolInput, toolContext);
        }
    }

    private static int maxContent(ModuleConfig config) {
        return Math.max(1_000, Math.min(Memory.CONTENT_COLUMN, config.getInt(MAX_CONTENT, DEFAULT_MAX_CONTENT)));
    }
}
