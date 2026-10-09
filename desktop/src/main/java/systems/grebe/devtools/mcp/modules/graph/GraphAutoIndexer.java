package systems.grebe.devtools.mcp.modules.graph;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.core.ModuleAction;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolRegistry;
import systems.grebe.devtools.mcp.api.Errors;

/**
 * Indiziert die Graph-Projekte automatisch: beobachtet je Projekt den ausgecheckten Branch, seinen Commit und die
 * lokalen Branches (alle paar Sekunden, egal ob der Commit aus DevTools, der IDE oder der Shell kommt) und
 * <ul>
 *   <li>baut bei einem neuen Commit oder Branch-Wechsel den Graphen des ausgecheckten Branches – inkrementell
 *       ({@link GraphService#build}: nur geänderte Dateien lesen, nur den Unterschied speichern; ein Branch mit gleichem
 *       Stand wie ein gespeicherter übernimmt dessen Graphen),</li>
 *   <li>lässt einen neuen, nicht ausgecheckten Branch auf dem ausgecheckten Commit den vorhandenen Graphen übernehmen
 *       ({@link GraphService#adopt}).</li>
 * </ul>
 * Nur bei eingeschaltetem Modul „Code-Graph“ und Einstellung „Automatisch indizieren“. Beim Start wird nur der Stand
 * gemerkt, gebaut wird erst bei einer Änderung.
 */
@Component
public class GraphAutoIndexer {

    private static final Logger LOG = LoggerFactory.getLogger(GraphAutoIndexer.class);

    private final Supplier<ModuleConfig> config;
    private final Function<ModuleConfig, GraphService> services;
    private final Duration interval;
    private final Map<Path, GitState.Heads> seen = new HashMap<>();
    /** Fehlversuche in Folge je Projekt und der Zeitpunkt, vor dem nicht erneut versucht wird (Backoff). */
    private final Map<Path, Integer> failures = new HashMap<>();
    private final Map<Path, Instant> retryAt = new HashMap<>();
    /** Wartezeit nach dem ersten Fehlschlag; verdoppelt sich bis {@link #MAX_BACKOFF}. Für Tests änderbar. */
    Duration retryBase = Duration.ofMinutes(1);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(30);
    private volatile Thread worker;

    @Autowired
    public GraphAutoIndexer(ObjectProvider<ToolRegistry> registry, GraphModule module,
                            @Value("${devtools.graph.watch-interval:5s}") Duration interval) {
        this(() -> {
            ToolRegistry r = registry.getIfAvailable();
            if (r == null || !r.hasModule(module.id()) || !r.settings(module.id()).enabled()) {
                return null;
            }
            return r.config(module.id());
        }, module::service, interval);
    }

    /**
     * @param config Konfiguration des Moduls oder {@code null}, wenn es aus ist
     */
    GraphAutoIndexer(Supplier<ModuleConfig> config, Function<ModuleConfig, GraphService> services, Duration interval) {
        this.config = config;
        this.services = services;
        this.interval = interval;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        worker = Thread.ofVirtual().name("graph-auto-index").start(this::loop);
    }

    @PreDestroy
    public void stop() {
        Thread w = worker;
        worker = null;
        if (w != null) {
            w.interrupt();
        }
    }

    private void loop() {
        while (worker == Thread.currentThread()) {
            try {
                poll();
                Thread.sleep(interval);
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                LOG.warn("Automatisches Indizieren: {}", Errors.rootMessage(e));
            }
        }
    }

    /** Ein Durchgang: Änderungen erkennen und die betroffenen Projekte indizieren. Liefert die Meldungen. */
    synchronized List<String> poll() {
        ModuleConfig cfg = config.get();
        if (cfg == null || !cfg.getBoolean(GraphModule.AUTO_INDEX)) {
            seen.clear(); // nach dem Wiedereinschalten neu beginnen statt alles Verpasste zu bauen
            failures.clear();
            retryAt.clear();
            return List.of();
        }
        GraphService service = services.apply(cfg);
        Set<Path> roots = new LinkedHashSet<>();
        service.projects().all().values().forEach(p -> roots.add(p.toAbsolutePath().normalize()));
        seen.keySet().retainAll(roots);
        failures.keySet().retainAll(roots);
        retryAt.keySet().retainAll(roots);
        List<String> out = new ArrayList<>();
        for (Path root : roots) {
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            GitState.Heads now = GitState.Heads.of(root);
            if (now == null) {
                continue; // ohne Git nichts zu beobachten
            }
            Instant retry = retryAt.get(root);
            if (retry != null && Instant.now().isBefore(retry)) {
                continue; // nach einem Fehlschlag: erst nach der Wartezeit erneut versuchen
            }
            GitState.Heads before = seen.put(root, now);
            if (before == null) {
                continue; // beim ersten Mal nur merken
            }
            try {
                if (!Objects.equals(now.head(), before.head()) || !Objects.equals(now.branch(), before.branch())) {
                    GraphService.BuildResult r = service.build(root.toString(), null, false,
                            ModuleAction.Progress.NONE);
                    out.add(describe(r));
                }
                for (Map.Entry<String, String> b : now.branches().entrySet()) {
                    if (!before.branches().containsKey(b.getKey()) && !b.getKey().equals(now.branch())
                            && b.getValue().equals(now.head())) {
                        GraphService.BuildResult r = service.adopt(root.toString(), b.getKey());
                        if (r != null) {
                            out.add(describe(r));
                        }
                    }
                }
                failures.remove(root);
                retryAt.remove(root);
            } catch (RuntimeException e) {
                // Der neue Stand gilt nicht als gesehen: Sonst bliebe der Graph bis zum nächsten Commit still veraltet.
                seen.put(root, before);
                if (e.getCause() instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    break;
                }
                int n = failures.merge(root, 1, Integer::sum);
                Duration wait = retryBase.multipliedBy(1L << Math.min(n - 1, 20));
                retryAt.put(root, Instant.now().plus(wait.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : wait));
                if (n == 1) { // bei bleibenden Fehlern nicht bei jedem Versuch warnen
                    out.add(root + ": " + Errors.rootMessage(e));
                    LOG.warn("Code-Graph {} nicht automatisch indiziert (neuer Versuch später): {}", root,
                            Errors.rootMessage(e));
                } else {
                    LOG.debug("Code-Graph {} weiterhin nicht automatisch indiziert (Versuch {}): {}", root, n,
                            Errors.rootMessage(e));
                }
            }
        }
        out.forEach(m -> LOG.info("Code-Graph automatisch: {}", m));
        return out;
    }

    private static String describe(GraphService.BuildResult r) {
        String what = !r.rebuilt() ? "aktuell" : r.mode() != null ? r.mode() : "komplett gebaut";
        return r.key().project() + " (Branch " + r.key().branchLabel() + "): " + what + ", "
                + r.duration().toMillis() + " ms";
    }
}
