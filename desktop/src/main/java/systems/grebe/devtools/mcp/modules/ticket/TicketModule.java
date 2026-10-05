package systems.grebe.devtools.mcp.modules.ticket;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.config.SettingsStore;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.ConfigGroup;
import systems.grebe.devtools.mcp.core.ConnectionTestResult;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolModule;
import systems.grebe.devtools.mcp.core.ToolRegistry;
import systems.grebe.devtools.mcp.modules.classify.ClassifyModule;
import systems.grebe.devtools.mcp.modules.classify.TaskClassifier;
import systems.grebe.devtools.mcp.modules.ticket.spi.TicketProvider;
import systems.grebe.devtools.mcp.modules.ticket.spi.TicketSystem;

/**
 * Ticket-Systeme über austauschbare Provider ({@link TicketProvider}, per ServiceLoader): Boards, Suche, Ticket lesen;
 * schreibende Tools je Schalter. Mitgeliefert: Jira, GitHub, GitLab, YouTrack, OpenProject.
 */
@Component
public class TicketModule implements ToolModule {

    public static final String ID = "ticket";

    static final String DEFAULT_PROVIDER = "defaultProvider";
    static final String DEFAULT_PROJECT = "defaultProject";
    static final String TIMEOUT = "timeoutSeconds";
    static final String COMMENTS = "comments";
    static final String MAX_DESCRIPTION = "maxDescriptionChars";
    static final String MAX_PER_COLUMN = "maxPerColumn";
    static final String MAX_LINES = "maxOutputLines";
    static final String ALLOW_COMMENT = "allowComment";
    static final String ALLOW_TRANSITION = "allowTransition";
    static final String ALLOW_ASSIGN = "allowAssign";
    static final String ALLOW_EDIT = "allowEdit";
    static final String ALLOW_CREATE = "allowCreate";
    static final String WRITE_PROJECTS = "writeProjects";
    static final String COMMENT_SUFFIX = "commentSuffix";
    static final String ALLOW_DELETE = "allowDelete";
    static final String DELETE_ONLY_OWN = "deleteOnlyOwn";
    static final String ALLOW_CLASSIFY = "allowClassify";

    private final TicketProviders providers;
    /** Über alle Konfigurationsänderungen hinweg dieselbe Instanz – sonst ginge die Zuordnung beim Umschalten verloren. */
    private final TicketOwnership ownership;

    /** Einstellungen von ticket_classify – aus dem Modul Modellwahl, bei jedem Aufruf neu gelesen. */
    private final Supplier<TaskClassifier.Settings> classifier;

    /** Für Tests: Verzeichnis selbst angelegter Tickets nur im Speicher, Classifier mit Standardeinstellungen. */
    public TicketModule(TicketProviders providers) {
        this(providers, TicketOwnership.inMemory());
    }

    TicketModule(TicketProviders providers, TicketOwnership ownership) {
        this(providers, ownership, () -> ClassifyModule.settings(
                ModuleConfig.of(new ClassifyModule().configSchema(), Map.of())));
    }

    @Autowired
    public TicketModule(TicketProviders providers, SettingsStore store, ObjectProvider<ToolRegistry> registry) {
        this(providers, new TicketOwnership(store.file().toAbsolutePath().getParent().resolve("tickets-own.json")),
                () -> ClassifyModule.settings(registry.getObject().config(ClassifyModule.ID)));
    }

    TicketModule(TicketProviders providers, TicketOwnership ownership, Supplier<TaskClassifier.Settings> classifier) {
        this.providers = providers;
        this.ownership = ownership;
        this.classifier = classifier;
    }

    TicketEnvironment environment(ModuleConfig config) {
        return new TicketEnvironment(providers, config, ownership);
    }

    public TicketProviders providers() {
        return providers;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Tickets";
    }

    @Override
    public String description() {
        return "Jira, GitHub, GitLab, YouTrack, OpenProject und weitere Systeme (erweiterbar per ServiceLoader): Boards mit ihren Spalten, "
                + "Tickets suchen, Status, Zuständige, Beschreibung, Kommentare und Verknüpfungen lesen; optional "
                + "kommentieren, Status wechseln, zuweisen, bearbeiten und anlegen (einzeln schaltbar, je Projekt freigebbar) sowie "
                + "die Komplexität einschätzen und das Modell für die Umsetzung empfehlen.";
    }

    @Override
    public String instructions() {
        return """
                Für Tickets/Issues (Jira, GitHub, GitLab, YouTrack, OpenProject) diese Tools statt `curl` gegen die REST-APIs, `gh issue`/`glab issue` \
                oder eines Browsers verwenden:
                - `ticket_providers`: aktive Systeme, angemeldeter Benutzer, Standardprojekt und Schlüssel-/Abfrageformate.
                - `ticket_boards` → `ticket_board`: aktueller Stand eines Boards nach Spalten (Jira-Board/Sprint, GitHub Project, \
                GitLab-Issue-Board, YouTrack-Agile-Board, OpenProject-Board), optional nur eigene Tickets (`assignee=me`).
                - `ticket_search`: Tickets filtern (Status, Zuständige, Labels, Text, systemeigene Abfrage wie JQL).
                - `ticket_get`: ein Ticket vollständig (Titel, Status, Zuständige, Beschreibung, Kommentare); \
                `ticket_status`: Status und Zuständige mehrerer Tickets auf einmal; `ticket_links`: Parent, Unteraufgaben, \
                verknüpfte Tickets und Pull/Merge Requests; `ticket_transitions`: mögliche Statuswechsel.
                - Schreiben, nur wenn angeboten (einzeln in der App schaltbar): `ticket_comment`, `ticket_transition` \
                (Ziel aus `ticket_transitions`), `ticket_assign`, `ticket_update`, `ticket_create`, `ticket_delete_comment`, \
                `ticket_delete` (standardmäßig nur selbst angelegte; Schließen ist meist richtiger). Nur auf ausdrückliche \
                Anweisung des Nutzers schreiben und das Ergebnis mit Link melden. Fehlt ein schreibendes Tool, ist es \
                abgeschaltet: dem Nutzer den Schalter nennen, nicht per `curl`/`gh`/`glab` ausweichen.
                - `ticket_classify` (wenn angeboten): vor der Umsetzung eines Tickets dessen Komplexität einschätzen lassen \
                und das empfohlene Modell für die Umsetzung verwenden, z.B. als Modell des Subagenten; kommt ein \
                Classifier-Prompt zurück, die Einschätzung damit selbst durchführen (am besten per Subagent auf Opus). Architektur-Kontext aus dem Code (betroffene Module, Schichten) in `context` mitgeben.
                Taucht ein Ticket-Schlüssel (ABC-123, owner/repo#12, #123, Issue-URL) in Branch-Namen, Commits oder der Aufgabe auf, \
                das Ticket mit `ticket_get` lesen, bevor du es interpretierst.""";
    }

    @Override
    public int order() {
        return 160;
    }

    static String key(String providerId, String field) {
        return providerId + "." + field;
    }

    static String enabledKey(String providerId) {
        return key(providerId, "enabled");
    }

    @Override
    public List<ConfigField> configSchema() {
        List<String> options = new ArrayList<>(List.of("auto"));
        providers.providers().forEach(p -> options.add(p.id()));
        List<ConfigField> fields = new ArrayList<>();
        fields.add(ConfigField.of(DEFAULT_PROVIDER, "Standard-System", FieldType.ENUM).withDefault("auto")
                .withOptions(options.toArray(String[]::new))
                .withHelp("Für Aufrufe ohne 'provider', deren Schlüssel keinem System eindeutig gehört. "
                        + "'auto' = das einzige aktive System."));
        for (TicketProvider p : providers.providers()) {
            List<ConfigField> own = new ArrayList<>(p.configFields());
            own.add(ConfigField.of(DEFAULT_PROJECT, "Standardprojekt", FieldType.STRING)
                    .withHelp(p.projectHelp() + ". Wird verwendet, wenn das LLM kein Projekt angibt."));
            fields.addAll(new ConfigGroup(p.id(), p.displayName()).fields(false, own));
        }
        fields.addAll(List.of(
                ConfigField.of(COMMENTS, "Kommentare je Ticket", FieldType.INT).withDefault("5")
                        .withHelp("So viele neueste Kommentare liefert ticket_get ohne eigene Angabe."),
                ConfigField.of(MAX_DESCRIPTION, "Max. Zeichen der Beschreibung", FieldType.INT).withDefault("8000"),
                ConfigField.of(MAX_PER_COLUMN, "Tickets je Board-Spalte", FieldType.INT).withDefault("15"),
                ConfigField.of(TIMEOUT, "Timeout (Sekunden)", FieldType.INT).withDefault("30"),
                ConfigField.of(MAX_LINES, "Max. Ausgabezeilen", FieldType.INT).withDefault("400"),
                ConfigField.of(ALLOW_COMMENT, "Kommentieren erlauben", FieldType.BOOLEAN).withDefault("false")
                        .withHelp("ticket_comment; auch nötig für einen Kommentar beim Statuswechsel."),
                ConfigField.of(ALLOW_TRANSITION, "Status wechseln erlauben", FieldType.BOOLEAN).withDefault("false")
                        .withHelp("ticket_transition: Workflow-Übergang, Schließen/Öffnen, Board-Spalte."),
                ConfigField.of(ALLOW_ASSIGN, "Zuweisen erlauben", FieldType.BOOLEAN).withDefault("false")
                        .withHelp("ticket_assign"),
                ConfigField.of(ALLOW_EDIT, "Titel/Beschreibung/Labels ändern erlauben", FieldType.BOOLEAN).withDefault("false")
                        .withHelp("ticket_update"),
                ConfigField.of(ALLOW_CREATE, "Tickets anlegen erlauben", FieldType.BOOLEAN).withDefault("false")
                        .withHelp("ticket_create"),
                ConfigField.of(WRITE_PROJECTS, "Schreiben nur in diesen Projekten", FieldType.STRING_LIST)
                        .withHelp("Ein Projekt je Zeile, optional mit System: ABC, jira:ABC, github:owner/repo, "
                                + "gitlab:gruppe/projekt, youtrack:ABC, openproject:kennung, * am Ende als Präfix (gitlab:gruppe/*). Leer = alle Projekte."),
                ConfigField.of(COMMENT_SUFFIX, "Kennzeichnung von Kommentaren", FieldType.STRING)
                        .withHelp("Wird an jeden Kommentar angehängt, z.B. „(via DevTools MCP)“. Leer = keine."),
                ConfigField.of(ALLOW_DELETE, "Tickets und Kommentare löschen erlauben", FieldType.BOOLEAN).withDefault("false")
                        .withHelp("ticket_delete, ticket_delete_comment – endgültig, nicht wiederherstellbar."),
                ConfigField.of(DELETE_ONLY_OWN, "Nur selbst angelegte löschen", FieldType.BOOLEAN).withDefault("true")
                        .withHelp("Nur Tickets/Kommentare, die über ticket_create bzw. ticket_comment angelegt wurden "
                                + "(gemerkt in tickets-own.json)."),
                ConfigField.of(ALLOW_CLASSIFY, "Komplexität einschätzen (ticket_classify)", FieldType.BOOLEAN).withDefault("false")
                        .withHelp("Pre-Classifier: schätzt Tickets ein und empfiehlt das Modell für die "
                                + "Umsetzung – über das LLM des aufrufenden Clients oder die Claude API. Ausführung, Modelle je "
                                + "Stufe und Regeln: Modul Modellwahl.")));
        return fields;
    }

    @Override
    public List<ToolCallback> createTools(ModuleConfig config) {
        TicketEnvironment env = environment(config);
        List<Object> beans = new ArrayList<>(List.of(new TicketTools(env)));
        if (config.getBoolean(ALLOW_COMMENT)) {
            beans.add(new TicketCommentTools(env));
        }
        if (config.getBoolean(ALLOW_TRANSITION)) {
            beans.add(new TicketTransitionTools(env, config.getBoolean(ALLOW_COMMENT)));
        }
        if (config.getBoolean(ALLOW_ASSIGN)) {
            beans.add(new TicketAssignTools(env));
        }
        if (config.getBoolean(ALLOW_EDIT)) {
            beans.add(new TicketEditTools(env));
        }
        if (config.getBoolean(ALLOW_CREATE)) {
            beans.add(new TicketCreateTools(env));
        }
        if (config.getBoolean(ALLOW_DELETE)) {
            beans.add(new TicketDeleteTools(env, config.getBoolean(DELETE_ONLY_OWN)));
        }
        if (config.getBoolean(ALLOW_CLASSIFY)) {
            beans.add(new TicketClassifyTools(env, () -> new TaskClassifier(classifier.get())));
        }
        return List.of(ToolCallbacks.from(beans.toArray()));
    }

    @Override
    public ConnectionTestResult testConnection(ModuleConfig config) {
        List<String> errors = config.validate();
        if (!errors.isEmpty()) {
            return ConnectionTestResult.failed(String.join("\n", errors));
        }
        TicketEnvironment env;
        try {
            env = new TicketEnvironment(providers, config);
        } catch (RuntimeException e) {
            return ConnectionTestResult.failed(e.getMessage());
        }
        if (env.entries().isEmpty()) {
            return ConnectionTestResult.failed("Kein Ticket-System aktiviert.");
        }
        StringBuilder sb = new StringBuilder();
        boolean allOk = true;
        for (TicketEnvironment.Entry e : env.entries()) {
            TicketSystem.Availability a = e.system().probe();
            sb.append(e.provider().displayName()).append(": ");
            if (a.available()) {
                sb.append(a.version() == null ? "erreichbar" : a.version());
                sb.append(a.user() == null ? "" : ", angemeldet als " + a.user());
                if (!"verfügbar".equals(a.message())) {
                    sb.append(" (").append(a.message()).append(')');
                }
            } else {
                allOk = false;
                sb.append("nicht erreichbar – ").append(a.message());
            }
            sb.append('\n');
        }
        String msg = sb.toString().strip();
        return allOk ? ConnectionTestResult.ok(msg) : ConnectionTestResult.failed(msg);
    }
}
