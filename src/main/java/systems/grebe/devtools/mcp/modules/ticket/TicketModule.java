package systems.grebe.devtools.mcp.modules.ticket;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.ConnectionTestResult;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolModule;
import systems.grebe.devtools.mcp.modules.ticket.spi.TicketProvider;
import systems.grebe.devtools.mcp.modules.ticket.spi.TicketSystem;

/**
 * Ticket-Systeme über austauschbare Provider ({@link TicketProvider}, per ServiceLoader): Boards, Suche, Ticket lesen.
 * Mitgeliefert: Jira, GitHub, GitLab. Nur lesend.
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

    private final TicketProviders providers;

    public TicketModule(TicketProviders providers) {
        this.providers = providers;
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
        return "Jira, GitHub, GitLab und weitere Systeme (erweiterbar per ServiceLoader): Boards mit ihren Spalten, "
                + "Tickets suchen, Status, Zuständige, Beschreibung und Kommentare lesen. Nur lesend.";
    }

    @Override
    public String instructions() {
        return """
                Für Tickets/Issues (Jira, GitHub, GitLab) diese Tools statt `curl` gegen die REST-APIs, `gh issue`/`glab issue` \
                oder eines Browsers verwenden:
                - `ticket_providers`: aktive Systeme, angemeldeter Benutzer, Standardprojekt und Schlüssel-/Abfrageformate.
                - `ticket_boards` → `ticket_board`: aktueller Stand eines Boards nach Spalten (Jira-Board/Sprint, GitHub Project, \
                GitLab-Issue-Board), optional nur eigene Tickets (`assignee=me`).
                - `ticket_search`: Tickets filtern (Status, Zuständige, Labels, Text, systemeigene Abfrage wie JQL).
                - `ticket_get`: ein Ticket vollständig (Titel, Status, Zuständige, Beschreibung, Kommentare); \
                `ticket_status`: Status und Zuständige mehrerer Tickets auf einmal.
                Taucht ein Ticket-Schlüssel (ABC-123, owner/repo#12, Issue-URL) in Branch-Namen, Commits oder der Aufgabe auf, \
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
            fields.add(ConfigField.of(enabledKey(p.id()), p.displayName() + ": aktiv", FieldType.BOOLEAN).withDefault("false"));
            for (ConfigField f : p.configFields()) {
                fields.add(new ConfigField(key(p.id(), f.key()), p.displayName() + ": " + f.label(), f.type(),
                        f.required(), f.defaultValue(), f.help(), f.options()));
            }
            fields.add(ConfigField.of(key(p.id(), DEFAULT_PROJECT), p.displayName() + ": Standardprojekt", FieldType.STRING)
                    .withHelp(p.projectHelp() + ". Wird verwendet, wenn das LLM kein Projekt angibt."));
        }
        fields.addAll(List.of(
                ConfigField.of(COMMENTS, "Kommentare je Ticket", FieldType.INT).withDefault("5")
                        .withHelp("So viele neueste Kommentare liefert ticket_get ohne eigene Angabe."),
                ConfigField.of(MAX_DESCRIPTION, "Max. Zeichen der Beschreibung", FieldType.INT).withDefault("8000"),
                ConfigField.of(MAX_PER_COLUMN, "Tickets je Board-Spalte", FieldType.INT).withDefault("15"),
                ConfigField.of(TIMEOUT, "Timeout (Sekunden)", FieldType.INT).withDefault("30"),
                ConfigField.of(MAX_LINES, "Max. Ausgabezeilen", FieldType.INT).withDefault("400")));
        return fields;
    }

    @Override
    public List<ToolCallback> createTools(ModuleConfig config) {
        TicketEnvironment env = new TicketEnvironment(providers, config);
        return List.of(ToolCallbacks.from(new TicketTools(env)));
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
