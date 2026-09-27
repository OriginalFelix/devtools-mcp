package systems.grebe.devtools.mcp.modules.git;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.jgit.api.ListBranchCommand;
import org.eclipse.jgit.api.LogCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.blame.BlameResult;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.lib.BranchTrackingStatus;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.Text;
import systems.grebe.devtools.mcp.core.Workspaces;

/** Lesende Git-Tools. */
public class GitReadTools {

    private static final String REPO_PARAM = "Repository-Name (Ordnername) oder Pfad; leer = Standard-Repository";

    private final GitSupport git;

    GitReadTools(GitSupport git) {
        this.git = git;
    }

    @Tool(name = "list_repositories", description = "Listet die freigegebenen Git-Repositories (Name -> Pfad) mit aktuellem Branch.")
    public String listRepositories() {
        Workspaces repos = git.repositories();
        if (repos.isEmpty()) {
            return "Keine Repositories konfiguriert.";
        }
        StringBuilder sb = new StringBuilder();
        repos.all().forEach((name, path) -> {
            String branch = git.with(name, (g, root) -> g.getRepository().getBranch());
            sb.append(name).append("  [").append(branch).append("]  ").append(path).append('\n');
        });
        return sb.toString().trim();
    }

    @Tool(name = "status", description = "Zeigt den Arbeitsstand eines Repositories: aktueller Branch, Ahead/Behind "
            + "zum Upstream, gestagte, geänderte, gelöschte, unversionierte und konfliktbehaftete Dateien.")
    public String status(@ToolParam(required = false, description = REPO_PARAM) String repository) {
        return git.with(repository, (g, root) -> {
            Repository repo = g.getRepository();
            Status s = g.status().call();
            StringBuilder sb = new StringBuilder();
            String branch = repo.getBranch();
            sb.append("Repository: ").append(root).append('\n');
            sb.append("Branch: ").append(branch);
            BranchTrackingStatus tracking = BranchTrackingStatus.of(repo, branch);
            if (tracking != null) {
                sb.append(" (Upstream ").append(Repository.shortenRefName(tracking.getRemoteTrackingBranch()))
                        .append(": ").append(tracking.getAheadCount()).append(" voraus, ")
                        .append(tracking.getBehindCount()).append(" zurück)");
            }
            sb.append('\n');
            if (s.isClean()) {
                return sb.append("Arbeitsverzeichnis sauber.").toString();
            }
            section(sb, "Gestaged (neu)", s.getAdded());
            section(sb, "Gestaged (geändert)", s.getChanged());
            section(sb, "Gestaged (gelöscht)", s.getRemoved());
            section(sb, "Geändert, nicht gestaged", s.getModified());
            section(sb, "Gelöscht, nicht gestaged", s.getMissing());
            section(sb, "Konflikte", s.getConflicting());
            section(sb, "Unversioniert", s.getUntracked());
            return Text.limitLines(sb.toString().trim(), git.maxLines());
        });
    }

    private static void section(StringBuilder sb, String title, Set<String> files) {
        if (files.isEmpty()) {
            return;
        }
        sb.append('\n').append(title).append(" (").append(files.size()).append("):\n");
        new TreeSet<>(files).forEach(f -> sb.append("  ").append(f).append('\n'));
    }

    @Tool(name = "log", description = "Commit-Historie (neueste zuerst): Hash, Datum, Autor, Betreff. "
            + "Optional für einen Branch/Tag/Commit und/oder eingeschränkt auf eine Datei bzw. ein Verzeichnis.")
    public String log(
            @ToolParam(required = false, description = REPO_PARAM) String repository,
            @ToolParam(required = false, description = "Startrevision (Branch, Tag, Commit, z.B. 'origin/main'); Standard HEAD") String ref,
            @ToolParam(required = false, description = "Relativer Pfad (Datei/Verzeichnis) zum Filtern") String path,
            @ToolParam(required = false, description = "Nur Commits, deren Nachricht diesen Text enthält (Groß-/Kleinschreibung egal)") String messageContains,
            @ToolParam(required = false, description = "Maximale Anzahl (Standard 30, max. 500)") Integer maxCount,
            @ToolParam(required = false, description = "Anzahl zu überspringender Commits (Paging)") Integer skip) {
        int max = clamp(maxCount, 30, 500);
        return git.with(repository, (g, root) -> {
            Repository repo = g.getRepository();
            LogCommand cmd = g.log().add(GitSupport.resolveRev(repo, ref));
            if (path != null && !path.isBlank()) {
                cmd.addPath(Workspaces.relativePath(root, path));
            }
            if (skip != null && skip > 0) {
                cmd.setSkip(skip);
            }
            String needle = messageContains == null ? null : messageContains.toLowerCase();
            if (needle == null) {
                cmd.setMaxCount(max);
            }
            List<String> lines = new ArrayList<>();
            for (RevCommit c : cmd.call()) {
                if (needle != null && !c.getFullMessage().toLowerCase().contains(needle)) {
                    continue;
                }
                PersonIdent a = c.getAuthorIdent();
                lines.add(GitSupport.shortId(c) + "  " + GitSupport.DATE.format(a.getWhenAsInstant()) + "  "
                        + a.getName() + "  " + c.getShortMessage());
                if (lines.size() >= max) {
                    break;
                }
            }
            return lines.isEmpty() ? "(keine Commits gefunden)" : String.join("\n", lines);
        });
    }

    @Tool(name = "diff", description = "Unified Diff. Ohne 'from': nicht gestagte Änderungen (bzw. mit staged=true "
            + "die gestagten). Mit 'from' und ohne 'to': Revision gegen Arbeitsverzeichnis. Mit 'from' und 'to': "
            + "zwischen zwei Revisionen (z.B. from='main', to='feature/x').")
    public String diff(
            @ToolParam(required = false, description = REPO_PARAM) String repository,
            @ToolParam(required = false, description = "Ausgangsrevision") String from,
            @ToolParam(required = false, description = "Zielrevision") String to,
            @ToolParam(required = false, description = "Relativer Pfad zum Einschränken") String path,
            @ToolParam(required = false, description = "true = gestagte Änderungen (HEAD gegen Index)") Boolean staged,
            @ToolParam(required = false, description = "Nur Dateiliste ohne Inhalt") Boolean nameOnly) {
        return git.with(repository, (g, root) -> {
            Repository repo = g.getRepository();
            var cmd = g.diff().setShowNameAndStatusOnly(Boolean.TRUE.equals(nameOnly));
            if (from != null && !from.isBlank()) {
                cmd.setOldTree(GitSupport.tree(repo, from));
                cmd.setNewTree(to != null && !to.isBlank() ? GitSupport.tree(repo, to) : new FileTreeIterator(repo));
            } else if (to != null && !to.isBlank()) {
                throw new IllegalArgumentException("'to' benötigt auch 'from'.");
            } else {
                cmd.setCached(Boolean.TRUE.equals(staged));
            }
            if (path != null && !path.isBlank()) {
                String rel = Workspaces.relativePath(root, path);
                if (!".".equals(rel)) {
                    cmd.setPathFilter(PathFilter.create(rel));
                }
            }
            // DiffCommand formatiert selbst: nur so werden Inhalte aus dem Arbeitsverzeichnis/Index korrekt gelesen
            ByteArrayOutputStream patch = new ByteArrayOutputStream();
            if (!Boolean.TRUE.equals(nameOnly)) {
                cmd.setOutputStream(patch);
            }
            List<DiffEntry> entries = cmd.call();
            if (entries.isEmpty()) {
                return "(keine Änderungen)";
            }
            List<String> lines = entries.stream().map(e -> e.getChangeType() + " " + GitSupport.pathOf(e)).toList();
            if (Boolean.TRUE.equals(nameOnly)) {
                return Text.limitLines(String.join("\n", lines), git.maxLines());
            }
            return Text.limitLines(entries.size() + " Datei(en) geändert:\n  " + String.join("\n  ", lines) + "\n\n"
                    + patch.toString(StandardCharsets.UTF_8), git.maxLines());
        });
    }

    @Tool(name = "show_commit", description = "Details eines Commits: vollständige Nachricht, Autor, Eltern und Diff zum ersten Elterncommit.")
    public String showCommit(
            @ToolParam(required = false, description = REPO_PARAM) String repository,
            @ToolParam(description = "Commit-Hash, Branch oder Tag") String commit,
            @ToolParam(required = false, description = "Relativer Pfad zum Einschränken des Diffs") String path) {
        return git.with(repository, (g, root) -> {
            Repository repo = g.getRepository();
            RevCommit c = GitSupport.commit(repo, commit);
            PersonIdent a = c.getAuthorIdent();
            StringBuilder sb = new StringBuilder();
            sb.append("Commit: ").append(c.getName()).append('\n');
            sb.append("Autor:  ").append(a.getName()).append(" <").append(a.getEmailAddress()).append(">\n");
            sb.append("Datum:  ").append(GitSupport.DATE.format(a.getWhenAsInstant())).append('\n');
            if (c.getParentCount() > 0) {
                sb.append("Eltern: ");
                for (RevCommit p : c.getParents()) {
                    sb.append(GitSupport.shortId(p)).append(' ');
                }
                sb.append('\n');
            }
            sb.append('\n').append(c.getFullMessage().trim()).append("\n\n");
            var cmd = g.diff().setOldTree(GitSupport.parentTree(repo, c)).setNewTree(GitSupport.tree(repo, c.getName()));
            if (path != null && !path.isBlank()) {
                cmd.setPathFilter(PathFilter.create(Workspaces.relativePath(root, path)));
            }
            sb.append(GitSupport.formatDiff(repo, cmd.call()));
            return Text.limitLines(sb.toString(), git.maxLines());
        });
    }

    @Tool(name = "branches", description = "Listet lokale und Remote-Branches mit letztem Commit; der aktuelle Branch ist mit * markiert.")
    public String branches(
            @ToolParam(required = false, description = REPO_PARAM) String repository,
            @ToolParam(required = false, description = "true = auch Remote-Branches") Boolean includeRemote) {
        return git.with(repository, (g, root) -> {
            Repository repo = g.getRepository();
            String current = repo.getFullBranch();
            var cmd = g.branchList();
            if (Boolean.TRUE.equals(includeRemote)) {
                cmd.setListMode(ListBranchCommand.ListMode.ALL);
            }
            List<String> lines = new ArrayList<>();
            for (Ref ref : cmd.call()) {
                ObjectId id = ref.getObjectId();
                String msg = "";
                if (id != null) {
                    RevCommit c = GitSupport.commit(repo, id.getName());
                    msg = GitSupport.DATE.format(c.getAuthorIdent().getWhenAsInstant()) + "  " + c.getShortMessage();
                }
                lines.add((ref.getName().equals(current) ? "* " : "  ") + Repository.shortenRefName(ref.getName())
                        + "  " + GitSupport.shortId(id) + "  " + msg);
            }
            return lines.isEmpty() ? "(keine Branches)" : Text.limitLines(String.join("\n", lines), git.maxLines());
        });
    }

    @Tool(name = "blame", description = "Zeilenweise Herkunft einer Datei (Commit, Autor, Datum) – optional für einen Zeilenbereich.")
    public String blame(
            @ToolParam(required = false, description = REPO_PARAM) String repository,
            @ToolParam(description = "Relativer Dateipfad") String path,
            @ToolParam(required = false, description = "Erste Zeile (1-basiert)") Integer startLine,
            @ToolParam(required = false, description = "Letzte Zeile (inklusive)") Integer endLine) {
        return git.with(repository, (g, root) -> {
            String rel = Workspaces.relativePath(root, path);
            BlameResult result = g.blame().setFilePath(rel).setFollowFileRenames(true).call();
            if (result == null) {
                throw new IllegalArgumentException("Datei nicht versioniert: " + rel);
            }
            RawText content = result.getResultContents();
            int total = content.size();
            int from = startLine == null ? 1 : Math.max(1, startLine);
            int to = endLine == null ? total : Math.min(total, endLine);
            List<String> lines = new ArrayList<>();
            for (int i = from - 1; i < to; i++) {
                RevCommit c = result.getSourceCommit(i);
                PersonIdent a = result.getSourceAuthor(i);
                lines.add(String.format("%5d  %s  %-18.18s %s  | %s", i + 1, GitSupport.shortId(c),
                        a == null ? "?" : a.getName(),
                        a == null ? "" : GitSupport.DATE.format(a.getWhenAsInstant()), content.getString(i)));
            }
            return Text.limitLines(String.join("\n", lines), git.maxLines());
        });
    }

    @Tool(name = "file_at_revision", description = "Liefert den Inhalt einer Datei in einer bestimmten Revision (Branch, Tag, Commit).")
    public String fileAtRevision(
            @ToolParam(required = false, description = REPO_PARAM) String repository,
            @ToolParam(description = "Relativer Dateipfad") String path,
            @ToolParam(required = false, description = "Revision; Standard HEAD") String revision) {
        return git.with(repository, (g, root) -> {
            Repository repo = g.getRepository();
            String rel = Workspaces.relativePath(root, path);
            RevCommit c = GitSupport.commit(repo, revision);
            try (TreeWalk walk = TreeWalk.forPath(repo, rel, c.getTree())) {
                if (walk == null) {
                    throw new IllegalArgumentException("Datei '" + rel + "' existiert nicht in " + GitSupport.shortId(c));
                }
                byte[] bytes = repo.open(walk.getObjectId(0), Constants.OBJ_BLOB).getBytes();
                if (RawText.isBinary(bytes)) {
                    return "(Binärdatei, " + bytes.length + " Bytes)";
                }
                return Text.limitLines(new String(bytes, StandardCharsets.UTF_8), git.maxLines());
            }
        });
    }

    static int clamp(Integer value, int def, int max) {
        if (value == null || value <= 0) {
            return def;
        }
        return Math.min(value, max);
    }
}
