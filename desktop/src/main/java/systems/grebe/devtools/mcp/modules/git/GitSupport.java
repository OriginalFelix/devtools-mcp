package systems.grebe.devtools.mcp.modules.git;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
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

    private final Workspaces repositories;
    private final String defaultRepository;
    private final int maxLines;

    GitSupport(ModuleConfig config) {
        this.repositories = new Workspaces(config.getList(GitModule.REPOSITORIES), GitModule::isRepository, "Git-Repositories");
        this.defaultRepository = config.getString(GitModule.DEFAULT_REPOSITORY, null);
        this.maxLines = Math.max(50, config.getInt(GitModule.MAX_LINES, 1500));
    }

    Workspaces repositories() {
        return repositories;
    }

    int maxLines() {
        return maxLines;
    }

    Path resolve(String repo) {
        return repositories.resolve(repo, defaultRepository);
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
        Workspaces.requireWritable(resolve(repo));
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

    static String shortId(ObjectId id) {
        return id == null ? "-" : id.abbreviate(8).name();
    }
}
