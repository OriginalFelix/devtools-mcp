package systems.grebe.devtools.mcp.remote;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
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
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.socket.client.StandardWebSocketClient;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import systems.grebe.devtools.mcp.api.Me;
import systems.grebe.devtools.mcp.api.ModuleDescriptor;
import systems.grebe.devtools.mcp.api.ModuleOverlay;
import systems.grebe.devtools.mcp.api.ProjectInfo;
import systems.grebe.devtools.mcp.api.SettingsSnapshot;
import systems.grebe.devtools.mcp.config.ModuleSettings;
import systems.grebe.devtools.mcp.config.SettingsStore;
import systems.grebe.devtools.mcp.config.TeamSettings;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.ToolModule;
import systems.grebe.devtools.mcp.core.ToolRegistry;
import systems.grebe.devtools.mcp.core.ToolScope;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verbindung der Desktop-App zu ihrem Backend über GraphQL – eingebettet ({@link EmbeddedBackend}, lokaler Benutzer)
 * oder auf einem Team-Server (Adresse + Desktop-Token in den Einstellungen; Wechsel nach Neustart).
 *
 * <p>Beim Start meldet die App ihre Module ({@code reportCatalog}), lädt Benutzer, Vorgaben des aktiven Profils und
 * Projekte und abonniert {@code settingsChanged}, {@code projectsChanged}, {@code skillsChanged} und
 * {@code scriptsChanged} per WebSocket. Jede
 * Änderung baut die Tools neu – verbundene MCP-Clients bekommen {@code tools/list_changed}. Bricht die Verbindung ab,
 * verbinden sich die Subscriptions mit wachsendem Abstand neu und liefern dabei den aktuellen Stand; dazwischen gilt
 * der letzte (beim Team-Server auch über einen Neustart hinweg: verschlüsselte Cache-Datei {@code team-cache.json}).
 *
 * <p>Beim ersten eingebetteten Start übernimmt das Backend die bisherigen Modul-Einstellungen aus
 * {@code settings.json} als globale Vorgaben.
 */
@Component
public class BackendConnection {

    private static final Logger LOG = LoggerFactory.getLogger(BackendConnection.class);
    private static final String IMPORT_MARKER = "backend-import.done";
    /** Ping auf der Subscription-Verbindung, deutlich unter dem Idle-Timeout von Jetty (30 s) und Reverse-Proxys. */
    private static final Duration WS_KEEP_ALIVE = Duration.ofSeconds(15);

    static final String ME = "{ me { id username displayName email admin profiles { id name description } "
            + "activeProfileId } }";
    static final String SETTINGS_FIELDS = "profileId profileName modules { moduleId enabled tools { name enabled } "
            + "values { key value } locked } revision";
    static final String PROJECT_FIELDS = "id name owner toolName writable access description sonarKey ticketProject";

    /** Verbindungszustand für die Anzeige. */
    public enum Status {
        /** Noch kein Stand vom Backend. */
        CONNECTING,
        /** Verbunden, Subscriptions laufen. */
        ONLINE,
        /** Backend gerade nicht erreichbar – es gilt der letzte Stand. */
        OFFLINE,
        /** Anmeldung abgelehnt o.ä. – ohne Eingriff geht es nicht weiter. */
        ERROR
    }

    /** Stand vom Backend; {@code null}-Felder = (noch) unbekannt. */
    record State(String url, Me me, SettingsSnapshot settings, List<ProjectInfo> projects) {
    }

    private final SettingsStore store;
    private final ObjectProvider<ToolRegistry> registry;
    private final ObjectProvider<LocalUser> localUser;
    private final Environment env;
    private final Path cacheFile;
    private final JsonMapper json = JsonMapper.builder().build();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> skillListeners = new CopyOnWriteArrayList<>();
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
    private volatile Status status = Status.CONNECTING;
    private volatile String message = "";
    private volatile Instant lastSync;
    private String reportedCatalog;

    public BackendConnection(SettingsStore store, ObjectProvider<ToolRegistry> registry,
                             ObjectProvider<LocalUser> localUser, Environment env) {
        this.store = store;
        this.registry = registry;
        this.localUser = localUser;
        this.env = env;
        this.cacheFile = store.dir().resolve("team-cache.json");
    }

    // ---------------------------------------------------------------- Lebenszyklus

    /** Nach der Registrierung der Tools ({@link ToolRegistry#registerAll}), damit der Katalog sie enthält. */
    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void start() {
        if (embedded()) {
            connect(); // lokal und schnell: synchron, damit die App mit den Vorgaben startet
        } else {
            loadCache();
            executor.execute(this::connectQuietly);
        }
        registry.getObject().addChangeListener(() -> executor.execute(this::reportCatalogQuietly));
    }

    @PreDestroy
    public void stop() {
        subscriptions.forEach(Disposable::dispose);
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

    private void connectQuietly() {
        try {
            connect();
        } catch (RuntimeException e) {
            LOG.debug("Backend nicht erreichbar: {}", e.getMessage());
            executor.schedule(this::connectQuietly, 15, TimeUnit.SECONDS);
        }
    }

    private synchronized void connect() {
        String url = url();
        String token = embedded() ? localUser.getObject().token() : store.team().token();
        try {
            http = HttpSyncGraphQlClient.builder(RestClient.builder().baseUrl(url + "/graphql")
                    .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token).build()).build();
            WebSocketGraphQlClient previous = ws;
            ws = WebSocketGraphQlClient.builder(URI.create(url.replaceFirst("^http", "ws") + "/graphql"),
                    new StandardWebSocketClient()).keepAlive(WS_KEEP_ALIVE)
                    .interceptor(new WebSocketGraphQlClientInterceptor() {
                        @Override
                        public Mono<Object> connectionInitPayload() {
                            return Mono.just(Map.of(HttpHeaders.AUTHORIZATION, "Bearer " + token));
                        }
                    }).build();
            if (previous != null) {
                previous.stop().subscribe();
            }
            reportCatalog();
            if (embedded()) {
                importLegacySettings();
            }
            Me me = query(ME, Map.of(), "me", Me.class);
            SettingsSnapshot settings = query("{ settings { " + SETTINGS_FIELDS + " } }", Map.of(), "settings",
                    SettingsSnapshot.class);
            List<ProjectInfo> projects = queryList("{ projects { " + PROJECT_FIELDS + " } }", "projects",
                    ProjectInfo.class);
            online(new State(url, me, settings, projects));
            subscribe();
        } catch (RuntimeException e) {
            status = state != null && state.settings() != null ? Status.OFFLINE : Status.ERROR;
            message = describe(e);
            notifyListeners();
            throw e;
        }
    }

    private void subscribe() {
        subscriptions.forEach(Disposable::dispose);
        subscriptions.clear();
        Retry retry = Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(30))
                .doBeforeRetry(r -> offline(r.failure()));
        subscriptions.add(ws.document("subscription { settingsChanged { " + SETTINGS_FIELDS + " } }")
                .retrieveSubscription("settingsChanged").toEntity(SettingsSnapshot.class)
                .retryWhen(retry).subscribe(s -> executor.execute(() -> onSettings(s)), this::offline));
        subscriptions.add(ws.document("subscription { projectsChanged { " + PROJECT_FIELDS + " } }")
                .retrieveSubscription("projectsChanged").toEntityList(ProjectInfo.class)
                .retryWhen(retry).subscribe(p -> executor.execute(() -> onProjects(p)), this::offline));
        subscriptions.add(ws.document("subscription { skillsChanged }")
                .retrieveSubscription("skillsChanged").toEntity(Integer.class)
                .retryWhen(retry).subscribe(n -> skillListeners.forEach(Runnable::run), this::offline));
        subscriptions.add(ws.document("subscription { scriptsChanged }")
                .retrieveSubscription("scriptsChanged").toEntity(Integer.class)
                .retryWhen(retry).subscribe(n -> scriptListeners.forEach(Runnable::run), this::offline));
    }

    private void onSettings(SettingsSnapshot settings) {
        State s = state;
        Me me = s == null ? null : s.me();
        try {
            me = query(ME, Map.of(), "me", Me.class); // Profile können sich mitgeändert haben
        } catch (RuntimeException e) {
            LOG.debug("Benutzer nicht neu geladen: {}", e.getMessage());
        }
        online(new State(url(), me, settings, s == null ? null : s.projects()));
    }

    private void onProjects(List<ProjectInfo> projects) {
        State s = state;
        online(new State(url(), s == null ? null : s.me(), s == null ? null : s.settings(), projects));
    }

    private synchronized void online(State next) {
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
            if (!embedded()) {
                saveCache();
            }
            applied();
        } else {
            notifyListeners();
        }
    }

    private void offline(Throwable error) {
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

    /** Wird nach jeder Änderung aufgerufen (beliebiger Thread). */
    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    /** Wird aufgerufen, wenn sich Skills geändert haben (beliebiger Thread). */
    public void addSkillListener(Runnable listener) {
        skillListeners.add(listener);
    }

    /**
     * Wird aufgerufen, wenn sich Groovy-Skripte geändert haben – und beim (Wieder-)Aufbau der Subscription mit dem
     * aktuellen Stand (beliebiger Thread).
     */
    public void addScriptListener(Runnable listener) {
        scriptListeners.add(listener);
    }

    // ---------------------------------------------------------------- Ändern

    /**
     * Trägt einen Team-Server ein (Adresse + Desktop-Token) – geprüft per {@code me}; wirksam nach einem Neustart.
     * {@code url} leer: zurück zum eingebetteten Backend.
     *
     * @return der Benutzer auf dem Server bzw. leer
     */
    public Optional<Me> configureServer(String url, String token) {
        if (url == null || url.isBlank()) {
            store.saveTeam(new TeamSettings("", "", store.team().projectPaths()));
            return Optional.empty();
        }
        TeamSettings next = new TeamSettings(url, token, store.team().projectPaths());
        HttpSyncGraphQlClient probe = HttpSyncGraphQlClient.builder(RestClient.builder()
                .baseUrl(next.url() + "/graphql")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + next.token()).build()).build();
        Me me = extract(probe.document(ME).executeSync(), "me", Me.class);
        store.saveTeam(next);
        return Optional.of(me);
    }

    /** Ordnet einem Projekt ein lokales Verzeichnis zu ({@code null}/leer = Zuordnung entfernen). */
    public void setProjectPath(long projectId, String path) {
        synchronized (this) {
            store.saveTeam(store.team().withProjectPath(projectId, path));
        }
        applied();
    }

    public void activateProfile(long profileId) {
        SettingsSnapshot s = query("mutation($id: Int!) { activateProfile(profileId: $id) { " + SETTINGS_FIELDS
                + " } }", Map.of("id", profileId), "activateProfile", SettingsSnapshot.class);
        onSettings(s);
    }

    public ProjectInfo createProject(String name, String description) {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("name", name);
        vars.put("description", description);
        ProjectInfo p = query("mutation($name: String!, $description: String) { createProject(name: $name, "
                + "description: $description) { " + PROJECT_FIELDS + " } }", vars, "createProject", ProjectInfo.class);
        onProjects(queryList("{ projects { " + PROJECT_FIELDS + " } }", "projects", ProjectInfo.class));
        return p;
    }

    /**
     * Speichert eine Änderung aus dem Modul-Formular als Überschreibung im aktiven Profil – nur, was sich gegenüber
     * {@code before} geändert hat. Gesperrte Felder lehnt das Backend ab.
     */
    public void save(ToolModule module, ModuleSettings before, ModuleSettings after) {
        ModuleOverlay current = query("query($id: String!) { overrides(level: PROFILE, moduleId: $id) { moduleId "
                        + "enabled tools { name enabled } values { key value } locked } }", Map.of("id", module.id()),
                "overrides", ModuleOverlay.class);
        Boolean enabled = before.enabled() != after.enabled() ? Boolean.valueOf(after.enabled()) : current.enabled();
        Map<String, Boolean> tools = new LinkedHashMap<>(current.toolMap());
        Set<String> changedTools = new LinkedHashSet<>(before.disabledTools());
        changedTools.addAll(after.disabledTools());
        changedTools.removeIf(t -> before.disabledTools().contains(t) == after.disabledTools().contains(t));
        changedTools.forEach(t -> tools.put(t, !after.disabledTools().contains(t)));
        Map<String, String> values = new LinkedHashMap<>(current.valueMap());
        Set<String> schema = module.configSchema().stream().map(ConfigField::key).collect(Collectors.toSet());
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
        onSettings(s);
        return s;
    }

    private static String blank(String s) {
        return s == null ? "" : s;
    }

    // ---------------------------------------------------------------- GraphQL

    /** Führt eine Operation aus; fachliche Fehler als {@link IllegalArgumentException} mit der Meldung des Backends. */
    public <T> T query(String document, Map<String, Object> variables, String field, Class<T> type) {
        return extract(execute(document, variables), field, type);
    }

    public <T> List<T> queryList(String document, String field, Class<T> type) {
        return queryList(document, Map.of(), field, type);
    }

    public <T> List<T> queryList(String document, Map<String, Object> variables, String field, Class<T> type) {
        ClientGraphQlResponse r = execute(document, variables);
        check(r);
        List<T> list = r.field(field).toEntityList(type);
        return list == null ? List.of() : list;
    }

    private ClientGraphQlResponse execute(String document, Map<String, Object> variables) {
        HttpSyncGraphQlClient c = client();
        try {
            return c.document(document).variables(variables).executeSync();
        } catch (RuntimeException e) {
            throw new IllegalStateException("Backend nicht erreichbar (" + url() + "): " + describe(e), e);
        }
    }

    private static <T> T extract(ClientGraphQlResponse r, String field, Class<T> type) {
        check(r);
        return r.field(field).getValue() == null ? null : r.field(field).toEntity(type);
    }

    private static void check(ClientGraphQlResponse r) {
        if (r.getErrors().isEmpty()) {
            return;
        }
        ResponseError e = r.getErrors().getFirst();
        String type = String.valueOf(e.getErrorType());
        if ("BAD_REQUEST".equals(type) || "FORBIDDEN".equals(type)) {
            throw new IllegalArgumentException(e.getMessage());
        }
        if ("UNAUTHORIZED".equals(type)) {
            throw new IllegalStateException("Desktop-Token ungültig, abgelaufen oder widerrufen – neues Token in der "
                    + "Web-UI unter „Mein Konto“ erzeugen.");
        }
        throw new IllegalStateException("Backend-Fehler: " + e.getMessage());
    }

    private HttpSyncGraphQlClient client() {
        HttpSyncGraphQlClient c = http;
        if (c == null) {
            throw new IllegalStateException("Backend noch nicht verbunden" + (message.isEmpty() ? "" : ": " + message));
        }
        return c;
    }

    // ---------------------------------------------------------------- Katalog, Import

    private void reportCatalogQuietly() {
        try {
            if (http != null) {
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
        listeners.forEach(l -> {
            try {
                l.run();
            } catch (RuntimeException e) {
                LOG.warn("Listener fehlgeschlagen", e);
            }
        });
    }

    private void saveCache() {
        try {
            Files.writeString(cacheFile, store.encrypt(json.writeValueAsString(state)));
        } catch (IOException | RuntimeException e) {
            LOG.warn("Cache {} nicht schreibbar", cacheFile, e);
        }
    }

    private void loadCache() {
        if (!Files.isRegularFile(cacheFile)) {
            return;
        }
        try {
            State cached = json.readValue(store.decrypt(Files.readString(cacheFile)), State.class);
            if (store.team().url().equals(cached.url())) {
                state = cached;
                status = Status.OFFLINE;
                message = "Letzter Stand vom Server (noch nicht verbunden)";
                applied();
            }
        } catch (IOException | RuntimeException e) {
            LOG.warn("Cache {} nicht lesbar – wird beim nächsten Abgleich neu geschrieben", cacheFile, e);
        }
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
