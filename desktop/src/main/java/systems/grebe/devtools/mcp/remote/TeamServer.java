package systems.grebe.devtools.mcp.remote;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.api.Catalog;
import systems.grebe.devtools.mcp.api.Me;
import systems.grebe.devtools.mcp.api.ModuleDescriptor;
import systems.grebe.devtools.mcp.api.ProjectInfo;
import systems.grebe.devtools.mcp.api.SettingsSnapshot;
import systems.grebe.devtools.mcp.config.SettingsStore;
import systems.grebe.devtools.mcp.config.TeamSettings;
import systems.grebe.devtools.mcp.core.ToolModule;
import systems.grebe.devtools.mcp.core.ToolRegistry;
import systems.grebe.devtools.mcp.core.ToolScope;
import tools.jackson.databind.json.JsonMapper;

/**
 * Anbindung der Desktop-App an einen Team-Server (optional, Einstellungen → Server).
 *
 * <p>Die App meldet dem Server ihre Module ({@code PUT /api/catalog}) und holt sich alle 30 Sekunden den Benutzer mit
 * seinen Profilen, die Einstellungs-Vorgaben des aktiven Profils und die sichtbaren Projekte (per {@code ETag}, meist
 * nur {@code 304}). Ändert sich etwas, baut die {@link ToolRegistry} die Tools neu – verbundene MCP-Clients bekommen
 * {@code tools/list_changed}. Den letzten Stand hält eine verschlüsselte Cache-Datei ({@code team-cache.json}), damit
 * die App auch ohne Server mit den Vorgaben weiterarbeitet.
 *
 * <p>Ohne eingetragenen Server verhält sich die App wie im Einzelplatz-Betrieb.
 */
@Component
public class TeamServer {

    private static final Logger LOG = LoggerFactory.getLogger(TeamServer.class);

    /** Verbindungszustand für die Anzeige. */
    public enum Status {
        /** Kein Server eingetragen. */
        OFF,
        /** Letzter Abgleich erfolgreich. */
        ONLINE,
        /** Server gerade nicht erreichbar – es gilt der letzte bekannte Stand. */
        OFFLINE,
        /** Anmeldung abgelehnt o.ä. – ohne Eingriff geht es nicht weiter. */
        ERROR
    }

    /** Stand vom Server; {@code null}-Felder = (noch) unbekannt. */
    record State(String url, Me me, SettingsSnapshot settings, List<ProjectInfo> projects) {
    }

    private final SettingsStore store;
    /** Lazy: Module (z.B. Projekte) brauchen den TeamServer, die Registry braucht die Module. */
    private final ObjectProvider<ToolRegistry> registry;
    private final Path cacheFile;
    private final long intervalSeconds;
    private final JsonMapper json = JsonMapper.builder().build();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private ScheduledExecutorService scheduler;

    private volatile State state;
    private volatile Status status = Status.OFF;
    private volatile String message = "";
    private volatile Instant lastSync;
    private String settingsEtag;
    private String projectsEtag;
    private String reportedCatalog;

    public TeamServer(SettingsStore store, ObjectProvider<ToolRegistry> registry,
                      @Value("${devtools.team.sync-seconds:30}") long intervalSeconds) {
        this.store = store;
        this.registry = registry;
        this.cacheFile = store.file().toAbsolutePath().resolveSibling("team-cache.json");
        this.intervalSeconds = intervalSeconds;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        loadCache();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "team-server-sync");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::syncQuietly, 0, intervalSeconds, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- Lesen

    /** Ob Vorgaben vom Server gelten (verbunden oder aus dem Cache). */
    public boolean active() {
        State s = state;
        return store.team().configured() && s != null && s.settings() != null;
    }

    public Status status() {
        return status;
    }

    /** Letzte Meldung (Fehler oder Hinweis) für die Anzeige. */
    public String message() {
        return message;
    }

    public Optional<Instant> lastSync() {
        return Optional.ofNullable(lastSync);
    }

    public Optional<Me> me() {
        return Optional.ofNullable(state).map(State::me);
    }

    public Optional<SettingsSnapshot> settings() {
        return active() ? Optional.of(state.settings()) : Optional.empty();
    }

    public List<ProjectInfo> projects() {
        State s = state;
        return store.team().configured() && s != null && s.projects() != null ? s.projects() : List.of();
    }

    /** Lokales Verzeichnis eines Server-Projekts, falls zugeordnet und vorhanden. */
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

    public TeamSettings settingsOfConnection() {
        return store.team();
    }

    /** Wird nach jeder Änderung aufgerufen (beliebiger Thread). */
    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    // ---------------------------------------------------------------- Ändern

    /**
     * Verbindet mit einem Server: prüft Adresse und Token ({@code /api/me}), speichert sie und gleicht sofort ab.
     *
     * @throws TeamServerException wenn der Server nicht erreichbar ist oder das Token ablehnt
     */
    public synchronized Me connect(String url, String token) {
        TeamSettings next = new TeamSettings(url, token, Objects.equals(store.team().url(), url.strip())
                ? store.team().projectPaths() : java.util.Map.of());
        Me me = new ServerClient(next.url(), next.token()).me();
        store.saveTeam(next);
        state = new State(next.url(), me, null, null);
        settingsEtag = null;
        projectsEtag = null;
        reportedCatalog = null;
        sync();
        return me;
    }

    /** Trennt vom Server: Vorgaben, Projekte und Cache entfallen, es gelten wieder die lokalen Einstellungen. */
    public synchronized void disconnect() {
        store.saveTeam(TeamSettings.none());
        state = null;
        settingsEtag = null;
        projectsEtag = null;
        reportedCatalog = null;
        status = Status.OFF;
        message = "";
        try {
            Files.deleteIfExists(cacheFile);
        } catch (IOException e) {
            LOG.warn("Cache {} nicht löschbar", cacheFile, e);
        }
        applied();
    }

    /** Ordnet einem Server-Projekt ein lokales Verzeichnis zu ({@code null}/leer = Zuordnung entfernen). */
    public void setProjectPath(long projectId, String path) {
        synchronized (this) {
            store.saveTeam(store.team().withProjectPath(projectId, path));
        }
        applied();
    }

    /** Wechselt das aktive Profil auf dem Server und übernimmt dessen Vorgaben. */
    public void activateProfile(long profileId) {
        client().activateProfile(profileId);
        sync();
    }

    /** Gleicht sofort ab; Fehler landen in {@link #status()} / {@link #message()} und werden geworfen. */
    public synchronized void sync() {
        TeamSettings team = store.team();
        if (!team.configured()) {
            status = Status.OFF;
            return;
        }
        try {
            ServerClient client = client();
            Me me = client.me();
            reportCatalog(client);
            State old = state != null && team.url().equals(state.url()) ? state : new State(team.url(), null, null, null);
            ServerClient.Fetched<SettingsSnapshot> s = client.settings(old.settings() == null ? null : settingsEtag);
            ServerClient.Fetched<List<ProjectInfo>> p = client.projects(old.projects() == null ? null : projectsEtag);
            State next = new State(team.url(), me, s.notModified() ? old.settings() : s.body(),
                    p.notModified() ? old.projects() : p.body());
            settingsEtag = s.etag();
            projectsEtag = p.etag();
            boolean changed = !next.equals(old);
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
        } catch (TeamServerException e) {
            status = e.permanent() || state == null || state.settings() == null ? Status.ERROR : Status.OFFLINE;
            message = e.getMessage();
            notifyListeners();
            throw e;
        }
    }

    private void syncQuietly() {
        try {
            sync();
        } catch (TeamServerException e) {
            LOG.debug("Abgleich mit dem Team-Server fehlgeschlagen: {}", e.getMessage());
        } catch (RuntimeException e) {
            LOG.warn("Abgleich mit dem Team-Server fehlgeschlagen", e);
        }
    }

    /** Client mit der eingetragenen Adresse und dem Token (z.B. für die Skills). */
    public ServerClient client() {
        TeamSettings team = store.team();
        if (!team.configured()) {
            throw new TeamServerException("Kein Team-Server eingetragen.", null, true);
        }
        return new ServerClient(team.url(), team.token());
    }

    /** Meldet die Module, wenn sie sich seit der letzten Meldung geändert haben (z.B. Plugin installiert). */
    private void reportCatalog(ServerClient client) {
        Catalog catalog = catalog();
        String serialized = json.writeValueAsString(catalog);
        if (!serialized.equals(reportedCatalog)) {
            client.putCatalog(catalog);
            reportedCatalog = serialized;
        }
    }

    Catalog catalog() {
        return new Catalog(registry.getObject().modules().stream().map(this::descriptor).toList());
    }

    private ModuleDescriptor descriptor(ToolModule m) {
        List<ModuleDescriptor.ToolDescriptor> tools = registry.getObject().availableTools(m.id()).stream()
                .map(d -> new ModuleDescriptor.ToolDescriptor(d.name(), d.description())).toList();
        return new ModuleDescriptor(m.id(), m.displayName(), m.description(), m.enabledByDefault(), m.hasTools(),
                m.order(), m.configSchema(), tools);
    }

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

    // ---------------------------------------------------------------- Cache

    private void saveCache() {
        try {
            Files.writeString(cacheFile, store.encrypt(json.writeValueAsString(state)));
        } catch (IOException | RuntimeException e) {
            LOG.warn("Cache {} nicht schreibbar", cacheFile, e);
        }
    }

    private void loadCache() {
        TeamSettings team = store.team();
        if (!team.configured() || !Files.isRegularFile(cacheFile)) {
            return;
        }
        try {
            State cached = json.readValue(store.decrypt(Files.readString(cacheFile)), State.class);
            if (team.url().equals(cached.url())) {
                state = cached;
                status = Status.OFFLINE;
                message = "Letzter Stand vom Server (noch nicht abgeglichen)";
                applied();
            }
        } catch (IOException | RuntimeException e) {
            LOG.warn("Cache {} nicht lesbar – wird beim nächsten Abgleich neu geschrieben", cacheFile, e);
        }
    }
}
