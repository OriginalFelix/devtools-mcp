package systems.grebe.devtools.mcp.modules.sentry;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.ConnectionTestResult;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolModule;
import systems.grebe.devtools.mcp.modules.ticket.spi.HttpJson;
import tools.jackson.databind.JsonNode;

/** Anbindung an Sentry (sentry.io oder selbst betrieben): Issues und Stacktraces lesen. */
@Component
public class SentryModule implements ToolModule {

    static final String BASE_URL = "baseUrl";
    static final String TOKEN = "token";
    static final String ORGANIZATION = "organization";
    static final String DEFAULT_PROJECT = "defaultProject";
    static final String TIMEOUT = "timeoutSeconds";

    @Override
    public String id() {
        return "sentry";
    }

    @Override
    public String displayName() {
        return "Sentry";
    }

    @Override
    public String description() {
        return "Liest Fehler aus Sentry: Issues mit Häufigkeit und betroffenen Nutzern, Stacktrace, Request, Tags und "
                + "Breadcrumbs des letzten Events.";
    }

    @Override
    public String instructions() {
        return """
                Für Sentry diese Tools statt `curl` gegen die Web-API oder eines Browsers verwenden: \
                `sentry_list_projects` (Projekt-Slug finden), `sentry_issues` (offene Fehler, Sentry-Suchsyntax) → \
                `sentry_issue_detail` (Stacktrace des letzten Events, dann die Stelle im Code beheben).""";
    }

    @Override
    public List<ConfigField> configSchema() {
        return List.of(
                ConfigField.of(BASE_URL, "Server-URL", FieldType.URL).asRequired().withDefault("https://sentry.io")
                        .withHelp("https://sentry.io, für EU-Organisationen https://de.sentry.io, sonst die eigene Instanz"),
                ConfigField.of(TOKEN, "Auth-Token", FieldType.SECRET)
                        .withHelp("User Auth Token (Einstellungen → Auth Tokens) mit event:read, project:read, org:read. "
                                + "Wird verschlüsselt gespeichert."),
                ConfigField.of(ORGANIZATION, "Organisation (Slug)", FieldType.STRING).asRequired()
                        .withHelp("Wie in der URL: https://sentry.io/organizations/<slug>/"),
                ConfigField.of(DEFAULT_PROJECT, "Standard-Projekt (Slug)", FieldType.STRING)
                        .withHelp("Wird verwendet, wenn das LLM kein Projekt angibt; leer = alle Projekte."),
                ConfigField.of(TIMEOUT, "Timeout (Sekunden)", FieldType.INT).withDefault("30"));
    }

    @Override
    public List<ToolCallback> createTools(ModuleConfig config) {
        return List.of(ToolCallbacks.from(new SentryTools(() -> client(config), config.require(ORGANIZATION),
                config.getString(DEFAULT_PROJECT, null))));
    }

    static HttpJson client(ModuleConfig config) {
        String token = config.getString(TOKEN, "");
        return new HttpJson("Sentry", HttpJson.stripSlash(config.require(BASE_URL)) + "/api/0",
                token.isBlank() ? Map.of() : Map.of("Authorization", "Bearer " + token),
                Duration.ofSeconds(Math.max(5, config.getInt(TIMEOUT, 30))), "Sentry");
    }

    @Override
    public ConnectionTestResult testConnection(ModuleConfig config) {
        List<String> errors = config.validate();
        if (!errors.isEmpty()) {
            return ConnectionTestResult.failed(String.join("\n", errors));
        }
        HttpJson client = client(config);
        JsonNode org = client.getJson("/organizations/" + HttpJson.enc(config.require(ORGANIZATION)) + "/");
        return ConnectionTestResult.ok("Organisation „" + org.path("name").asString(org.path("slug").asString())
                + "“ auf " + client.baseUrl() + " erreichbar, Token gültig.");
    }
}
