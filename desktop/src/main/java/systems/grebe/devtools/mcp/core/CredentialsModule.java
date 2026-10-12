package systems.grebe.devtools.mcp.core;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.api.CredentialInfo;
import systems.grebe.devtools.mcp.oauth.OAuthClient;
import systems.grebe.devtools.mcp.oauth.OAuthOptions;
import systems.grebe.devtools.mcp.oauth.OAuthServer;
import systems.grebe.devtools.mcp.oauth.OAuthTokens;

/**
 * Zentrale Zugangsdaten wie beim Git Credential Manager: Tokens und Passwörter je Server-URL, einmal hinterlegt.
 * Module, deren Geheimnis leer bleibt, bekommen das des passenden Eintrags (siehe {@link Credentials} und
 * {@link ToolModule#credentialFields()}). Stellt selbst keine Tools bereit; angewendet von der {@link ToolRegistry}.
 *
 * <p>Ein Eintrag hat entweder ein Token von Hand oder meldet sich per OAuth 2.0 / OpenID Connect im Browser an
 * (Aktion „Im Browser anmelden“, {@link OAuthClient}); das Access-Token erneuert dann das Backend.
 *
 * <p>Die Einträge liegen nicht in den Moduleinstellungen, sondern je Benutzer verschlüsselt im Backend (Tabelle
 * {@code credential}); {@code BackendSettingsResolver} liest und speichert sie dort.
 */
@Component
public class CredentialsModule implements ToolModule {

    public static final String ID = "credentials";
    public static final String ENTRIES = "entries";
    public static final String NAME = "name";
    public static final String URL = "url";
    public static final String AUTH = "auth";
    public static final String USER = "user";
    public static final String SECRET = "secret";
    public static final String ISSUER = "issuer";
    public static final String CLIENT_ID = "clientId";
    public static final String CLIENT_SECRET = "clientSecret";
    public static final String SCOPES = "scopes";
    public static final String OPTIONS = "options";
    public static final String DESCRIPTION = "description";
    /** Werte von {@link #AUTH} im Formular. */
    public static final String AUTH_TOKEN = "token";
    public static final String AUTH_OAUTH = "oauth";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM. HH:mm");

    private final ObjectProvider<CredentialStore> store;
    private final OAuthClient oauth;
    private final Consumer<URI> browser;

    @Autowired
    public CredentialsModule(ObjectProvider<CredentialStore> store) {
        this(store, new OAuthClient(Duration.ofSeconds(30)), CredentialsModule::browse);
    }

    /** @param browser öffnet eine Adresse im Browser */
    CredentialsModule(ObjectProvider<CredentialStore> store, OAuthClient oauth, Consumer<URI> browser) {
        this.store = store;
        this.oauth = oauth;
        this.browser = browser;
    }

    /** Ohne Ablage – nur Schema und Zuordnung (Tests). */
    public CredentialsModule() {
        this(null, new OAuthClient(Duration.ofSeconds(30)), uri -> { });
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Zugangsdaten";
    }

    @Override
    public String description() {
        return "Tokens und Passwörter zentral je Server hinterlegen, wie beim Git Credential Manager – von Hand oder per "
                + "Anmeldung im Browser (OAuth 2.0 / OpenID Connect): Module, deren Token- bzw. Passwortfeld leer "
                + "bleibt, verwenden den Eintrag, dessen URL zu ihrer Server-URL passt (Tickets, Pull Requests, CI, "
                + "SonarQube, Maven, Chat …).";
    }

    @Override
    public boolean hasTools() {
        return false;
    }

    @Override
    public boolean enabledByDefault() {
        return true;
    }

    @Override
    public int order() {
        return 2;
    }

    @Override
    public List<ConfigField> configSchema() {
        return List.of(ConfigField.records(ENTRIES, "Zugangsdaten",
                        ConfigField.of(NAME, "Name", FieldType.STRING).asRequired(),
                        ConfigField.of(URL, "URL", FieldType.URL).asRequired()
                                .withHelp("Server, für den die Zugangsdaten gelten, z.B. https://github.com. Gilt auch "
                                        + "für Subdomains (api.github.com) und Pfade darunter; der genaueste Eintrag "
                                        + "gewinnt."),
                        ConfigField.of(AUTH, "Anmeldung", FieldType.ENUM).withOptions(AUTH_TOKEN, AUTH_OAUTH)
                                .withDefault(AUTH_TOKEN)
                                .withHelp("token = Token/Passwort von Hand; oauth = im Browser anmelden (OAuth 2.0 / "
                                        + "OpenID Connect) – nach dem Speichern über „Im Browser anmelden“."),
                        ConfigField.of(USER, "Benutzer", FieldType.STRING)
                                .withHelp("Optional, z.B. die E-Mail zum Jira-Cloud-Token. Wird nur eingesetzt, wenn "
                                        + "das Modul keinen eigenen Benutzer hat."),
                        ConfigField.of(SECRET, "Token / Passwort", FieldType.SECRET)
                                .withHelp("Nur für Anmeldung token. Bei oauth trägt die Anmeldung das Token ein."),
                        ConfigField.of(ISSUER, "OAuth: Aussteller", FieldType.URL)
                                .withHelp("Autorisierungsserver für die Discovery (RFC 8414 / OpenID Connect). Leer = "
                                        + "Herkunft der URL, z.B. https://gitlab.com; GitHub braucht keinen."),
                        ConfigField.of(CLIENT_ID, "OAuth: Client-ID", FieldType.STRING)
                                .withHelp("Registrierte OAuth-App: GitHub (Device Flow aktivieren), GitLab-Anwendung "
                                        + "(nicht vertraulich, Redirect-URI http://127.0.0.1/callback) oder ein anderer "
                                        + "OAuth-/OIDC-Server."),
                        ConfigField.of(CLIENT_SECRET, "OAuth: Client-Geheimnis", FieldType.SECRET)
                                .withHelp("Nur für vertrauliche Clients; öffentliche Clients brauchen keins."),
                        ConfigField.of(SCOPES, "OAuth: Scopes", FieldType.STRING)
                                .withHelp("Durch Leerzeichen getrennt. Leer = GitHub: repo read:org; Jira Cloud: "
                                        + "read:jira-work write:jira-work read:jira-user offline_access; sonst die "
                                        + "Vorgabe des Servers (GitLab: api)."),
                        ConfigField.of(OPTIONS, "OAuth: Optionen", FieldType.STRING)
                                .withHelp("Nur nötig für Server ohne Discovery oder mit Besonderheiten, name=wert durch "
                                        + "; getrennt: authorization_endpoint, token_endpoint, "
                                        + "device_authorization_endpoint (RFC 8414), redirect_uri=http://127.0.0.1:"
                                        + "<port>/callback (feste Rückleitung), sonst weitere Anmelde-Parameter. Jira "
                                        + "Cloud ist vorgegeben; Jira Data Center z.B. authorization_endpoint=https://"
                                        + "jira.firma.de/rest/oauth2/latest/authorize; token_endpoint=https://"
                                        + "jira.firma.de/rest/oauth2/latest/token"),
                        ConfigField.of(DESCRIPTION, "Beschreibung", FieldType.STRING))
                .withHelp("Ein eigenes Token im Modul hat immer Vorrang. Gespeichert im Backend je Benutzer "
                        + "(verschlüsselt, für alle Profile); geht nie an das LLM."));
    }

    @Override
    public List<ToolCallback> createTools(ModuleConfig config) {
        return List.of();
    }

    @Override
    public List<CredentialField> credentialFields() {
        return List.of();
    }

    @Override
    public List<ModuleAction> actions() {
        return List.of(new LoginAction());
    }

    @Override
    public ConnectionTestResult testConnection(ModuleConfig config) {
        List<String> errors = config.validate();
        if (!errors.isEmpty()) {
            return ConnectionTestResult.failed(String.join("\n", errors));
        }
        List<Map<String, String>> entries = config.getRecords(ENTRIES);
        if (entries.isEmpty()) {
            return ConnectionTestResult.ok("Keine Zugangsdaten hinterlegt.");
        }
        List<String> lines = new ArrayList<>();
        lines.add(entries.size() + " Zugangsdaten hinterlegt:");
        for (Map<String, String> e : entries) {
            lines.add("- " + e.get(NAME) + ": " + e.get(URL) + (AUTH_OAUTH.equals(e.get(AUTH)) ? " (OAuth)" : ""));
            if (AUTH_TOKEN.equals(e.getOrDefault(AUTH, AUTH_TOKEN)) && e.getOrDefault(SECRET, "").isBlank()) {
                return ConnectionTestResult.failed("„" + e.get(NAME) + "“: Token/Passwort fehlt.");
            }
            if (AUTH_OAUTH.equals(e.get(AUTH)) && e.getOrDefault(CLIENT_ID, "").isBlank()) {
                return ConnectionTestResult.failed("„" + e.get(NAME) + "“: Client-ID fehlt (Anmeldung oauth).");
            }
        }
        return ConnectionTestResult.ok(String.join("\n", lines));
    }

    /** Zustand eines Eintrags für die Anzeige. */
    static String status(CredentialInfo c, Instant now) {
        if (!c.oauth()) {
            return "Token von Hand";
        }
        if (c.secret() == null || c.secret().isBlank()) {
            return "Nicht angemeldet";
        }
        if (c.expiresAt() == null) {
            return "Angemeldet (Token läuft nicht ab)";
        }
        Instant expires = Instant.parse(c.expiresAt());
        String at = TIME.format(LocalDateTime.ofInstant(expires, ZoneId.systemDefault()));
        return expires.isAfter(now) ? "Angemeldet, Token gültig bis " + at + " (wird automatisch erneuert)"
                : "Token abgelaufen seit " + at + " – wird erneuert bzw. neu anmelden";
    }

    /** „Im Browser anmelden“: OAuth-Anmeldung eines gespeicherten Eintrags; die Tokens gehen ins Backend. */
    private final class LoginAction implements ModuleAction {

        @Override
        public String id() {
            return "oauth-login";
        }

        @Override
        public String label() {
            return "Im Browser anmelden";
        }

        @Override
        public String description() {
            return "Für Einträge mit Anmeldung oauth: öffnet den Browser (Device Flow mit Code bzw. Anmeldeseite mit "
                    + "Rückleitung an die App). Vorher die Einstellungen speichern.";
        }

        @Override
        public List<String> targets(ModuleConfig config) {
            return entries().stream().filter(CredentialInfo::oauth).map(CredentialInfo::name).toList();
        }

        @Override
        public String describe(ModuleConfig config, String target) {
            return entry(target).map(c -> status(c, Instant.now())).orElse(null);
        }

        @Override
        public ActionResult run(ModuleConfig config, String target, Set<String> flags, Progress progress) {
            CredentialStore s = store == null ? null : store.getIfAvailable();
            CredentialInfo c = entry(target).filter(CredentialInfo::oauth).orElse(null);
            if (s == null || c == null) {
                return ActionResult.failed("Kein gespeicherter Eintrag „" + target + "“ mit Anmeldung oauth – "
                        + "Einstellungen speichern.");
            }
            progress.update("Suche den Anmeldeserver …", -1);
            OAuthOptions options = OAuthOptions.parse(c.options());
            OAuthServer server = oauth.resolve(c.issuer(), c.url(), options);
            String scopes = c.scopes() == null || c.scopes().isBlank() ? server.defaultScopes() : c.scopes();
            OAuthTokens t = oauth.login(server, c.clientId(), c.clientSecret(), scopes, options,
                    prompt -> progress.update(prompt, -1), browser);
            String expires = t.expiresAt() == null ? null : t.expiresAt().toString();
            s.save(s.credentials().stream()
                    .map(x -> x.id() != null && x.id().equals(c.id()) ? x.withTokens(t.accessToken(), t.refreshToken(),
                            expires) : x)
                    .toList());
            return ActionResult.ok("„" + c.name() + "“ ist angemeldet"
                    + (t.expiresAt() == null ? "." : "; das Token wird vor dem Ablauf automatisch erneuert."));
        }

        private List<CredentialInfo> entries() {
            CredentialStore s = store == null ? null : store.getIfAvailable();
            return s == null ? List.of() : s.credentials();
        }

        private Optional<CredentialInfo> entry(String name) {
            return entries().stream().filter(c -> c.name().equalsIgnoreCase(name)).findFirst();
        }
    }

    /** Öffnet die Adresse im Browser, wenn das geht (nicht im Headless-Betrieb); sonst steht sie in der Anzeige. */
    private static void browse(URI uri) {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(uri);
            }
        } catch (Exception ignored) {
            // Adresse steht in der Anzeige
        }
    }
}
