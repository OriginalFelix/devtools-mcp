package systems.grebe.devtools.mcp.modules.git;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.jgit.api.CheckoutCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.NotMergedException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.Text;
import systems.grebe.devtools.mcp.core.Workspaces;

/**
 * Verwerfen und Löschen (Schalter {@code allowDiscard}) – nicht rückgängig zu machen, daher eigener Schalter und nur
 * auf ausdrücklichen Auftrag. Gelöschte Branches und Commits bleiben eine Weile über git_reflog auffindbar,
 * verworfene Änderungen im Arbeitsverzeichnis nicht.
 */
public class GitDiscardTools {

    private static final String REPO_PARAM = "Repository-Name (Ordnername) oder Pfad; leer = Standard-Repository";

    private final GitSupport git;

    GitDiscardTools(GitSupport git) {
        this.git = git;
    }

    @Tool(name = "restore", description = "Verwirft lokale Änderungen (gestaged und nicht gestaged) an Dateien und stellt "
            + "den Stand aus HEAD bzw. einer Revision wieder her; optional unversionierte Dateien löschen. ENDGÜLTIG – "
            + "nur auf ausdrücklichen Auftrag. Statt `git restore`/`git checkout -- <pfad>`/`git clean` in der Shell "
            + "verwenden." + ShellHints.GIT)
    public String restore(
            @ToolParam(required = false, description = REPO_PARAM) String repository,
            @ToolParam(required = false, description = "Relative Pfade (Dateien/Verzeichnisse)") List<String> paths,
            @ToolParam(required = false, description = "true = alle Änderungen im Repository verwerfen (statt 'paths')") Boolean all,
            @ToolParam(required = false, description = "Quelle; Standard HEAD") String source,
            @ToolParam(required = false, description = "true = auch unversionierte Dateien in diesen Pfaden löschen") Boolean includeUntracked) {
        if (!Boolean.TRUE.equals(all) && (paths == null || paths.isEmpty())) {
            throw new IllegalArgumentException("Pfade fehlen ('paths') – oder all=true für alle Änderungen.");
        }
        return git.withWrite(repository, (g, root) -> {
            Repository repo = g.getRepository();
            List<String> rels = Boolean.TRUE.equals(all) ? List.of(".")
                    : paths.stream().map(p -> Workspaces.relativePath(root, p)).toList();
            boolean everything = rels.contains(".");
            String start = source == null || source.isBlank() ? Constants.HEAD : source.trim();
            GitSupport.resolveRev(repo, start);
            Status before = g.status().call();
            Set<String> affected = new TreeSet<>(before.getUncommittedChanges().stream().filter(f -> under(f, rels)).toList());
            if (!affected.isEmpty() || !Constants.HEAD.equals(start)) {
                CheckoutCommand co = g.checkout().setStartPoint(start);
                if (everything) {
                    co.setAllPaths(true);
                } else {
                    rels.forEach(co::addPath);
                }
                co.call();
            }
            // neu gestagte Dateien, die es in HEAD nicht gibt, aus dem Index nehmen (sie bleiben als unversioniert liegen)
            List<String> added = before.getAdded().stream().filter(f -> under(f, rels)).toList();
            if (!added.isEmpty() && Constants.HEAD.equals(start)) {
                var reset = g.reset();
                added.forEach(reset::addPath);
                reset.call();
            }
            Set<String> removed = Set.of();
            if (Boolean.TRUE.equals(includeUntracked)) {
                Set<String> untracked = new TreeSet<>(g.status().call().getUntracked().stream()
                        .filter(f -> under(f, rels)).toList());
                if (!untracked.isEmpty()) {
                    removed = g.clean().setPaths(untracked).call();
                }
            }
            StringBuilder sb = new StringBuilder();
            sb.append(affected.isEmpty() ? "Keine geänderten versionierten Dateien." : "Verworfen (" + affected.size()
                    + "):\n  " + String.join("\n  ", affected));
            if (!removed.isEmpty()) {
                sb.append("\nUnversioniert gelöscht (").append(removed.size()).append("):\n  ")
                        .append(String.join("\n  ", new TreeSet<>(removed)));
            }
            if (!Constants.HEAD.equals(start)) {
                sb.append("\nStand aus ").append(start).append(" übernommen (gestaged).");
            }
            return Text.limitLines(sb.toString(), git.maxLines());
        });
    }

    private static boolean under(String file, List<String> rels) {
        return rels.stream().anyMatch(p -> ".".equals(p) || file.equals(p) || file.startsWith(p + "/"));
    }

    @Tool(name = "delete_branch", description = "Löscht einen lokalen Branch. Nicht gemergte Branches nur mit force=true "
            + "(Commits dann nur noch über git_reflog auffindbar). Weder den aktuellen noch geschützte Branches. "
            + "Statt `git branch -d/-D` in der Shell verwenden." + ShellHints.GIT)
    public String deleteBranch(
            @ToolParam(required = false, description = REPO_PARAM) String repository,
            @ToolParam(description = "Branch-Name") String name,
            @ToolParam(required = false, description = "true = auch nicht gemergte Branches löschen") Boolean force) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Branch-Name fehlt ('name').");
        }
        String b = name.trim();
        return git.withWrite(repository, (g, root) -> {
            Repository repo = g.getRepository();
            if (b.equals(repo.getBranch())) {
                throw new IllegalStateException("'" + b + "' ist ausgecheckt – zuerst git_checkout auf einen anderen Branch.");
            }
            if (git.isProtected(b)) {
                throw new IllegalStateException("'" + b + "' ist geschützt (Modul Git → 'Nie pushen auf').");
            }
            var ref = repo.exactRef(Constants.R_HEADS + b);
            if (ref == null) {
                throw new IllegalArgumentException("Lokaler Branch '" + b + "' existiert nicht (git_branches).");
            }
            String id = GitSupport.shortId(ref.getObjectId());
            try {
                g.branchDelete().setBranchNames(Constants.R_HEADS + b).setForce(Boolean.TRUE.equals(force)).call();
            } catch (NotMergedException e) {
                throw new IllegalStateException("'" + b + "' ist nicht in den aktuellen Branch gemergt – Commits gingen "
                        + "verloren. Nur mit force=true auf ausdrücklichen Wunsch löschen.", e);
            }
            return "Branch '" + b + "' gelöscht (war " + id + ").";
        });
    }

    @Tool(name = "delete_tag", description = "Löscht einen lokalen Tag (nicht auf dem Remote)."
            + " Statt `git tag -d` in der Shell verwenden." + ShellHints.GIT)
    public String deleteTag(
            @ToolParam(required = false, description = REPO_PARAM) String repository,
            @ToolParam(description = "Tag-Name") String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Tag-Name fehlt ('name').");
        }
        return git.withWrite(repository, (g, root) -> {
            if (g.getRepository().exactRef(Constants.R_TAGS + name.trim()) == null) {
                throw new IllegalArgumentException("Tag '" + name.trim() + "' existiert nicht (git_tags).");
            }
            g.tagDelete().setTags(name.trim()).call();
            return "Tag '" + name.trim() + "' gelöscht.";
        });
    }

    @Tool(name = "stash_drop", description = "Löscht einen Stash (stash@{n}) oder mit all=true alle. Endgültig."
            + " Statt `git stash drop`/`git stash clear` in der Shell verwenden." + ShellHints.GIT)
    public String stashDrop(
            @ToolParam(required = false, description = REPO_PARAM) String repository,
            @ToolParam(required = false, description = "Index n aus stash@{n} (Standard 0)") Integer index,
            @ToolParam(required = false, description = "true = alle Stashes löschen") Boolean all) {
        return git.withWrite(repository, (g, root) -> {
            int count = g.stashList().call().size();
            if (count == 0) {
                return "Keine Stashes vorhanden.";
            }
            if (Boolean.TRUE.equals(all)) {
                g.stashDrop().setAll(true).call();
                return count + " Stash(es) gelöscht.";
            }
            int n = index == null || index < 0 ? 0 : index;
            if (n >= count) {
                throw new IllegalArgumentException("stash@{" + n + "} existiert nicht – es gibt " + count + " (git_stash_list).");
            }
            g.stashDrop().setStashRef(n).call();
            return "stash@{" + n + "} gelöscht.";
        });
    }
}
