package systems.grebe.devtools.mcp.modules.graph;

import java.nio.file.Path;
import java.time.Duration;
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
import systems.grebe.devtools.mcp.core.GitWorktrees;
import systems.grebe.devtools.mcp.core.ModuleAction;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolRegistry;

/**
 * Indiziert die Graph-Projekte automatisch: beobachtet je Projekt und je Worktree eines Projekts den ausgecheckten
 * Branch, seinen Commit und die lokalen Branches (alle paar Sekunden, egal ob der Commit aus DevTools, der IDE oder der
 * Shell kommt) und
 * <ul>
 *   <li>baut bei einem neuen Commit oder Branch-Wechsel den Graphen des ausgecheckten Branches – inkrementell
 *       ({@link GraphService#build}: nur geänderte Dateien lesen, nur den Unterschied speichern; ein Branch mit gleichem
 *       Stand wie ein gespeicherter übernimmt dessen Graphen),</li>
 *   <li>lässt einen neuen, nicht ausgecheckten Branch auf dem ausgecheckten Commit den vorhandenen Graphen übernehmen
 *       ({@link GraphService#adopt}).</li>
 * </ul>
 * Nur bei eingeschaltetem Modul „Code-Graph“ und Einstellung „Automatisch indizieren“. Beim Start wird nur der Stand
 * gemerkt, gebaut wird erst bei einer Änderung.
 *
 * <p>Unabhängig von „Automatisch indizieren“ (nur bei eingeschaltetem Modul): Verschwindet ein Worktree, wird der
 * Graph seines Branches gelöscht ({@link GraphService#removeWorktreeGraph}). Wird ein Worktree entfernt, während die
 * App nicht läuft, bleibt sein Graph bis zum Löschen des Branches.
 */
@Component
public class GraphAutoIndexer {

    private static final Logger LOG = LoggerFactory.getLogger(GraphAutoIndexer.class);

    private final Supplier<ModuleConfig> config;
    private final Function<ModuleConfig, GraphService> services;
    private final Duration interval;
    private final Map<Path, GitState.Heads> seen = new HashMap<>();
    /** Bekannte Worktrees (Verzeichnis) mit Haupt-Repository und zuletzt ausgechecktem Branch. */
    private Map<Path, Tracked> worktrees = new HashMap<>();
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
                LOG.warn("Automatisches Indizieren: {}", GraphModule.rootMessage(e));
            }
        }
    }

    /** Ein Durchgang: Änderungen erkennen und die betroffenen Projekte indizieren. Liefert die Meldungen. */
    synchronized List<String> poll() {
        ModuleConfig cfg = config.get();
        if (cfg == null) {
            seen.clear();
            worktrees.clear();
            return List.of();
        }
        GraphService service = services.apply(cfg);
        List<GitWorktrees.Worktree> currentWorktrees = service.worktrees();
        List<String> out = new ArrayList<>(removedWorktrees(service, currentWorktrees));
        if (!cfg.getBoolean(GraphModule.AUTO_INDEX)) {
            seen.clear(); // nach dem Wiedereinschalten neu beginnen statt alles Verpasste zu bauen
            out.forEach(m -> LOG.info("Code-Graph automatisch: {}", m));
            return out;
        }
        Set<Path> roots = new LinkedHashSet<>();
        service.projects().all().values().forEach(p -> roots.add(p.toAbsolutePath().normalize()));
        currentWorktrees.forEach(w -> roots.add(w.dir())); // eigener Branch je Worktree
        seen.keySet().retainAll(roots);
        for (Path root : roots) {
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            GitState.Heads now = GitState.Heads.of(root);
            GitState.Heads before = now == null ? null : seen.put(root, now);
            if (now == null || before == null) {
                continue; // ohne Git nichts zu beobachten; beim ersten Mal nur merken
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
            } catch (RuntimeException e) {
                if (e.getCause() instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    break;
                }
                out.add(root + ": " + GraphModule.rootMessage(e));
                LOG.warn("Code-Graph {} nicht automatisch indiziert: {}", root, GraphModule.rootMessage(e));
            }
        }
        out.forEach(m -> LOG.info("Code-Graph automatisch: {}", m));
        return out;
    }

    private record Tracked(Path main, String branch) {
    }

    /** Worktrees merken; für verschwundene den Graphen ihres Branches löschen. Liefert die Meldungen. */
    private List<String> removedWorktrees(GraphService service, List<GitWorktrees.Worktree> current) {
        Map<Path, Tracked> now = new HashMap<>();
        for (GitWorktrees.Worktree w : current) {
            GitState.Heads h = GitState.Heads.of(w.dir());
            Tracked before = worktrees.get(w.dir());
            String branch = h != null && h.branch() != null ? h.branch() : before == null ? null : before.branch();
            now.put(w.dir(), new Tracked(w.main(), branch)); // losgelöster HEAD: letzten Branch behalten
        }
        List<String> out = new ArrayList<>();
        for (Map.Entry<Path, Tracked> e : worktrees.entrySet()) {
            if (now.containsKey(e.getKey())) {
                continue;
            }
            try {
                for (String b : service.removeWorktreeGraph(e.getValue().main(), e.getValue().branch())) {
                    out.add(e.getValue().main().getFileName() + " (Branch " + b + "): Graph gelöscht, Worktree "
                            + e.getKey() + " entfernt");
                }
            } catch (RuntimeException ex) {
                now.put(e.getKey(), e.getValue()); // z.B. Backend nicht erreichbar: beim nächsten Durchgang erneut
                LOG.warn("Graph des entfernten Worktrees {} nicht gelöscht: {}", e.getKey(),
                        GraphModule.rootMessage(ex));
            }
        }
        worktrees = now;
        return out;
    }

    private static String describe(GraphService.BuildResult r) {
        String what = !r.rebuilt() ? "aktuell" : r.mode() != null ? r.mode() : "komplett gebaut";
        return r.key().project() + " (Branch " + r.key().branchLabel() + "): " + what + ", "
                + r.duration().toMillis() + " ms";
    }
}
