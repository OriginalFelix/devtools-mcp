package systems.grebe.devtools.mcp.modules.graph;

import java.lang.ref.SoftReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import systems.grebe.devtools.mcp.core.GitWorktrees;
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

    /**
     * Projektwurzel, absolut – daraus wird gebaut. Ein Worktree eines Projekts ({@code <projekt>/<ordner>} oder ein
     * Pfad darin) ist sein eigenes Arbeitsverzeichnis mit eigenem Branch, nicht das Haupt-Repository, in dem er oft
     * liegt.
     */
    Path resolve(String project) {
        GitWorktrees.Worktree w = GitWorktrees.find(worktrees(), project);
        if (w != null) {
            return w.dir();
        }
        return projects.resolve(project, defaultProject).toAbsolutePath().normalize();
    }

    /** Verknüpfte Worktrees der Projekte ({@code git worktree add}). */
    List<GitWorktrees.Worktree> worktrees() {
        return GitWorktrees.of(projects.all());
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
        Path main = GitWorktrees.main(worktrees(), root);
        GraphProjects.Identity id = identity(root);
        if (id == null && !main.equals(root)) {
            id = identity(main); // Worktree: Graph des Projekts, nur auf dem Branch des Worktrees
        }
        String name = id != null ? id.name() : configuredName(root).or(() -> configuredName(main))
                .orElseGet(() -> name(main));
        return new Key(name, root.toString(), branch, id == null ? null : id.projectId());
    }

    private GraphProjects.Identity identity(Path root) {
        return identities == null ? null : identities.identify(root);
    }

    private Optional<String> configuredName(Path root) {
        return projects.all().entrySet().stream().filter(e -> e.getValue().toAbsolutePath().normalize().equals(root))
                .map(Map.Entry::getKey).findFirst();
    }

    /**
     * Ergebnis eines Aufbaus.
     *
     * @param mode wie gespeichert wurde, für Meldungen: {@code null} = komplett, sonst z.B. „inkrementell: 2 Dateien
     *             gelesen, 37 Änderungen gespeichert“ oder „übernommen von Branch main (gleicher Stand)“
     */
    record BuildResult(Key key, GraphReader graph, boolean rebuilt, int changedFiles, int addedFiles,
                       int removedFiles, Duration duration, List<String> removedBranches, String mode) {
    }

    /**
     * Zwischenstand eines Projekts über Aufbauten hinweg (im Speicher der App): Deklarationen und Kanten je Datei
     * ({@link GraphBuilder.ParseCache}) und der zuletzt gespeicherte Graph samt Generation – daraus wird beim nächsten
     * Aufbau nur der Unterschied gespeichert.
     */
    static final class Session {
        final GraphBuilder.ParseCache parse = new GraphBuilder.ParseCache();
        Key key;
        String storage;
        String generation;
        GraphFile last;
    }

    /**
     * Zwischenstände der zuletzt gebauten Projekte (höchstens {@value #MAX_SESSIONS}). Weich referenziert: wird der
     * Speicher knapp, räumt der GC sie ab – der nächste Aufbau liest dann alles neu und schreibt komplett (gleiches
     * Ergebnis, nur langsamer).
     */
    private static final int MAX_SESSIONS = 4;
    private static final Map<Path, SoftReference<Session>> SESSIONS = new LinkedHashMap<>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Path, SoftReference<Session>> eldest) {
            return size() > MAX_SESSIONS;
        }
    };

    private static Session session(Path root) {
        synchronized (SESSIONS) {
            SoftReference<Session> ref = SESSIONS.get(root);
            Session s = ref == null ? null : ref.get();
            if (s == null) {
                s = new Session();
                SESSIONS.put(root, new SoftReference<>(s));
            }
            return s;
        }
    }

    /** Für Tests: Zwischenstände verwerfen (wie nach einem Neustart der App). */
    static void forgetSessions() {
        synchronized (SESSIONS) {
            SESSIONS.clear();
        }
    }

    BuildResult build(String project, boolean force) {
        return build(project, null, force, ModuleAction.Progress.NONE);
    }

    /**
     * Baut den Graphen des ausgecheckten Branches, wenn sich eine Quelldatei geändert hat (SHA-256), Dateien
     * hinzugekommen/entfallen sind oder {@code force} gesetzt ist; sonst wird der gespeicherte geliefert. Ein
     * Thread-Interrupt bricht ab.
     *
     * <p>Inkrementell: Hat ein anderer Branch des Projekts genau diesen Stand gespeichert (z.B. ein eben angelegter
     * Branch), wird dessen Graph übernommen. Sonst werden nur geänderte Dateien neu gelesen ({@link Session}), und
     * gespeichert wird der Unterschied zum zuletzt gespeicherten Stand ({@link GraphProvider#update}) – bei großen
     * Änderungen, nach einem Neustart der App oder mit {@code force} der ganze Graph.
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
        // je Projekt, nicht je Branch: der Zwischenstand gilt fürs Arbeitsverzeichnis
        ReentrantLock lock = LOCKS.computeIfAbsent(root.toString(), k -> new ReentrantLock());
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
                                gone, null);
                    }
                }
            } else if (!force) {
                BuildResult linked = linkSameState(store, key, sources, git, start);
                if (linked != null) {
                    return linked;
                }
            }
            // nur lesend freigegeben: vorhandenen Graphen nutzen, nicht neu bauen (Worktree: Freigabe des Projekts)
            Workspaces.requireWritable(GitWorktrees.main(worktrees(), root));
            Session session = session(root);
            if (force) {
                session.last = null;
                session.parse.clear(); // komplett neu lesen, den Cache dabei neu füllen
            }
            GraphFile data = builder.build(sources, key.project(), progress, session.parse)
                    .withBranch(current, git == null ? null : git.commit());
            int parsed = session.parse.parsedDeclarations;
            sources.clear();
            progress.update("Speichere (" + store.describe() + ") …", 0.95);
            GraphReader reader = null;
            String mode = null;
            GraphDelta delta = incrementalDelta(store, key, session, data);
            if (delta != null) {
                reader = store.update(key, session.generation, delta);
                if (reader != null) {
                    mode = "inkrementell: " + parsed + " Datei(en) gelesen, " + delta.size()
                            + " Änderung(en) gespeichert";
                }
            }
            if (reader == null) {
                reader = store.write(key, data);
                if (parsed < data.files().size()) {
                    mode = "inkrementell gelesen: " + parsed + " von " + data.files().size() + " Dateien, komplett "
                            + "gespeichert";
                }
            }
            session.key = key;
            session.storage = store.describe();
            session.generation = reader.generation();
            session.last = data;
            List<String> gone = cleanup(store, key, git);
            progress.update("Fertig", 1);
            return new BuildResult(key, reader, true, changed, added, removed, Duration.ofNanos(System.nanoTime() - start),
                    gone, mode);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Ein eben angelegter, nicht ausgecheckter Branch, der auf dem ausgecheckten Commit steht: übernimmt den Graphen des
     * ausgecheckten Branches (vorher auf den aktuellen Stand gebracht), wenn das Arbeitsverzeichnis keine Änderungen an
     * Java-Dateien hat – sonst gehörten sie nicht zu seinem Commit. Gebaut wird er dann beim Auschecken.
     *
     * @return Ergebnis oder {@code null}, wenn nichts übernommen wurde
     */
    BuildResult adopt(String project, String newBranch) {
        BuildResult current = build(project, null, false, ModuleAction.Progress.NONE);
        Path root = resolve(project);
        if (current.key().branch() == null || current.key().branch().equals(newBranch) || !GitState.javaClean(root)) {
            return null;
        }
        Key target = key(root, newBranch);
        GraphReader reader = storage().link(target, current.key());
        return reader == null ? null : new BuildResult(target, reader, true, 0, 0, 0, Duration.ZERO, List.of(),
                "übernommen von Branch " + current.key().branchLabel() + " (gleicher Stand, nichts gebaut)");
    }

    /**
     * Unterschied zum zuletzt gespeicherten Stand, wenn er sich lohnt: derselbe Branch, dieselbe Ablage, dort gilt noch
     * die zuletzt geschriebene Generation, und es ändert sich höchstens ein Viertel des Graphen.
     */
    private static GraphDelta incrementalDelta(GraphProvider store, Key key, Session session, GraphFile data) {
        if (session.last == null || session.generation == null || !key.equals(session.key)
                || !store.describe().equals(session.storage)) {
            return null;
        }
        GraphReader stored = store.reader(key);
        if (stored == null || !session.generation.equals(stored.generation())) {
            return null; // inzwischen anderswo gebaut (z.B. anderer Rechner) – neu schreiben
        }
        GraphDelta delta = GraphDelta.between(session.last, data);
        int limit = Math.max(2_000, (data.nodes().size() + data.edges().size()) / 4);
        return delta.size() <= limit ? delta : null;
    }

    /**
     * Noch kein Graph für den Branch: Hat ein anderer Branch des Projekts genau diese Dateien gespeichert (eben
     * angelegter Branch, zurück auf einen alten Stand), wird dessen Graph übernommen – ohne Aufbau.
     */
    private BuildResult linkSameState(GraphProvider store, Key key, List<GraphBuilder.Source> sources, GitState git,
                                      long start) {
        Map<String, String> now = new HashMap<>();
        sources.forEach(s -> now.put(s.path(), s.sha256()));
        for (Stored s : store.branches(key)) {
            if (Objects.equals(s.branch(), key.branch()) || s.files() != now.size()) {
                continue;
            }
            Key other = key.withBranch(s.branch());
            GraphProvider.State st = safeState(store, other);
            if (st == null || !GraphBuilder.GENERATOR.equals(st.generator()) || !now.equals(st.fileHashes())) {
                continue;
            }
            GraphReader reader = store.link(key, other);
            if (reader != null) {
                return new BuildResult(key, reader, true, 0, 0, 0, Duration.ofNanos(System.nanoTime() - start),
                        cleanup(store, key, git), "übernommen von Branch " + (s.branch() == null ? "(ohne Git)"
                        : s.branch()) + " (gleicher Stand, nichts gebaut)");
            }
        }
        return null;
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
     * Ein Worktree wurde entfernt ({@code git worktree remove}): Der Graph seines Branches wird gelöscht, wenn er hier
     * gebaut wurde und der Branch weder im Haupt-Repository noch in einem anderen Worktree ausgecheckt ist – der Branch
     * selbst bleibt dabei meist bestehen, die Aufräumregel für gelöschte Branches greift also nicht. Die Datei-Ablage
     * liegt im Worktree und ist mit ihm verschwunden.
     *
     * @param main   Haupt-Repository des Worktrees
     * @param branch zuletzt im Worktree ausgecheckter Branch; {@code null} = nichts zu tun
     * @return gelöschte Branch-Graphen
     */
    List<String> removeWorktreeGraph(Path main, String branch) {
        if (branch == null) {
            return List.of();
        }
        GraphProvider store = storage();
        if (store instanceof FileGraphProvider) {
            return List.of();
        }
        Set<String> checkedOut = new HashSet<>();
        GitState.Heads heads = GitState.Heads.of(main);
        if (heads != null) {
            checkedOut.add(heads.branch());
        }
        for (GitWorktrees.Worktree w : worktrees()) {
            GitState.Heads h = w.main().equals(main) ? GitState.Heads.of(w.dir()) : null;
            if (h != null) {
                checkedOut.add(h.branch());
            }
        }
        if (checkedOut.contains(branch)) {
            return List.of();
        }
        Key key = key(main, branch);
        boolean builtHere = store.branches(key).stream().anyMatch(s -> branch.equals(s.branch())
                && (s.builtBy() == null || s.builtBy().equals(GraphProvider.localBuilder())));
        return builtHere && store.delete(key) ? List.of(branch) : List.of();
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
