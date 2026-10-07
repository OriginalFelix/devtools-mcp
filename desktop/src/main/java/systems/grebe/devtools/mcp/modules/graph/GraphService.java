package systems.grebe.devtools.mcp.modules.graph;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import systems.grebe.devtools.mcp.core.ModuleAction;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.Workspaces;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.GraphFile;
import systems.grebe.devtools.mcp.modules.graph.GraphProvider.Key;
import systems.grebe.devtools.mcp.modules.graph.GraphProvider.Stored;

/**
 * Projektauflösung, Branch-Ermittlung, Aufbau (mit Änderungserkennung per SHA-256) und Zugriff auf die Graphen – in
 * der Graph-Storage des Backends ({@link GraphProvider}, im Local-Mode direkt, sonst über GraphQL) oder als Datei im
 * Projekt ({@link FileGraphProvider}).
 *
 * <p>Ein Graph gehört zu Projekt + Branch: gebaut wird immer der ausgecheckte Branch (aus dem Arbeitsverzeichnis),
 * gelesen werden kann jeder gespeicherte. Nach jedem Aufbau werden Graphen von Branches gelöscht, die es in Git
 * (lokal oder auf einem Remote) nicht mehr gibt.
 */
final class GraphService {

    private static final Map<String, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    private final Workspaces projects;
    private final String defaultProject;
    private final List<String> excludes;
    private final boolean includeTests;
    private final int maxFiles;
    private final ModuleConfig config;
    private final Supplier<GraphProvider> database;
    private final GraphProjects identities;
    private GraphProvider storage;

    /** Nur mit Datei-Ablage (Tests). */
    GraphService(ModuleConfig config) {
        this(config, null);
    }

    GraphService(ModuleConfig config, Supplier<GraphProvider> database) {
        this(config, database, null);
    }

    /**
     * @param database   Graph-Storage des Backends; erst beim ersten Zugriff abgefragt
     * @param identities Projektname und Backend-Projekt je Verzeichnis; {@code null} = Name aus den Einstellungen
     */
    GraphService(ModuleConfig config, Supplier<GraphProvider> database, GraphProjects identities) {
        this.config = config;
        this.database = database;
        this.identities = identities;
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

    /** Graph-Storage des Backends statt Datei – alles außer {@code file} (auch ältere Einstellungen wie {@code neo4j}). */
    static boolean usesDatabase(ModuleConfig config) {
        return !GraphModule.STORAGE_FILE.equals(config.getString(GraphModule.STORAGE, GraphModule.STORAGE_DATABASE));
    }

    /** Ablage laut Konfiguration; die Datenbank wird erst beim ersten Zugriff geöffnet. */
    synchronized GraphProvider storage() {
        if (storage == null) {
            GraphProvider db = usesDatabase(config) && database != null ? database.get() : null;
            if (usesDatabase(config) && db == null) {
                throw new IllegalStateException("Keine Graph-Storage verfügbar (Backend nicht verbunden) – in den "
                        + "Einstellungen des Moduls „Code-Graph“ als Ablage 'file' wählen oder anmelden.");
            }
            storage = db != null ? db : new FileGraphProvider();
        }
        return storage;
    }

    Workspaces projects() {
        return projects;
    }

    /** Projektwurzel, absolut – identifiziert das Projekt in der Ablage. */
    Path resolve(String project) {
        return projects.resolve(project, defaultProject).toAbsolutePath().normalize();
    }

    static String name(Path root) {
        return root.getFileName() == null ? root.toString() : root.getFileName().toString();
    }

    /**
     * Schlüssel für Projekt + Branch.
     *
     * @param branch gewünschter Branch; leer = ausgecheckter Branch (ohne Git: kein Branch)
     */
    Key key(String project, String branch) {
        Path root = resolve(project);
        if (branch != null && !branch.isBlank()) {
            return key(root, branch.strip());
        }
        GitState git = GitState.of(root);
        return key(root, git == null ? null : git.branch());
    }

    /**
     * Schlüssel für ein Projektverzeichnis: Name und Backend-Projekt wie im {@code ProjectProvider} – in der
     * Datenbank ist das derselbe Graph für alle mit diesem Projekt und Branch, egal unter welchem Pfad.
     */
    Key key(Path root, String branch) {
        GraphProjects.Identity id = identities == null ? null : identities.identify(root);
        String name = id != null ? id.name() : projects.all().entrySet().stream()
                .filter(e -> e.getValue().toAbsolutePath().normalize().equals(root)).map(Map.Entry::getKey)
                .findFirst().orElseGet(() -> name(root));
        return new Key(name, root.toString(), branch, id == null ? null : id.projectId());
    }

    record BuildResult(Key key, GraphReader graph, boolean rebuilt, int changedFiles, int addedFiles,
                       int removedFiles, Duration duration, List<String> removedBranches) {
    }

    BuildResult build(String project, boolean force) {
        return build(project, null, force, ModuleAction.Progress.NONE);
    }

    /**
     * Baut den Graphen des ausgecheckten Branches, wenn sich eine Quelldatei geändert hat (SHA-256), Dateien
     * hinzugekommen/entfallen sind oder {@code force} gesetzt ist; sonst wird der gespeicherte geliefert. Ein
     * Thread-Interrupt bricht ab.
     *
     * @param branch nur zur Kontrolle: ist er angegeben, muss er ausgecheckt sein
     */
    BuildResult build(String project, String branch, boolean force, ModuleAction.Progress progress) {
        Path root = resolve(project);
        GitState git = GitState.of(root);
        String current = git == null ? null : git.branch();
        if (branch != null && !branch.isBlank() && !branch.strip().equals(current)) {
            throw new IllegalArgumentException("Gebaut wird aus dem Arbeitsverzeichnis, dort ist "
                    + (current == null ? "kein Git-Branch" : "Branch '" + current + "'") + " ausgecheckt, nicht '"
                    + branch.strip() + "'. Branch auschecken oder ohne branch bauen.");
        }
        Key key = key(root, current);
        GraphProvider store = storage();
        store.check(); // Verbindung/Anmeldung prüfen, bevor minutenlang geparst wird
        ReentrantLock lock = LOCKS.computeIfAbsent(root + "@" + current, k -> new ReentrantLock());
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
            GraphProvider.State existing = force ? null : safeState(store, key);
            int changed = 0;
            int added = 0;
            int removed = 0;
            if (existing != null) {
                Map<String, String> old = existing.fileHashes();
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
                if (changed + added + removed == 0 && GraphBuilder.GENERATOR.equals(existing.generator())) {
                    GraphReader reader = store.reader(key);
                    if (reader != null) {
                        List<String> gone = cleanup(store, key, git);
                        return new BuildResult(key, reader, false, 0, 0, 0, Duration.ofNanos(System.nanoTime() - start),
                                gone);
                    }
                }
            }
            Workspaces.requireWritable(root); // nur lesend freigegeben: vorhandenen Graphen nutzen, nicht neu bauen
            GraphFile data = builder.build(sources, key.project(), progress)
                    .withBranch(current, git == null ? null : git.commit());
            sources.clear();
            progress.update("Speichere (" + store.describe() + ") …", 0.95);
            GraphReader reader = store.write(key, data);
            List<String> gone = cleanup(store, key, git);
            progress.update("Fertig", 1);
            return new BuildResult(key, reader, true, changed, added, removed, Duration.ofNanos(System.nanoTime() - start),
                    gone);
        } finally {
            lock.unlock();
        }
    }

    private static GraphProvider.State safeState(GraphProvider store, Key key) {
        try {
            return store.state(key);
        } catch (RuntimeException e) {
            if (store instanceof FileGraphProvider) {
                return null; // beschädigte Datei -> neu bauen
            }
            throw e; // Datenbank nicht erreichbar: melden statt stundenlang umsonst zu bauen
        }
    }

    /**
     * Löscht gespeicherte Graphen von Branches, die es in Git nicht mehr gibt – nur die hier gebauten: einen Branch,
     * den jemand anders nur lokal hat, kennt das eigene Git nicht.
     */
    private static List<String> cleanup(GraphProvider store, Key key, GitState git) {
        if (git == null) {
            return List.of();
        }
        List<String> gone = new ArrayList<>();
        for (Stored s : store.branches(key)) {
            boolean builtHere = s.builtBy() == null || s.builtBy().equals(GraphProvider.localBuilder());
            if (s.branch() != null && builtHere && !git.exists(s.branch()) && store.delete(key.withBranch(s.branch()))) {
                gone.add(s.branch());
            }
        }
        return gone;
    }

    /**
     * Gespeicherter Graph für Projekt + Branch. Fehlt der Graph des ausgecheckten Branches, wird er jetzt gebaut; ein
     * anderer Branch muss bereits gespeichert sein.
     */
    GraphReader graph(String project, String branch) {
        return open(project, branch).reader();
    }

    /** Graph samt Aufbau-Ergebnis, falls er eben erst gebaut wurde. */
    record Opened(GraphReader reader, BuildResult built) {

        /** Hinweis für die Tool-Ausgabe, wenn der Graph eben gebaut wurde; sonst leer. */
        String note() {
            if (built == null) {
                return "";
            }
            return "Noch kein Graph für " + built.key().project() + " (Branch " + built.key().branchLabel()
                    + ") – eben gebaut in " + built.duration().toMillis() + " ms, " + reader.info().stat("files")
                    + " Dateien.\n\n";
        }
    }

    /** Wie {@link #graph(String, String)}, meldet aber, ob der Graph dafür gebaut wurde. */
    Opened open(String project, String branch) {
        Key key = key(project, branch);
        GraphReader reader = storage().reader(key);
        if (reader != null) {
            return new Opened(reader, null);
        }
        GitState git = GitState.of(key.path());
        String current = git == null ? null : git.branch();
        if (key.branch() == null || key.branch().equals(current)) {
            BuildResult r = build(project, null, false, ModuleAction.Progress.NONE);
            // parallel gebaut oder unverändert gespeichert: kein Hinweis nötig
            return new Opened(r.graph(), r.rebuilt() ? r : null);
        }
        List<String> stored = storage().branches(key).stream().map(s -> s.branch() == null ? "(ohne Git)"
                : s.branch()).toList();
        throw new IllegalArgumentException("Für Branch '" + key.branch() + "' von " + key.project()
                + " ist kein Graph gespeichert. Gespeichert: " + (stored.isEmpty() ? "keine" : stored)
                + ". Ausgecheckt ist " + (current == null ? "kein Branch" : "'" + current + "'")
                + " – nur der ausgecheckte Branch kann gebaut werden (graph_build).");
    }

    GraphReader graph(String project) {
        return graph(project, null);
    }
}
