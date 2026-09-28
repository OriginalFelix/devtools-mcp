package systems.grebe.devtools.mcp.modules.graph;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import systems.grebe.devtools.mcp.core.ModuleAction;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.Workspaces;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.FileEntry;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.GraphFile;

/** Projektauflösung, Aufbau (mit Änderungserkennung per SHA-256) und Laden der Graphen. */
final class GraphService {

    private static final Map<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    private final Workspaces projects;
    private final String defaultProject;
    private final List<String> excludes;
    private final boolean includeTests;
    private final int maxFiles;

    GraphService(ModuleConfig config) {
        this.projects = new Workspaces(config.getList(GraphModule.PROJECTS), GraphService::isJavaProject, "Graph-Projekte");
        this.defaultProject = config.getString(GraphModule.DEFAULT_PROJECT, null);
        this.excludes = config.getList(GraphModule.EXCLUDES);
        this.includeTests = config.getBoolean(GraphModule.INCLUDE_TESTS);
        this.maxFiles = Math.max(1, config.getInt(GraphModule.MAX_FILES, 30000));
    }

    /** Ein Projekt ist ein Verzeichnis mit Build-Datei, Git-Repository oder {@code src}-Ordner. */
    static boolean isJavaProject(Path dir) {
        for (String marker : List.of("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
                "pom.xml", ".git", "src", GraphStore.FILE_NAME)) {
            if (Files.exists(dir.resolve(marker))) {
                return true;
            }
        }
        return false;
    }

    Workspaces projects() {
        return projects;
    }

    Path resolve(String project) {
        return projects.resolve(project, defaultProject);
    }

    record BuildResult(Path root, CodeGraph graph, boolean rebuilt, int changedFiles, int addedFiles,
                       int removedFiles, Duration duration) {
    }

    /**
     * Baut den Graphen, wenn sich eine Quelldatei geändert hat (SHA-256), Dateien hinzugekommen/entfallen sind oder
     * {@code force} gesetzt ist; sonst wird die vorhandene Datei geliefert.
     */
    BuildResult build(String project, boolean force) {
        return build(project, force, ModuleAction.Progress.NONE);
    }

    /** Wie {@link #build(String, boolean)}, mit Fortschritt; ein Thread-Interrupt bricht ab. */
    BuildResult build(String project, boolean force, ModuleAction.Progress progress) {
        Path root = resolve(project);
        ReentrantLock lock = LOCKS.computeIfAbsent(root, k -> new ReentrantLock());
        if (lock.isLocked()) {
            progress.update("Warte auf laufenden Aufbau …", -1);
        }
        try {
            lock.lockInterruptibly(); // parallele Aufrufe warten auf denselben Aufbau, statt ihn doppelt zu starten
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Graph-Aufbau abgebrochen", e);
        }
        try {
            long start = System.nanoTime();
            GraphBuilder builder = new GraphBuilder(root, excludes, includeTests, maxFiles);
            progress.update("Suche Java-Dateien …", -1);
            List<Path> paths = builder.scan();
            List<GraphBuilder.Source> sources = new ArrayList<>(paths.size());
            for (int i = 0; i < paths.size(); i++) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IllegalStateException("Graph-Aufbau abgebrochen", new InterruptedException());
                }
                sources.add(builder.read(paths.get(i)));
                if ((i + 1) % Math.max(1, paths.size() / 50) == 0 || i + 1 == paths.size()) {
                    progress.update("Prüfsummen " + (i + 1) + "/" + paths.size(), 0.05 * (i + 1) / paths.size());
                }
            }
            CodeGraph existing = force ? null : safeLoad(root);
            int changed = 0;
            int added = 0;
            int removed = 0;
            if (existing != null) {
                Map<String, String> old = new HashMap<>();
                for (FileEntry f : existing.data().files()) {
                    old.put(f.path(), f.sha256());
                }
                Set<String> now = new HashSet<>();
                for (GraphBuilder.Source s : sources) {
                    now.add(s.path());
                    String sha = old.get(s.path());
                    if (sha == null) {
                        added++;
                    } else if (!sha.equals(s.sha256())) {
                        changed++;
                    }
                }
                for (String p : old.keySet()) {
                    if (!now.contains(p)) {
                        removed++;
                    }
                }
                if (changed + added + removed == 0 && GraphBuilder.GENERATOR.equals(existing.data().generator())) {
                    return new BuildResult(root, existing, false, 0, 0, 0, Duration.ofNanos(System.nanoTime() - start));
                }
            }
            String name = root.getFileName() == null ? root.toString() : root.getFileName().toString();
            GraphFile data = builder.build(sources, name, progress);
            progress.update("Schreibe " + GraphStore.FILE_NAME + " …", 0.95);
            CodeGraph graph = GraphStore.write(root, data);
            progress.update("Fertig", 1);
            return new BuildResult(root, graph, true, changed, added, removed, Duration.ofNanos(System.nanoTime() - start));
        } finally {
            lock.unlock();
        }
    }

    private static CodeGraph safeLoad(Path root) {
        try {
            return GraphStore.load(root);
        } catch (RuntimeException e) {
            return null; // beschädigt/fremd -> neu bauen
        }
    }

    /** Vorhandenen Graphen laden; fehlt die Datei, wird sie jetzt gebaut. */
    CodeGraph graph(String project) {
        Path root = resolve(project);
        CodeGraph g = GraphStore.load(root);
        return g != null ? g : build(project, false).graph();
    }
}
