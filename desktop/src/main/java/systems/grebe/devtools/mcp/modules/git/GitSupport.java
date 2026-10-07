package systems.grebe.devtools.mcp.modules.git;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.RebaseResult;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryState;
import org.eclipse.jgit.merge.ResolveMerger;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.Workspaces;

/** Gemeinsame Infrastruktur der Git-Tools. */
final class GitSupport {

    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    /**
     * Verknüpfter Worktree eines freigegebenen Repositories ({@code git worktree add}).
     *
     * @param name {@code <repository>/<ordner>}, so in git_*-Tools anzugeben
     * @param main Arbeitsverzeichnis des Haupt-Repositories – maßgeblich für die Schreibfreigabe
     */
    record Worktree(String name, Path dir, Path main) { }

    private final Workspaces repositories;
    private final String defaultRepository;
    private final int maxLines;
    private final Duration networkTimeout;
    private final List<String> protectedBranches;
    private final boolean allowDiscard;

    GitSupport(ModuleConfig config) {
        this.repositories = new Workspaces(config.getList(GitModule.REPOSITORIES), GitModule::isRepository, "Git-Repositories");
        this.defaultRepository = config.getString(GitModule.DEFAULT_REPOSITORY, null);
        this.maxLines = Math.max(50, config.getInt(GitModule.MAX_LINES, 1500));
        this.networkTimeout = Duration.ofSeconds(Math.max(10, config.getInt(GitModule.NETWORK_TIMEOUT, 120)));
        this.protectedBranches = config.getList(GitModule.PROTECTED_BRANCHES);
        this.allowDiscard = config.getBoolean(GitModule.ALLOW_DISCARD);
    }

    Workspaces repositories() {
        return repositories;
    }

    int maxLines() {
        return maxLines;
    }

    Duration networkTimeout() {
        return networkTimeout;
    }

    /** Branches, auf die git_push nie pusht (Vergleich ohne Groß-/Kleinschreibung). */
    boolean isProtected(String branch) {
        return protectedBranches.stream().anyMatch(b -> b.trim().equalsIgnoreCase(branch));
    }

    /** Ob verwerfende Aktionen (reset --hard, Branch löschen …) erlaubt sind. */
    boolean allowDiscard() {
        return allowDiscard;
    }

    /**
     * Repository-Name, Pfad oder Worktree ({@code <repository>/<ordner>} bzw. ein Pfad darin) auflösen. Worktrees
     * freigegebener Repositories gelten als freigegeben – auch wenn sie innerhalb des Haupt-Repositories liegen.
     */
    Path resolve(String repo) {
        if (repo != null && !repo.isBlank()) {
            Worktree w = worktree(repo.trim());
            if (w != null) {
                return w.dir();
            }
        }
        return repositories.resolve(repo, defaultRepository);
    }

    /** Haupt-Repository eines aufgelösten Verzeichnisses (für Worktrees das Repository, zu dem sie gehören). */
    Path mainRoot(Path root) {
        return worktrees().stream().filter(w -> w.dir().equals(root)).map(Worktree::main).findFirst().orElse(root);
    }

    private Worktree worktree(String wanted) {
        List<Worktree> all = worktrees();
        for (Worktree w : all) {
            if (w.name().equalsIgnoreCase(wanted)) {
                return w;
            }
        }
        Path p;
        try {
            p = Path.of(wanted).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            return null;
        }
        // der speziellste Worktree gewinnt (Worktrees liegen oft im Haupt-Repository, z.B. .claude/worktrees)
        return all.stream().filter(w -> p.startsWith(w.dir()))
                .max(Comparator.comparingInt(w -> w.dir().getNameCount())).orElse(null);
    }

    /** Verknüpfte Worktrees aller freigegebenen Repositories (aus {@code .git/worktrees/*}/gitdir). */
    List<Worktree> worktrees() {
        List<Worktree> out = new ArrayList<>();
        for (Map.Entry<String, Path> e : repositories.all().entrySet()) {
            Path meta = e.getValue().resolve(".git").resolve("worktrees");
            if (!Files.isDirectory(meta)) {
                continue;
            }
            try (Stream<Path> dirs = Files.list(meta)) {
                for (Path d : dirs.sorted().toList()) {
                    Path gitdir = d.resolve("gitdir");
                    if (!Files.isRegularFile(gitdir)) {
                        continue;
                    }
                    Path dotGit = Path.of(Files.readString(gitdir).strip());
                    Path dir = (dotGit.isAbsolute() ? dotGit : d.resolve(dotGit)).normalize().getParent();
                    if (dir != null && Files.isDirectory(dir)) {
                        out.add(new Worktree(e.getKey() + "/" + dir.getFileName(), dir.toAbsolutePath().normalize(),
                                e.getValue()));
                    }
                }
            } catch (IOException | InvalidPathException ex) {
                // nicht lesbar -> ohne Worktrees
            }
        }
        return out;
    }

    @FunctionalInterface
    interface GitAction<T> {
        T apply(Git git, Path root) throws GitAPIException, IOException;
    }

    /** Öffnet das Repository, führt die Aktion aus und übersetzt Fehler in verständliche Meldungen. */
    <T> T with(String repo, GitAction<T> action) {
        Path root = resolve(repo);
        try (Git git = Git.open(root.toFile())) {
            return action.apply(git, root);
        } catch (GitAPIException e) {
            throw new IllegalStateException("Git-Fehler: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new UncheckedIOException("Git-Fehler: " + e.getMessage(), e);
        }
    }

    /** Wie {@link #with}, aber nur, wenn der Benutzer im Repository schreiben darf. */
    <T> T withWrite(String repo, GitAction<T> action) {
        Workspaces.requireWritable(mainRoot(resolve(repo)));
        return with(repo, action);
    }

    static ObjectId resolveRev(Repository repository, String rev) throws IOException {
        String r = rev == null || rev.isBlank() ? "HEAD" : rev.trim();
        ObjectId id = repository.resolve(r);
        if (id == null) {
            throw new IllegalArgumentException("Revision nicht gefunden: " + r);
        }
        return id;
    }

    static RevCommit commit(Repository repository, String rev) throws IOException {
        try (RevWalk walk = new RevWalk(repository)) {
            return walk.parseCommit(resolveRev(repository, rev));
        }
    }

    static AbstractTreeIterator tree(Repository repository, String rev) throws IOException {
        RevCommit c = commit(repository, rev);
        CanonicalTreeParser parser = new CanonicalTreeParser();
        try (ObjectReader reader = repository.newObjectReader()) {
            parser.reset(reader, c.getTree().getId());
        }
        return parser;
    }

    static AbstractTreeIterator parentTree(Repository repository, RevCommit c) throws IOException {
        if (c.getParentCount() == 0) {
            return new EmptyTreeIterator();
        }
        return tree(repository, c.getParent(0).getName());
    }

    /** Formatiert Diff-Einträge als Unified Diff mit Statistik-Kopf. */
    static String formatDiff(Repository repository, List<DiffEntry> entries) throws IOException {
        if (entries.isEmpty()) {
            return "(keine Änderungen)";
        }
        StringBuilder head = new StringBuilder(entries.size() + " Datei(en) geändert:\n");
        for (DiffEntry e : entries) {
            head.append("  ").append(e.getChangeType()).append(' ').append(pathOf(e)).append('\n');
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DiffFormatter fmt = new DiffFormatter(out)) {
            fmt.setRepository(repository);
            fmt.setDetectRenames(true);
            fmt.format(entries);
        }
        return head.append('\n').append(out.toString(StandardCharsets.UTF_8)).toString();
    }

    static String pathOf(DiffEntry e) {
        return switch (e.getChangeType()) {
            case DELETE -> e.getOldPath();
            case RENAME, COPY -> e.getOldPath() + " -> " + e.getNewPath();
            default -> e.getNewPath();
        };
    }

    /** Laufende Operation verständlich (JGit liefert nur „Conflicts“, „Merged“ …). */
    static String describe(RepositoryState state) {
        return switch (state) {
            case SAFE -> "keine laufende Operation";
            case MERGING -> "Merge mit Konflikten";
            case MERGING_RESOLVED -> "Merge (Konflikte gelöst, git_continue)";
            case CHERRY_PICKING -> "Cherry-Pick mit Konflikten";
            case CHERRY_PICKING_RESOLVED -> "Cherry-Pick (Konflikte gelöst, git_continue)";
            case REVERTING -> "Revert mit Konflikten";
            case REVERTING_RESOLVED -> "Revert (Konflikte gelöst, git_continue)";
            case REBASING, REBASING_REBASING, REBASING_MERGE, REBASING_INTERACTIVE -> "Rebase angehalten";
            case APPLY -> "git am/apply angehalten";
            case BISECTING -> "Bisect";
            default -> state.name();
        };
    }

    static String shortId(ObjectId id) {
        return id == null ? "-" : id.abbreviate(8).name();
    }

    // ------------------------------------------------------------------ Integrieren/Konflikte

    static final String CONFLICT_HINT = "\nKonflikte lösen (Dateien bearbeiten), git_stage, dann git_continue – "
            + "oder git_abort für den Ausgangszustand.";

    /** Lehnt ab, solange ein Merge, Rebase, Cherry-Pick oder Revert angehalten ist. */
    static void requireSafe(Repository repo) {
        RepositoryState state = repo.getRepositoryState();
        if (state != RepositoryState.SAFE) {
            throw new IllegalStateException("Es läuft bereits: " + describe(state)
                    + " – zuerst git_continue oder git_abort.");
        }
    }

    static String describe(RebaseResult r, String branch, String onto) throws IOException {
        return switch (r.getStatus()) {
            case OK -> branch + (onto == null ? " fertig rebased." : " auf " + onto + " rebased.");
            case UP_TO_DATE -> branch + " ist bereits auf " + (onto == null ? "der Basis" : onto) + ".";
            case FAST_FORWARD -> branch + " per Fast-Forward auf " + (onto == null ? "die Basis" : onto) + " gebracht.";
            case STOPPED, CONFLICTS -> "Rebase angehalten bei Commit "
                    + (r.getCurrentCommit() == null ? "?" : shortId(r.getCurrentCommit()) + " ("
                    + r.getCurrentCommit().getShortMessage() + ")")
                    + (r.getConflicts() == null || r.getConflicts().isEmpty() ? ""
                    : " – Konflikte in:\n  " + String.join("\n  ", new TreeSet<>(r.getConflicts()))) + CONFLICT_HINT;
            case UNCOMMITTED_CHANGES -> throw new IllegalStateException("Rebase nicht möglich – nicht committete "
                    + "Änderungen: " + (r.getUncommittedChanges() == null ? "" : String.join(", ", r.getUncommittedChanges()))
                    + ". Zuerst committen oder git_stash.");
            case FAILED -> throw new IllegalStateException("Rebase fehlgeschlagen – lokale Änderungen würden "
                    + "überschrieben: " + failing(r.getFailingPaths()) + ". Zuerst committen oder git_stash.");
            case NOTHING_TO_COMMIT -> "Commit ist nach der Konfliktlösung leer – git_abort oder Änderungen stagen.";
            default -> "Rebase: " + r.getStatus();
        };
    }

    static String conflicts(Git g) throws GitAPIException {
        Collection<String> c = g.status().call().getConflicting();
        return c.isEmpty() ? "" : "\nKonflikte in:\n  " + String.join("\n  ", new TreeSet<>(c));
    }

    static String failing(Map<String, ResolveMerger.MergeFailureReason> paths) {
        return paths == null || paths.isEmpty() ? "?" : String.join(", ", new TreeSet<>(paths.keySet()));
    }
}
