package systems.grebe.devtools.mcp.modules.dolt;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolCallListener;
import systems.grebe.devtools.mcp.core.ToolRegistry;

/**
 * Bemerkt Branch-Wechsel in den Git-Arbeitsverzeichnissen der eingetragenen Datenbanken und lässt
 * {@link DoltBranches} abgleichen – egal, ob über git_checkout, die IDE oder die Shell gewechselt wurde:
 * <ul>
 *   <li>ein {@link WatchService} auf dem Git-Verzeichnis (dort liegt {@code HEAD}) meldet den Wechsel binnen
 *   Millisekunden, damit eine gleich danach gestartete Anwendung schon den neuen Branch bekommt;</li>
 *   <li>alle {@link #POLL} wird zusätzlich nachgesehen (Netzlaufwerke, verpasste Ereignisse) und ein gescheiterter
 *   Abgleich wiederholt, etwa wenn der Datenbank-Server erst jetzt läuft;</li>
 *   <li>nach jedem git_*-Tool wird synchron abgeglichen und dem LLM ins Ergebnis geschrieben, was sich geändert hat.</li>
 * </ul>
 * Aktiv nur, solange das Modul eingeschaltet ist und Datenbanken eingetragen sind; Abgleiche laufen nacheinander in
 * einem eigenen Thread, die Ereignisse werden zusammengefasst.
 */
@Component
public class DoltBranchWatcher implements ToolCallListener {

    private static final Logger LOG = LoggerFactory.getLogger(DoltBranchWatcher.class);

    static final Duration POLL = Duration.ofSeconds(10);
    /** Gescheiterte Abgleiche frühestens nach dieser Zeit wiederholen (ein toter Server soll Git nicht bremsen). */
    static final Duration RETRY = Duration.ofSeconds(15);
    /** Ältere Änderungen erwähnt das Ergebnis eines git_*-Tools nicht mehr. */
    static final Duration REPORT_MAX_AGE = Duration.ofMinutes(2);
    private static final long DEBOUNCE_MILLIS = 50;

    @FunctionalInterface
    interface WatchServiceFactory {
        WatchService create() throws IOException;
    }

    /** Eingetragene Datenbanken bei eingeschaltetem Modul. */
    record Snapshot(List<DoltDatabase> databases, DoltBranches.Settings settings) {
        static final Snapshot EMPTY = new Snapshot(List.of(), new DoltBranches.Settings(5, ""));
    }

    private final ObjectProvider<ToolRegistry> registry;
    private final DoltBranches branches;
    private final ThreadPoolExecutor syncs;
    private volatile Snapshot current = Snapshot.EMPTY;
    private final Map<Path, WatchKey> watched = new HashMap<>();
    private volatile WatchService watch;
    /** Erzeugt den WatchService; für Tests austauschbar. */
    WatchServiceFactory watchServices = () -> FileSystems.getDefault().newWatchService();
    /** Weckt die Schleife, wenn der WatchService erst nach ihrem Start entsteht (statt bis zur nächsten Abfrage zu schlafen). */
    private final java.util.concurrent.Semaphore watchCreated = new java.util.concurrent.Semaphore(0);
    private Thread loop;
    private volatile boolean stopped;

    public DoltBranchWatcher(ObjectProvider<ToolRegistry> registry, DoltBranches branches) {
        this.registry = registry;
        this.branches = branches;
        // ein Abgleich läuft, höchstens einer wartet – jeder prüft alle Datenbanken, weitere wären doppelt
        this.syncs = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1),
                Thread.ofPlatform().daemon().name("dolt-branch-sync").factory(), new ThreadPoolExecutor.DiscardPolicy());
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(10) // nach dem Registrieren der Tools
    public void start() {
        ToolRegistry r = registry.getIfAvailable();
        if (r == null) {
            return;
        }
        r.addChangeListener(this::reconfigure);
        reconfigure();
    }

    /** Übernimmt Schalter und Einstellungen des Moduls (bei jeder Änderung an einem Modul aufgerufen – billig). */
    void reconfigure() {
        ToolRegistry r = registry.getIfAvailable();
        if (r == null || !r.hasModule(DoltModule.ID)) {
            return;
        }
        Snapshot next = Snapshot.EMPTY;
        if (r.settings(DoltModule.ID).enabled()) {
            ModuleConfig cfg = r.config(DoltModule.ID);
            next = new Snapshot(DoltModule.databases(cfg), DoltModule.settings(cfg));
        }
        apply(next);
    }

    /** Setzt die beobachteten Datenbanken und gleicht neue bzw. geänderte im Hintergrund ab. */
    synchronized void apply(Snapshot next) {
        if (next.equals(current) || stopped) {
            return;
        }
        current = next;
        branches.retain(next.databases());
        Set<Path> dirs = new LinkedHashSet<>();
        for (DoltDatabase db : next.databases()) {
            try {
                dirs.add(GitHead.gitDir(db.repositoryPath()));
            } catch (IOException | IllegalStateException e) {
                LOG.debug("Git-Verzeichnis von {} nicht ermittelbar – nur Abfrage alle {} s: {}", db.name(),
                        POLL.toSeconds(), e.getMessage());
            }
        }
        watch(dirs, !next.databases().isEmpty());
        if (!next.databases().isEmpty()) {
            syncs.execute(this::checkAll);
        }
    }

    /** Nach jedem git_*-Tool: Wechsel sofort abgleichen und dem LLM melden, was sich an den Datenbanken getan hat. */
    @Override
    public String afterSuccess(ToolCall call, String result) {
        Snapshot s = current;
        if (!"git".equals(call.moduleId()) || s.databases().isEmpty()) {
            return result;
        }
        for (DoltDatabase db : s.databases()) {
            branches.syncIfNeeded(db, s.settings(), RETRY);
        }
        List<DoltBranches.Outcome> news = branches.takeUnreported(s.databases(), REPORT_MAX_AGE);
        if (news.isEmpty()) {
            return result;
        }
        return result + "\n\nDatenbank-Branches (DevTools):\n" + news.stream().map(o -> "- " + o.describe())
                .collect(Collectors.joining("\n"));
    }

    @PreDestroy
    public synchronized void stop() {
        stopped = true;
        syncs.shutdownNow();
        if (watch != null) {
            try {
                watch.close(); // beendet die Schleife
            } catch (IOException e) {
                // ignorieren
            }
        }
    }

    // ------------------------------------------------------------------ intern

    private void checkAll() {
        Snapshot s = current;
        for (DoltDatabase db : s.databases()) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            try {
                branches.syncIfNeeded(db, s.settings(), RETRY);
            } catch (RuntimeException e) {
                LOG.warn("Abgleich von {} fehlgeschlagen", db.name(), e);
            }
        }
    }

    /**
     * Beobachtet genau diese Git-Verzeichnisse; Schleife und WatchService entstehen erst beim ersten Bedarf – auch
     * ohne beobachtbares Verzeichnis, damit die regelmäßige Abfrage läuft (Repository erst später angelegt).
     */
    private void watch(Set<Path> dirs, boolean needed) {
        watched.entrySet().removeIf(e -> {
            if (!dirs.contains(e.getKey())) {
                e.getValue().cancel();
                return true;
            }
            return false;
        });
        if (!needed && loop == null) {
            return;
        }
        try {
            if (watch == null) {
                watch = watchServices.create();
                watchCreated.release();
            }
            for (Path dir : dirs) {
                if (!watched.containsKey(dir)) {
                    watched.put(dir, dir.register(watch, StandardWatchEventKinds.ENTRY_CREATE,
                            StandardWatchEventKinds.ENTRY_MODIFY));
                }
            }
        } catch (IOException e) {
            LOG.warn("Git-Verzeichnisse nicht beobachtbar – Branch-Wechsel werden nur alle {} s erkannt: {}",
                    POLL.toSeconds(), e.getMessage());
        }
        if (loop == null) {
            loop = Thread.ofPlatform().daemon().name("dolt-branch-watch").start(this::run);
        }
    }

    private void run() {
        while (!stopped) {
            try {
                // jedes Mal neu lesen: Scheiterte der WatchService beim ersten Start, entsteht er bei einer späteren
                // Konfiguration - und soll dann auch abgefragt werden, nicht nur die 10-s-Abfrage laufen
                WatchService ws = watch;
                WatchKey key = ws == null ? null : ws.poll(POLL.toMillis(), TimeUnit.MILLISECONDS);
                if (ws == null) {
                    watchCreated.tryAcquire(POLL.toMillis(), TimeUnit.MILLISECONDS);
                }
                if (key != null) {
                    // git schreibt HEAD.lock, benennt um, aktualisiert Index und Reflog – zusammenfassen
                    do {
                        key.pollEvents();
                        key.reset();
                        key = ws.poll(DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS);
                    } while (key != null);
                }
                syncs.execute(this::checkAll); // nach Ereignis sofort, sonst als regelmäßige Abfrage
            } catch (InterruptedException | ClosedWatchServiceException e) {
                return;
            } catch (RuntimeException e) {
                LOG.warn("Beobachtung der Git-Verzeichnisse gestört", e);
            }
        }
    }
}
