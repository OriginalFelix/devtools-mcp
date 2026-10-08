package systems.grebe.devtools.mcp.remote;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.graphql.ResponseError;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.HttpSyncGraphQlClient;
import org.springframework.graphql.client.WebSocketGraphQlClient;
import org.springframework.graphql.client.WebSocketGraphQlClientInterceptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.socket.client.StandardWebSocketClient;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import systems.grebe.devtools.mcp.api.Grants;
import systems.grebe.devtools.mcp.api.LoginResult;
import systems.grebe.devtools.mcp.api.Me;
import systems.grebe.devtools.mcp.api.ModuleDescriptor;
import systems.grebe.devtools.mcp.api.ModuleOverlay;
import systems.grebe.devtools.mcp.api.Permission;
import systems.grebe.devtools.mcp.api.ProjectInfo;
import systems.grebe.devtools.mcp.api.SettingsSnapshot;
import systems.grebe.devtools.mcp.backend.account.PasswordHashing;
import systems.grebe.devtools.mcp.config.ModuleSettings;
import systems.grebe.devtools.mcp.config.SettingsStore;
import systems.grebe.devtools.mcp.config.TeamSettings;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.core.ToolModule;
import systems.grebe.devtools.mcp.core.ToolRegistry;
import systems.grebe.devtools.mcp.core.ToolScope;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verbindung der Desktop-App zu ihrem Backend über GraphQL – eingebettet ({@link EmbeddedBackend}) oder auf einem
 * Team-Server (Adresse in den Einstellungen; Wechsel nach Neustart).
 *
 * <p><b>Anmeldung:</b> Ohne Anmeldung gibt es keine Tools. Mit Fenster meldet sich der Benutzer beim Start mit
 * Benutzername und Passwort an ({@link #login}, Mutation {@code login} → Sitzungs-Token); ohne Fenster über
 * {@code devtools.login.token} bzw. {@code devtools.login.username}/{@code password} (aus den Umgebungsvariablen
 * {@code DEVTOOLS_MCP_TOKEN}, {@code DEVTOOLS_MCP_USER}, {@code DEVTOOLS_MCP_PASSWORD}). Ein vom Administrator
 * gesetztes Passwort muss zuerst geändert werden ({@link #changePassword}). Beim Beenden oder {@link #logout} endet
 * die Sitzung auch im Backend. Lehnt das Backend das Token ab (abgelaufen, widerrufen, Benutzer gesperrt), ist der
 * Benutzer abgemeldet – die App fragt neu.
 *
 * <p>Nach der Anmeldung meldet die App ihre Module ({@code reportCatalog}), lädt Benutzer (mit Rollen und Rechten),
 * Vorgaben des aktiven Profils und Projekte und abonniert {@code settingsChanged}, {@code projectsChanged},
 * {@code skillsChanged}, {@code memoriesChanged} und {@code scriptsChanged} per WebSocket. Jede Änderung baut die
 * Tools neu – verbundene MCP-Clients bekommen {@code tools/list_changed}. Bricht die Verbindung ab, verbinden sich die
 * Subscriptions mit wachsendem Abstand neu; dazwischen gilt der letzte Stand. Beim Team-Server übersteht er auch einen
 * Neustart (verschlüsselte Cache-Datei {@code team-cache.json} mit dem Stand und dem Passwort-Hash der letzten
 * Anmeldung): Ist der Server beim Start nicht erreichbar, prüft die App das Passwort dagegen, arbeitet mit dem letzten
 * Stand weiter und meldet sich an, sobald er wieder antwortet (das Passwort bleibt nur so lange im Speicher).
 *
 * <p>Beim ersten eingebetteten Start übernimmt das Backend die bisherigen Modul-Einstellungen aus
 * {@code settings.json} als globale Vorgaben (sobald sich ein Benutzer mit dem Recht „Globale Einstellungen“
 * anmeldet).
 */
@Component
public class BackendConnection {

    private static final Logger LOG = LoggerFactory.getLogger(BackendConnection.class);
    private static final String IMPORT_MARKER = "backend-import.done";
    /** Ping auf der Subscription-Verbindung, deutlich unter dem Idle-Timeout von Jetty (30 s) und Reverse-Proxys. */
    private static final Duration WS_KEEP_ALIVE = Duration.ofSeconds(15);
    private static final Duration RECONNECT = Duration.ofSeconds(15);
    private static final String SESSION_INVALID = "Anmeldung abgelaufen oder widerrufen – bitte neu anmelden.";

    static final String ME = "{ me { id username displayName email admin profiles { id name description } "
            + "activeProfileId roles permissions passwordChangeRequired } }";
    static final String SETTINGS_FIELDS = "profileId profileName modules { moduleId enabled tools { name enabled } "
            + "values { key value } locked } revision";
    static final String PROJECT_FIELDS = "id name owner toolName writable access description sonarKey ticketProject";
    static final String LOGIN = "mutation($u: String!, $p: String!, $c: String) { login(username: $u, password: $p, "
            + "client: $c) { token expiresAt passwordChangeRequired } }";

    /** Verbindungszustand für die Anzeige. */
    public enum Status {
        /** Niemand angemeldet – keine Tools. */
        SIGNED_OUT,
        /** Angemeldet, noch kein Stand vom Backend. */
        CONNECTING,
        /** Verbunden, Subscriptions laufen. */
        ONLINE,
        /** Backend gerade nicht erreichbar – es gilt der letzte Stand. */
        OFFLINE,
        /** Fehler, ohne Eingriff geht es nicht weiter. */
        ERROR
    }

    /** Ergebnis von {@link #login}. */
    public enum LoginOutcome {
        /** Angemeldet und verbunden. */
        SIGNED_IN,
        /** Team-Server nicht erreichbar: Passwort gegen die letzte Anmeldung geprüft, es gilt der letzte Stand. */
        SIGNED_IN_OFFLINE,
        /** Angemeldet, aber erst nach {@link #changePassword} arbeitsfähig. */
        PASSWORD_CHANGE_REQUIRED
    }

    /** Backend (bzw. Team-Server) nicht erreichbar – im Gegensatz zu abgelehnten Angaben. */
    public static class UnreachableException extends IllegalStateException {
        UnreachableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Stand vom Backend; {@code null}-Felder = (noch) unbekannt. */
    record State(String url, Me me, SettingsSnapshot settings, List<ProjectInfo> projects) {
    }

    /**
     * Inhalt von {@code team-cache.json}: letzter Stand und, wer ihn bekommen hat.
     *
     * @param verifier Hash des Passworts (wie in der Datenbank), für die Anmeldung ohne Server
     * @param token    persönliches Token, mit dem der Stand geholt wurde (Sitzungs-Tokens werden nicht gespeichert)
     */
    record CacheFile(State state, String username, String verifier, String token) {
    }

    /**
     * Eine Anmeldung: Token, Benutzer und ob es ein persönliches Token ist (wird nicht abgemeldet).
     *
     * @param token {@code null} nach einer Anmeldung ohne Server, bis er wieder erreichbar ist
     */
    private record Session(String username, String token, boolean personal) {
    }

    private final SettingsStore store;
    private final ObjectProvider<ToolRegistry> registry;
    private final ObjectProvider<EmbeddedAccounts> embeddedAccounts;
    private final Environment env;
    private final Path cacheFile;
    private final JsonMapper json = JsonMapper.builder().build();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> skillListeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> memoryListeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> scriptListeners = new CopyOnWriteArrayList<>();
    private final List<Disposable> subscriptions = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "backend-connection");
        t.setDaemon(true);
        return t;
    });

    private volatile HttpSyncGraphQlClient http;
    private volatile WebSocketGraphQlClient ws;
    private volatile State state;
    private volatile Session session;
    /** Passwort-Hash der Anmeldung für den Cache (nur Team-Server). */
    private volatile String verifier;
    /** Nur nach einer Anmeldung ohne Server: für die Anmeldung, sobald er wieder erreichbar ist. */
    private volatile String offlinePassword;
    private volatile Status status = Status.SIGNED_OUT;
    private volatile String message = "";
    private volatile Instant lastSync;
    private volatile boolean closing;
    private volatile boolean probing;
    private String reportedCatalog;

    public BackendConnection(SettingsStore store, ObjectProvider<ToolRegistry> registry,
                             ObjectProvider<EmbeddedAccounts> embeddedAccounts, Environment env) {
        this.store = store;
        this.registry = registry;
        this.embeddedAccounts = embeddedAccounts;
        this.env = env;
        this.cacheFile = store.dir().resolve("team-cache.json");
    }

    // ---------------------------------------------------------------- Lebenszyklus

    /**
     * Nach der Registrierung der Tools ({@link ToolRegistry#registerAll}), damit der Katalog sie enthält. Meldet sich
     * an, wenn Zugangsdaten vorgegeben sind (ohne Fenster, Tests); sonst wartet die App auf den Anmeldedialog.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void start() {
        registry.getObject().addChangeListener(() -> execute(this::reportCatalogQuietly));
        autoLogin();
    }

    private void autoLogin() {
        String token = env.getProperty("devtools.login.token", "");
        String username = env.getProperty("devtools.login.username", "");
        String password = env.getProperty("devtools.login.password", "");
        boolean headless = env.getProperty("devtools.headless", Boolean.class, false);
        if (token.isBlank() && headless && !embedded()) {
            token = store.team().token(); // früher eingetragenes Desktop-Token
        }
        try {
            if (!token.isBlank()) {
                loginWithToken(token);
            } else if (!username.isBlank() && !password.isBlank()) {
                EmbeddedAccounts accounts = embeddedAccounts.getIfAvailable();
                if (accounts != null && accounts.setupRequired()) {
                    accounts.setup(username, null, null, password);
                }
                if (login(username, password) == LoginOutcome.PASSWORD_CHANGE_REQUIRED) {
                    LOG.error("Anmeldung als {}: das Passwort muss zuerst geändert werden (Web-UI bzw. Anmeldedialog)",
                            username);
                }
            } else if (headless) {
                LOG.warn("Ohne Fenster und ohne Anmeldung gibt es keine Tools – DEVTOOLS_MCP_TOKEN oder "
                        + "DEVTOOLS_MCP_USER/DEVTOOLS_MCP_PASSWORD setzen.");
            }
        } catch (UnreachableException e) {
            LOG.warn("Backend nicht erreichbar ({}), neuer Versuch in {} s", e.getMessage(), RECONNECT.toSeconds());
            schedule(this::autoLogin, RECONNECT);
        } catch (RuntimeException e) {
            LOG.error("Anmeldung fehlgeschlagen: {}", e.getMessage());
            message = e.getMessage();
            notifyListeners();
        }
    }

    /**
     * Schon beim Schließen des Kontexts: vor dem Graceful Shutdown des Webservers. Sonst schließt das eingebettete
     * Backend die Subscriptions, und sie verbinden sich gegen den bereits gestoppten WebSocket-Client endlos neu.
     * Die Sitzung endet auch im Backend (höchstens 2 s Wartezeit).
     */
    @EventListener(ContextClosedEvent.class)
    @PreDestroy
    public synchronized void stop() {
        if (closing) {
            return;
        }
        closing = true;
        subscriptions.forEach(Disposable::dispose);
        try {
            CompletableFuture.runAsync(this::endSessionQuietly).get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.debug("Abmelden beim Beenden: {}", e.toString());
        }
        WebSocketGraphQlClient w = ws;
        if (w != null) {
            try {
                w.stop().block(Duration.ofSeconds(2));
            } catch (RuntimeException ignored) {
                // Ende
            }
        }
        executor.shutdownNow();
    }

    // ---------------------------------------------------------------- Anmeldung

    /**
     * Meldet sich mit Benutzername und Passwort an und verbindet sich. Ist der Team-Server nicht erreichbar, gilt das
     * Passwort der letzten Anmeldung dieses Benutzers und ihr Stand ({@link LoginOutcome#SIGNED_IN_OFFLINE}).
     *
     * @throws IllegalArgumentException bei falschen Angaben, gesperrtem Benutzer oder zu vielen Fehlversuchen
     * @throws UnreachableException     wenn das Backend nicht erreichbar ist (und es keinen passenden Stand gibt)
     */
    public LoginOutcome login(String username, String password) {
        String name = username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
        if (name.isEmpty() || password == null || password.isEmpty()) {
            throw new IllegalArgumentException("Benutzername und Passwort angeben.");
        }
        synchronized (this) {
            requireOpen();
            LoginResult r;
            try {
                Map<String, Object> vars = new LinkedHashMap<>();
                vars.put("u", name);
                vars.put("p", password);
                vars.put("c", "Desktop-App (" + hostName() + ")");
                r = extract(execute(httpClient(url(), null), LOGIN, vars), "login", LoginResult.class, null);
            } catch (UnreachableException e) {
                if (!embedded() && offlineLogin(name, password)) {
                    return LoginOutcome.SIGNED_IN_OFFLINE;
                }
                throw e;
            }
            boolean wasSignedIn = signedIn();
            endSessionQuietly(); // vorherige Anmeldung (Benutzerwechsel)
            disconnect();
            state = null;
            offlinePassword = null;
            session = new Session(name, r.token(), false);
            verifier = embedded() ? null : PasswordHashing.hash(password);
            store.saveTeam(store.team().withUsername(name));
            if (r.passwordChangeRequired()) {
                status = Status.SIGNED_OUT;
                message = "Das Passwort muss zuerst geändert werden.";
                if (wasSignedIn) {
                    applied(); // Tools des bisherigen Benutzers weg
                } else {
                    notifyListeners();
                }
                return LoginOutcome.PASSWORD_CHANGE_REQUIRED;
            }
            status = Status.CONNECTING;
            try {
                connect();
            } catch (RuntimeException e) {
                if (!(e instanceof UnreachableException)) {
                    endSessionQuietly();
                }
                session = null;
                status = Status.SIGNED_OUT;
                if (wasSignedIn) {
                    applied();
                }
                throw e;
            }
            return LoginOutcome.SIGNED_IN;
        }
    }

    /**
     * Anmeldung mit einem persönlichen Desktop-Token (ohne Fenster). Beim Team-Server gilt ohne Verbindung der letzte
     * Stand, wenn er mit demselben Token geholt wurde.
     */
    public synchronized void loginWithToken(String token) {
        requireOpen();
        endSessionQuietly();
        disconnect();
        state = null;
        offlinePassword = null;
        session = new Session(null, token.strip(), true);
        verifier = null;
        status = Status.CONNECTING;
        try {
            connect();
        } catch (UnreachableException e) {
            CacheFile c = embedded() ? null : readCache();
            if (c != null && c.state() != null && url().equals(c.state().url()) && token.strip().equals(c.token())) {
                offline(c, "Server nicht erreichbar – letzter Stand");
                return;
            }
            session = null;
            status = Status.SIGNED_OUT;
            throw e;
        } catch (RuntimeException e) {
            session = null;
            status = Status.SIGNED_OUT;
            throw e;
        }
    }

    /**
     * Ändert das Passwort des angemeldeten Benutzers. Musste er es ändern (vom Administrator gesetzt), ist er danach
     * arbeitsfähig und die App verbindet sich.
     */
    public void changePassword(String current, String next) {
        synchronized (this) {
            Session s = session;
            if (s == null) {
                throw new IllegalStateException("Nicht angemeldet.");
            }
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("c", current);
            vars.put("n", next);
            extract(execute(httpClient(url(), s.token()), "mutation($c: String!, $n: String!) { "
                    + "changePassword(currentPassword: $c, newPassword: $n) }", vars), "changePassword", Boolean.class,
                    s);
            if (!embedded() && !s.personal()) {
                verifier = PasswordHashing.hash(next);
            }
            if (state == null || state.me() == null || state.me().passwordChangeRequired()) {
                status = Status.CONNECTING;
                connect();
            } else {
                saveCache();
            }
        }
    }

    /** Meldet ab: Sitzung im Backend beenden, Tools aus. Danach fragt die App neu nach der Anmeldung. */
    public void logout() {
        endSessionQuietly();
        signedOut("Abgemeldet.");
    }

    /** Sitzung im Backend beenden (persönliche Tokens bleiben gültig); Fehler zählen nicht. */
    private void endSessionQuietly() {
        Session s = session;
        if (s == null || s.personal() || s.token() == null) {
            return;
        }
        try {
            execute(httpClient(url(), s.token()), "mutation { logout }", Map.of());
        } catch (RuntimeException e) {
            LOG.debug("Abmelden im Backend: {}", e.getMessage());
        }
    }

    /** Abgemeldet (Benutzer, abgelehntes Token): Verbindung trennen, Stand verwerfen, Tools aus. */
    private void signedOut(String reason) {
        synchronized (this) {
            session = null;
            verifier = null;
            offlinePassword = null;
            disconnect();
            state = null;
            status = Status.SIGNED_OUT;
            message = reason;
        }
        LOG.info("{}", reason);
        applied();
        // Skripte, Skills und Memories des bisherigen Benutzers verschwinden aus Modulen und Ansichten
        scriptListeners.forEach(BackendConnection::runQuietly);
        skillListeners.forEach(BackendConnection::runQuietly);
        memoryListeners.forEach(BackendConnection::runQuietly);
    }

    /** Team-Server nicht erreichbar: Passwort gegen die letzte Anmeldung prüfen und mit ihrem Stand weiterarbeiten. */
    private boolean offlineLogin(String username, String password) {
        CacheFile c = readCache();
        if (c == null || c.state() == null || !url().equals(c.state().url()) || c.verifier() == null
                || !username.equals(c.username())) {
            return false;
        }
        if (!PasswordHashing.matches(password, c.verifier())) {
            throw new IllegalArgumentException("Benutzername oder Passwort falsch (Server nicht erreichbar – geprüft "
                    + "gegen die letzte Anmeldung).");
        }
        disconnect();
        session = new Session(username, null, false);
        verifier = c.verifier();
        offlinePassword = password;
        offline(c, "Server nicht erreichbar – letzter Stand der Anmeldung");
        return true;
    }

    /** Mit dem Stand aus dem Cache weiterarbeiten und regelmäßig neu verbinden. */
    private void offline(CacheFile c, String reason) {
        state = c.state();
        status = Status.OFFLINE;
        message = reason;
        applied();
        schedule(this::reconnectQuietly, RECONNECT);
    }

    private void reconnectQuietly() {
        Session s = session;
        if (closing || s == null || status == Status.ONLINE) {
            return;
        }
        try {
            String password = offlinePassword;
            if (password != null) { // nach der Anmeldung ohne Server: jetzt richtig anmelden
                LoginResult r;
                try {
                    r = extract(execute(httpClient(url(), null), LOGIN, Map.of("u", s.username(), "p", password,
                            "c", "Desktop-App (" + hostName() + ")")), "login", LoginResult.class, null);
                } catch (IllegalArgumentException e) {
                    signedOut(e.getMessage() + " – bitte neu anmelden.");
                    return;
                }
                synchronized (this) {
                    if (session != s) {
                        return; // inzwischen ab- oder anders angemeldet
                    }
                    session = new Session(s.username(), r.token(), false);
                    offlinePassword = null;
                }
                if (r.passwordChangeRequired()) {
                    signedOut("Das Passwort muss zuerst geändert werden – bitte neu anmelden.");
                    return;
                }
            }
            connect();
            LOG.info("Wieder mit {} verbunden", url());
        } catch (RuntimeException e) {
            LOG.debug("Neu verbinden: {}", e.getMessage());
            if (session != null && !SESSION_INVALID.equals(e.getMessage())) { // abgelehnt: check meldet ab
                schedule(this::reconnectQuietly, RECONNECT);
            }
        }
    }

    // ---------------------------------------------------------------- Verbinden

    private synchronized void connect() {
        Session s = session;
        if (s == null) {
            throw new IllegalStateException("Nicht angemeldet.");
        }
        String url = url();
        try {
            http = httpClient(url, s.token());
            WebSocketGraphQlClient previous = ws;
            ws = WebSocketGraphQlClient.builder(URI.create(url.replaceFirst("^http", "ws") + "/graphql"),
                    new StandardWebSocketClient()).keepAlive(WS_KEEP_ALIVE)
                    .interceptor(new WebSocketGraphQlClientInterceptor() {
                        @Override
                        public Mono<Object> connectionInitPayload() {
                            return Mono.just(Map.of(HttpHeaders.AUTHORIZATION, "Bearer " + s.token()));
                        }
                    }).build();
            if (previous != null) {
                previous.stop().subscribe();
            }
            Me me = query(ME, Map.of(), "me", Me.class);
            if (me.passwordChangeRequired()) {
                throw new IllegalStateException("Das Passwort muss zuerst geändert werden.");
            }
            reportCatalog();
            if (embedded() && me.grants().has(Permission.SETTINGS_GLOBAL)) {
                importLegacySettings();
            }
            SettingsSnapshot settings = query("{ settings { " + SETTINGS_FIELDS + " } }", Map.of(), "settings",
                    SettingsSnapshot.class);
            List<ProjectInfo> projects = queryList("{ projects { " + PROJECT_FIELDS + " } }", "projects",
                    ProjectInfo.class);
            if (!online(s, new State(url, me, settings, projects))) {
                return; // inzwischen ab- oder anders angemeldet
            }
            subscribe();
        } catch (RuntimeException e) {
            if (session == s) {
                status = state != null && state.settings() != null ? Status.OFFLINE : Status.ERROR;
                message = describe(e);
                notifyListeners();
            }
            throw e;
        }
    }

    /** Subscriptions beenden und die Clients vergessen. */
    private void disconnect() {
        subscriptions.forEach(Disposable::dispose);
        subscriptions.clear();
        WebSocketGraphQlClient w = ws;
        ws = null;
        http = null;
        if (w != null) {
            try {
                w.stop().subscribe();
            } catch (RuntimeException ignored) {
                // war schon zu
            }
        }
    }

    private void subscribe() {
        subscriptions.forEach(Disposable::dispose);
        subscriptions.clear();
        Session owner = session; // Nachrichten gehören zu dieser Anmeldung, auch wenn sie später verarbeitet werden
        Retry retry = Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(30))
                .filter(e -> !closing && session != null).doBeforeRetry(r -> offline(r.failure()));
        subscriptions.add(ws.document("subscription { settingsChanged { " + SETTINGS_FIELDS + " } }")
                .retrieveSubscription("settingsChanged").toEntity(SettingsSnapshot.class)
                .retryWhen(retry).subscribe(s -> execute(() -> onSettings(owner, s)), this::offline));
        subscriptions.add(ws.document("subscription { projectsChanged { " + PROJECT_FIELDS + " } }")
                .retrieveSubscription("projectsChanged").toEntityList(ProjectInfo.class)
                .retryWhen(retry).subscribe(p -> execute(() -> onProjects(owner, p)), this::offline));
        subscriptions.add(ws.document("subscription { skillsChanged }")
                .retrieveSubscription("skillsChanged").toEntity(Integer.class)
                .retryWhen(retry).subscribe(n -> skillListeners.forEach(Runnable::run), this::offline));
        subscriptions.add(ws.document("subscription { memoriesChanged }")
                .retrieveSubscription("memoriesChanged").toEntity(Integer.class)
                .retryWhen(retry).subscribe(n -> memoryListeners.forEach(Runnable::run), this::offline));
        subscriptions.add(ws.document("subscription { scriptsChanged }")
                .retrieveSubscription("scriptsChanged").toEntity(Integer.class)
                .retryWhen(retry).subscribe(n -> scriptListeners.forEach(Runnable::run), this::offline));
    }

    private void onSettings(Session owner, SettingsSnapshot settings) {
        State s = state;
        if (s == null || owner == null || session != owner) {
            return;
        }
        Me me = s.me();
        try {
            me = query(ME, Map.of(), "me", Me.class); // Profile, Rollen und Rechte können sich mitgeändert haben
        } catch (RuntimeException e) {
            LOG.debug("Benutzer nicht neu geladen: {}", e.getMessage());
        }
        online(owner, new State(url(), me, settings, s.projects()));
    }

    private void onProjects(Session owner, List<ProjectInfo> projects) {
        State s = state;
        if (s == null || owner == null || session != owner) {
            return;
        }
        online(owner, new State(url(), s.me(), s.settings(), projects));
    }

    /**
     * Übernimmt einen Stand – nur, wenn er zur aktuellen Anmeldung gehört (Nachrichten und Antworten der vorigen
     * Sitzung, z.B. nach einem Benutzerwechsel noch in der Warteschlange, verfallen).
     *
     * @return ob übernommen
     */
    private synchronized boolean online(Session owner, State next) {
        if (session != owner) {
            return false;
        }
        State current = state;
        if (current != null && current.settings() != null && next.settings() != null
                && next.settings().revision() < current.settings().revision()) {
            // überholt (z.B. Subscription-Nachricht nach der Antwort einer Mutation): älteren Stand nicht übernehmen
            next = new State(next.url(), next.me(), current.settings(), next.projects());
        }
        boolean changed = !next.equals(state);
        state = next;
        status = Status.ONLINE;
        message = "";
        lastSync = Instant.now();
        if (changed) {
            saveCache();
            applied();
        } else {
            notifyListeners();
        }
        return true;
    }

    private void offline(Throwable error) {
        if (closing || session == null) {
            return;
        }
        if (status == Status.ONLINE && !probing) {
            probing = true; // abgelehntes Token? Ein Aufruf über HTTP verrät es
            execute(() -> {
                try {
                    query(ME, Map.of(), "me", Me.class);
                } catch (RuntimeException ignored) {
                    // nicht erreichbar oder abgemeldet (siehe check)
                } finally {
                    probing = false;
                }
            });
        }
        if (status != Status.ERROR) {
            status = Status.OFFLINE;
        }
        message = describe(error);
        notifyListeners();
    }

    // ---------------------------------------------------------------- Lesen

    /** Eingebettetes Backend (keine Server-URL eingetragen). */
    public boolean embedded() {
        return !store.team().configured();
    }

    /** Basisadresse des Backends, z.B. {@code http://127.0.0.1:8765}. */
    public String url() {
        return embedded() ? "http://127.0.0.1:" + env.getProperty("local.server.port", "8765") : store.team().url();
    }

    /** Token der laufenden Anmeldung (Sitzungs- oder Desktop-Token), z.B. als Passwort beim Broker des Backends. */
    public Optional<String> token() {
        Session s = session;
        return s == null || s.token() == null ? Optional.empty() : Optional.of(s.token());
    }

    /** Jemand ist angemeldet und es gibt einen Stand (vom Backend oder aus dem Cache). */
    public boolean signedIn() {
        State s = state;
        return session != null && s != null && s.me() != null;
    }

    /** Angemeldet, aber das Passwort muss erst geändert werden. */
    public boolean passwordChangePending() {
        return session != null && state == null && status == Status.SIGNED_OUT;
    }

    /** Rechte des angemeldeten Benutzers; niemand angemeldet = keine. */
    public Grants grants() {
        State s = state;
        return session != null && s != null && s.me() != null ? s.me().grants() : Grants.NONE;
    }

    /** Zuletzt angemeldeter Benutzer (Vorbelegung des Anmeldedialogs). */
    public String lastUsername() {
        return store.team().username();
    }

    /** Einrichtung des ersten Kontos (nur eingebettet). */
    public Optional<EmbeddedAccounts> embeddedAccounts() {
        return embedded() ? Optional.ofNullable(embeddedAccounts.getIfAvailable()) : Optional.empty();
    }

    public Status status() {
        return status;
    }

    public String message() {
        return message;
    }

    public Optional<Instant> lastSync() {
        return Optional.ofNullable(lastSync);
    }

    public Optional<Me> me() {
        return Optional.ofNullable(state).map(State::me);
    }

    /** Vorgaben des aktiven Profils, sobald das Backend (oder der Cache) einen Stand geliefert hat. */
    public Optional<SettingsSnapshot> settings() {
        return Optional.ofNullable(state).map(State::settings);
    }

    public List<ProjectInfo> projects() {
        State s = state;
        return s == null || s.projects() == null ? List.of() : s.projects();
    }

    /** Lokales Verzeichnis eines Projekts, falls zugeordnet und vorhanden. */
    public Optional<Path> projectPath(long projectId) {
        String p = store.team().projectPaths().get(projectId);
        if (p == null) {
            return Optional.empty();
        }
        try {
            Path dir = Path.of(p).toAbsolutePath().normalize();
            return Files.isDirectory(dir) ? Optional.of(dir) : Optional.empty();
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
    }

    public TeamSettings serverSettings() {
        return store.team();
    }

    /**
     * Schlüssel für Caches eines Benutzers auf dem Team-Server (Adresse + Benutzer); {@code null} = eingebettet oder
     * niemand angemeldet.
     */
    public String cacheKey() {
        State s = state;
        if (embedded() || session == null || s == null || s.me() == null) {
            return null;
        }
        return url() + "#" + s.me().username();
    }

    /** Wird nach jeder Änderung aufgerufen (beliebiger Thread) – auch bei An- und Abmeldung. */
    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    /** Wird aufgerufen, wenn sich Skills geändert haben (beliebiger Thread). */
    public void addSkillListener(Runnable listener) {
        skillListeners.add(listener);
    }

    /** Wird aufgerufen, wenn sich Memories geändert haben (beliebiger Thread). */
    public void addMemoryListener(Runnable listener) {
        memoryListeners.add(listener);
    }

    /**
     * Wird aufgerufen, wenn sich Skripte geändert haben – beim (Wieder-)Aufbau der Subscription mit dem aktuellen
     * Stand und bei der Abmeldung (beliebiger Thread).
     */
    public void addScriptListener(Runnable listener) {
        scriptListeners.add(listener);
    }

    // ---------------------------------------------------------------- Ändern

    /**
     * Trägt einen Team-Server ein – geprüft, ob unter der Adresse eine GraphQL-API antwortet; wirksam nach einem
     * Neustart. {@code url} leer: zurück zum eingebetteten Backend.
     */
    public void configureServer(String url) {
        if (url == null || url.isBlank()) {
            store.saveTeam(store.team().withServer(""));
            return;
        }
        TeamSettings next = store.team().withServer(url);
        if (!next.url().matches("https?://.+")) {
            throw new IllegalArgumentException("Adresse mit http:// oder https:// angeben.");
        }
        ClientGraphQlResponse r = execute(httpClient(next.url(), null), "{ __typename }", Map.of());
        if (!r.isValid()) {
            throw new IllegalArgumentException("Unter " + next.url() + " antwortet kein DevTools-Backend.");
        }
        store.saveTeam(next);
    }

    /** Ordnet einem Projekt ein lokales Verzeichnis zu ({@code null}/leer = Zuordnung entfernen). */
    public void setProjectPath(long projectId, String path) {
        synchronized (this) {
            store.saveTeam(store.team().withProjectPath(projectId, path));
        }
        applied();
    }

    public void activateProfile(long profileId) {
        Session owner = session;
        SettingsSnapshot s = query("mutation($id: Int!) { activateProfile(profileId: $id) { " + SETTINGS_FIELDS
                + " } }", Map.of("id", profileId), "activateProfile", SettingsSnapshot.class);
        onSettings(owner, s);
    }

    public ProjectInfo createProject(String name, String description) {
        Session owner = session;
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("name", name);
        vars.put("description", description);
        ProjectInfo p = query("mutation($name: String!, $description: String) { createProject(name: $name, "
                + "description: $description) { " + PROJECT_FIELDS + " } }", vars, "createProject", ProjectInfo.class);
        onProjects(owner, queryList("{ projects { " + PROJECT_FIELDS + " } }", "projects", ProjectInfo.class));
        return p;
    }

    /**
     * Speichert eine Änderung aus dem Modul-Formular als Überschreibung im aktiven Profil – nur, was sich gegenüber
     * {@code before} geändert hat. Gesperrte Felder lehnt das Backend ab.
     */
    public void save(ToolModule module, ModuleSettings before, ModuleSettings after) {
        reportCatalog(); // ein gerade aufgenommenes Modul (Plugin, Skript) muss das Backend schon kennen
        ModuleOverlay current = query("query($id: String!) { overrides(level: PROFILE, moduleId: $id) { moduleId "
                        + "enabled tools { name enabled } values { key value } locked } }", Map.of("id", module.id()),
                "overrides", ModuleOverlay.class);
        Boolean enabled = before.enabled() != after.enabled() ? Boolean.valueOf(after.enabled()) : current.enabled();
        Map<String, Boolean> tools = new LinkedHashMap<>(current.toolMap());
        Set<String> changedTools = new LinkedHashSet<>(before.disabledTools());
        changedTools.addAll(after.disabledTools());
        changedTools.removeIf(t -> before.disabledTools().contains(t) == after.disabledTools().contains(t));
        changedTools.forEach(t -> tools.put(t, !after.disabledTools().contains(t)));
        Map<String, ConfigField> fields = module.configSchema().stream()
                .collect(Collectors.toMap(ConfigField::key, f -> f, (a, b) -> a));
        Map<String, String> values = new LinkedHashMap<>(current.valueMap());
        // Überschreibungen aus älteren Versionen: entfallene Felder (z.B. neo4jUser) lehnt das Backend ab, entfallene
        // Auswahlwerte (z.B. storage=neo4j) wären ungültig – beides fällt beim Speichern weg, es gilt die Vorgabe
        values.entrySet().removeIf(e -> {
            ConfigField f = fields.get(e.getKey());
            return f == null || f.type() == FieldType.ENUM && !f.options().isEmpty() && !e.getValue().isEmpty()
                    && !f.options().contains(e.getValue());
        });
        Set<String> schema = fields.keySet();
        Set<String> keys = new LinkedHashSet<>(before.values().keySet());
        keys.addAll(after.values().keySet());
        for (String k : keys) {
            if (schema.contains(k) && !Objects.equals(blank(before.values().get(k)), blank(after.values().get(k)))) {
                values.put(k, blank(after.values().get(k)));
            }
        }
        saveOverrides("PROFILE", module.id(), enabled, tools, values);
    }

    private SettingsSnapshot saveOverrides(String level, String moduleId, Boolean enabled, Map<String, Boolean> tools,
                                           Map<String, String> values) {
        Session owner = session;
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("enabled", enabled);
        input.put("tools", tools.entrySet().stream()
                .map(e -> Map.of("name", e.getKey(), "enabled", e.getValue())).toList());
        input.put("values", values.entrySet().stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", e.getKey());
            m.put("value", e.getValue());
            return m;
        }).toList());
        SettingsSnapshot s = query("mutation($level: Level!, $id: String!, $in: OverlayInput!) { saveOverrides("
                        + "level: $level, moduleId: $id, input: $in) { " + SETTINGS_FIELDS + " } }",
                Map.of("level", level, "id", moduleId, "in", input), "saveOverrides", SettingsSnapshot.class);
        onSettings(owner, s);
        return s;
    }

    private static String blank(String s) {
        return s == null ? "" : s;
    }

    // ---------------------------------------------------------------- GraphQL

    /** Führt eine Operation aus; fachliche Fehler als {@link IllegalArgumentException} mit der Meldung des Backends. */
    public <T> T query(String document, Map<String, Object> variables, String field, Class<T> type) {
        Session s = session; // vor dem Client lesen: ein Client ist nie älter als die gelesene Anmeldung
        return extract(execute(client(), document, variables), field, type, s);
    }

    public <T> List<T> queryList(String document, String field, Class<T> type) {
        return queryList(document, Map.of(), field, type);
    }

    public <T> List<T> queryList(String document, Map<String, Object> variables, String field, Class<T> type) {
        Session s = session;
        ClientGraphQlResponse r = execute(client(), document, variables);
        check(r, s);
        List<T> list = r.field(field).toEntityList(type);
        return list == null ? List.of() : list;
    }

    private ClientGraphQlResponse execute(HttpSyncGraphQlClient c, String document, Map<String, Object> variables) {
        try {
            return c.document(document).variables(variables).executeSync();
        } catch (RuntimeException e) {
            throw new UnreachableException("Backend nicht erreichbar (" + url() + "): " + describe(e), e);
        }
    }

    private <T> T extract(ClientGraphQlResponse r, String field, Class<T> type, Session sender) {
        check(r, sender);
        return r.field(field).getValue() == null ? null : r.field(field).toEntity(type);
    }

    /**
     * Fehler der Antwort als Exception. Lehnt das Backend das Token ab, ist {@code sender} (die Anmeldung, mit der
     * gefragt wurde) abgemeldet – eine spätere Anmeldung bleibt davon unberührt.
     */
    private void check(ClientGraphQlResponse r, Session sender) {
        if (r.getErrors().isEmpty()) {
            return;
        }
        ResponseError e = r.getErrors().getFirst();
        String type = String.valueOf(e.getErrorType());
        if ("BAD_REQUEST".equals(type) || "FORBIDDEN".equals(type)) {
            throw new IllegalArgumentException(e.getMessage());
        }
        if ("UNAUTHORIZED".equals(type)) {
            if (sender != null && !closing) {
                execute(() -> {
                    if (session == sender) {
                        signedOut(SESSION_INVALID);
                    }
                });
            }
            throw new IllegalStateException(SESSION_INVALID);
        }
        throw new IllegalStateException("Backend-Fehler: " + e.getMessage());
    }

    private HttpSyncGraphQlClient client() {
        HttpSyncGraphQlClient c = http;
        if (c == null) {
            throw new IllegalStateException(session == null ? "Nicht angemeldet."
                    : "Backend noch nicht verbunden" + (message.isEmpty() ? "" : ": " + message));
        }
        return c;
    }

    /** Client mit Token ({@code null} = ohne, für {@code login}); HTTP/1.1, Zeitlimits gegen hängende Server. */
    private static HttpSyncGraphQlClient httpClient(String url, String token) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofMinutes(2));
        RestClient.Builder rest = RestClient.builder().baseUrl(url + "/graphql").requestFactory(factory);
        if (token != null) {
            rest.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return HttpSyncGraphQlClient.builder(rest.build()).build();
    }

    // ---------------------------------------------------------------- Katalog, Import

    private void reportCatalogQuietly() {
        try {
            if (http != null && signedIn()) {
                reportCatalog();
            }
        } catch (RuntimeException e) {
            LOG.debug("Katalog nicht gemeldet: {}", e.getMessage());
        }
    }

    /** Meldet die Module, wenn sie sich seit der letzten Meldung geändert haben (z.B. Plugin installiert). */
    private synchronized void reportCatalog() {
        List<ModuleDescriptor> modules = registry.getObject().modules().stream().map(this::descriptor).toList();
        String serialized = json.writeValueAsString(modules);
        if (!serialized.equals(reportedCatalog)) {
            query("mutation($m: [ModuleInput!]!) { reportCatalog(modules: $m) }", Map.of("m", modules),
                    "reportCatalog", Boolean.class);
            reportedCatalog = serialized;
        }
    }

    private ModuleDescriptor descriptor(ToolModule m) {
        List<ModuleDescriptor.ToolDescriptor> tools = registry.getObject().availableTools(m.id()).stream()
                .map(d -> new ModuleDescriptor.ToolDescriptor(d.name(), d.description())).toList();
        return new ModuleDescriptor(m.id(), m.displayName(), m.description(), m.enabledByDefault(), m.hasTools(),
                m.order(), m.configSchema(), tools);
    }

    /** Erster eingebetteter Start: bisherige Modul-Einstellungen aus {@code settings.json} als globale Vorgaben. */
    private void importLegacySettings() {
        Path marker = store.dir().resolve(IMPORT_MARKER);
        if (Files.exists(marker)) {
            return;
        }
        int count = 0;
        for (ToolModule m : registry.getObject().modules()) {
            Optional<ModuleSettings> saved = store.module(m.id());
            if (saved.isEmpty()) {
                continue;
            }
            Set<String> schema = m.configSchema().stream().map(ConfigField::key).collect(Collectors.toSet());
            Map<String, String> values = new LinkedHashMap<>();
            saved.get().values().forEach((k, v) -> {
                if (schema.contains(k)) {
                    values.put(k, v);
                }
            });
            Map<String, Boolean> tools = new LinkedHashMap<>();
            saved.get().disabledTools().forEach(t -> tools.put(t, false));
            try {
                saveOverrides("GLOBAL", m.id(), saved.get().enabled(), tools, values);
                count++;
            } catch (RuntimeException e) {
                LOG.warn("Einstellungen von {} nicht übernommen: {}", m.id(), e.getMessage());
            }
        }
        try {
            Files.writeString(marker, Instant.now().toString());
        } catch (IOException e) {
            LOG.warn("Marker {} nicht schreibbar", marker, e);
        }
        LOG.info("Einstellungen von {} Modulen aus settings.json ins Backend übernommen", count);
    }

    // ---------------------------------------------------------------- Anwenden, Cache

    /** Neuer Stand: Schreibschutz für nur lesend freigegebene Projekte setzen, Tools neu aufbauen, UI informieren. */
    private void applied() {
        Set<Path> readOnly = projects().stream().filter(p -> !p.writable())
                .map(p -> projectPath(p.id())).flatMap(Optional::stream).collect(Collectors.toSet());
        ToolScope.LOCAL.restrictWrites(readOnly.isEmpty() ? null
                : root -> readOnly.stream().noneMatch(ro -> root.toAbsolutePath().normalize().startsWith(ro)));
        registry.getObject().refreshAll();
        notifyListeners();
    }

    private void notifyListeners() {
        listeners.forEach(BackendConnection::runQuietly);
    }

    private static void runQuietly(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            LOG.warn("Listener fehlgeschlagen", e);
        }
    }

    /** Stand des Team-Servers für die Anmeldung ohne Server; nicht beim eingebetteten Backend. */
    private void saveCache() {
        Session s = session;
        State st = state;
        if (embedded() || s == null || st == null || st.me() == null) {
            return;
        }
        try {
            CacheFile c = new CacheFile(st, st.me().username(), s.personal() ? null : verifier,
                    s.personal() ? s.token() : null);
            Files.writeString(cacheFile, store.encrypt(json.writeValueAsString(c)));
        } catch (IOException | RuntimeException e) {
            LOG.warn("Cache {} nicht schreibbar", cacheFile, e);
        }
    }

    private CacheFile readCache() {
        if (!Files.isRegularFile(cacheFile)) {
            return null;
        }
        try {
            CacheFile c = json.readValue(store.decrypt(Files.readString(cacheFile)), CacheFile.class);
            return c.state() == null ? null : c; // älteres Format ohne Anmeldung: unbrauchbar
        } catch (IOException | RuntimeException e) {
            LOG.warn("Cache {} nicht lesbar – wird beim nächsten Abgleich neu geschrieben", cacheFile, e);
            return null;
        }
    }

    // ---------------------------------------------------------------- intern

    private void requireOpen() {
        if (closing) {
            throw new IllegalStateException("Die App wird beendet.");
        }
    }

    private void execute(Runnable task) {
        try {
            executor.execute(task);
        } catch (RejectedExecutionException e) {
            LOG.debug("Beim Beenden verworfen");
        }
    }

    private void schedule(Runnable task, Duration delay) {
        try {
            executor.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            LOG.debug("Beim Beenden verworfen");
        }
    }

    private static String hostName() {
        String name = System.getenv("COMPUTERNAME");
        if (name == null || name.isBlank()) {
            name = System.getenv("HOSTNAME");
        }
        return name == null || name.isBlank() ? "unbekannter Rechner" : name;
    }

    private static String describe(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String m = root.getMessage() != null ? root.getMessage() : e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m;
    }
}
