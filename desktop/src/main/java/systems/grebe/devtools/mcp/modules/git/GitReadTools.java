package systems.grebe.devtools.mcp.modules.git;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.eclipse.jgit.api.ListBranchCommand;
import org.eclipse.jgit.api.LogCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.blame.BlameResult;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.BranchTrackingStatus;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryState;
import org.eclipse.jgit.lib.ReflogEntry;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.Text;
import systems.grebe.devtools.mcp.core.Workspaces;
import systems.grebe.devtools.mcp.core.ShellHints;

/** Lesende Git-Tools. */
public class GitReadTools {

    private static final String REPO_PARAM = "Repository-Name (Ordnername) oder Pfad; leer = Standard-Repository";

    private final GitSupport git;

    GitReadTools(GitSupport git) {
        this.git = git;
    }

    @Tool(name = "list_repositories", description = "Listet die freigegebenen Git-Repositories (Name -> Pfad) mit aktuellem "
            + "Branch, darunter ihre Worktrees als <repository>/<ordner>."
            + " Statt `git rev-parse`/`git branch --show-current`/`git worktree list` in der Shell verwenden." + ShellHints.GIT)
    public String listRepositories() {
        Workspaces repos = git.repositories();
        if (repos.isEmpty()) {
            return Workspaces.unrestricted() ? repos.unrestrictedHint() : "Keine Repositories konfiguriert.";
        }
        StringBuilder sb = new StringBuilder();
        repos.all().forEach((name, path) -> {
            String branch = git.with(name, (g, root) -> g.getRepository().getBranch());
            sb.append(name).append("  [").append(branch).append("]  ").append(path).append('\n');
        });
        for (GitSupport.Worktree w : git.worktrees()) {
            String branch;
            try {
                branch = git.with(w.name(), (g, root) -> g.getRepository().getBranch());
            } catch (RuntimeException e) {
                branch = "?";
            }
            sb.append(w.name()).append("  [").append(branch).append("]  ").append(w.dir()).append("  (Worktree)\n");
        }
        sb.append('\n').append(repos.unrestrictedHint());
        return sb.toString().trim();
    }

    @Tool(name = "status", description = "Zeigt den Arbeitsstand eines Repositories: aktueller Branch, Ahead/Behind "
            + "zum Upstream, gestagte, geänderte, gelöschte, unversionierte und konfliktbehaftete Dateien."
            + " Statt `git status` in der Shell verwenden." + ShellHints.GIT)
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
            RepositoryState state = repo.getRepositoryState();
            if (state != RepositoryState.SAFE) {
                sb.append("Zustand: ").append(GitSupport.describe(state)).append(" – Konflikte lösen, git_stage, dann "
                        + "git_continue; oder git_abort\n");
            }
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
            + "Optional für einen Branch/Tag/Commit und/oder eingeschränkt auf eine Datei bzw. ein Verzeichnis."
            + " Statt `git log` in der Shell verwenden." + ShellHints.GIT)
    public String log(
            @ToolParam(required = false, description = REPO_PARAM) String repository,
            @ToolParam(required = false, description = "Startrevision (Branch, Tag, Commit, z.B. 'origin/main'); Standard HEAD") String ref,
            @ToolParam(required = false, description = "Relativer Pfad (Datei/Verzeichnis) zum Filtern") String path,
            @ToolParam(required = false, description = "Nur Commits, deren Nachricht diesen Text enthält (Groß-/Kleinschreibung egal)") String messageContains,
            @ToolParam(required = false, description = "Maximale Anzahl (Standard 30, max. 500)") Integer maxCount,
            @ToolParam(required = false, description = "Anzahl zu überspringender Commits (Paging)") Integer skip,
            @ToolParam(required = false, description = "Nur Commits, die die Anzahl der Vorkommen dieses Textes im Code "
                    + "ändern – findet, wann etwas eingeführt oder entfernt wurde (wie git log -S)") String contentChange) {
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
            String pickaxe = contentChange == null || contentChange.isEmpty() ? null : contentChange;
            if (needle == null && pickaxe == null) {
                cmd.setMaxCount(max);
            }
            List<String> lines = new ArrayList<>();
            int scanned = 0;
            for (RevCommit c : cmd.call()) {
                if (pickaxe != null && ++scanned > PICKAXE_SCAN) {
                    lines.add("… Suche nach " + PICKAXE_SCAN + " Commits abgebrochen – mit 'ref'/'skip'/'path' eingrenzen");
                    break;
                }
                if (needle != null && !c.getFullMessage().toLowerCase().contains(needle)) {
                    continue;
                }
                if (pickaxe != null && !changesOccurrences(repo, c, pickaxe,
                        path == null || path.isBlank() ? null : Workspaces.relativePath(root, path))) {
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
            + "zwischen zwei Revisionen (z.B. from='main', to='feature/x')."
            + " Statt `git diff` in der Shell verwenden." + ShellHints.GIT)
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

    @Tool(name = "show_commit", description = "Details eines Commits: vollständige Nachricht, Autor, Eltern und Diff zum ersten Elterncommit."
            + " Statt `git show <commit>` in der Shell verwenden." + ShellHints.GIT)
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

    @Tool(name = "branches", description = "Listet lokale und Remote-Branches mit letztem Commit; der aktuelle Branch ist mit * markiert."
            + " Statt `git branch -a` in der Shell verwenden." + ShellHints.GIT)
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

    @Tool(name = "blame", description = "Zeilenweise Herkunft einer Datei (Commit, Autor, Datum) – optional für einen Zeilenbereich."
            + " Statt `git blame` in der Shell verwenden." + ShellHints.GIT)
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

    @Tool(name = "file_at_revision", description = "Liefert den Inhalt einer Datei in einer bestimmten Revision (Branch, Tag, Commit)."
            + " Statt `git show <rev>:<pfad>` in der Shell verwenden." + ShellHints.GIT)
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

    private static final int PICKAXE_SCAN = 3000;
    private static final int MAX_BLOB = 2 * 1024 * 1024;

    /** Ob der Commit die Anzahl der Vorkommen von {@code text} in einer Datei ändert (wie {@code git log -S}). */
    private static boolean changesOccurrences(Repository repo, RevCommit c, String text, String path) throws IOException {
        try (DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            df.setRepository(repo);
            if (path != null && !".".equals(path)) {
                df.setPathFilter(PathFilter.create(path));
            }
            List<DiffEntry> entries = df.scan(GitSupport.parentTree(repo, c), GitSupport.tree(repo, c.getName()));
            for (DiffEntry e : entries) {
                if (count(repo, e.getOldId().toObjectId(), text) != count(repo, e.getNewId().toObjectId(), text)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static int count(Repository repo, ObjectId id, String text) throws IOException {
        if (id == null || ObjectId.zeroId().equals(id) || !repo.getObjectDatabase().has(id)) {
            return 0;
        }
        var loader = repo.open(id);
        if (loader.getSize() > MAX_BLOB) {
            return 0;
        }
        byte[] bytes = loader.getBytes();
        if (RawText.isBinary(bytes)) {
            return 0;
        }
        String s = new String(bytes, StandardCharsets.UTF_8);
        int n = 0;
        for (int i = s.indexOf(text); i >= 0; i = s.indexOf(text, i + text.length())) {
            n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ Tags, Remotes, Stashes, Reflog

    @Tool(name = "tags", description = "Listet Tags (neueste zuerst) mit Commit, Datum und – bei annotierten Tags – "
            + "Nachricht. Statt `git tag -l`/`git describe` in der Shell verwenden." + ShellHints.GIT)
    public String tags(
            @ToolParam(required = false, description = REPO_PARAM) String repository,
            @ToolParam(required = false, description = "Nur Tags, deren Name dies enthält") String filter) {
        return git.with(repository, (g, root) -> {
            Repository repo = g.getRepository();
            record TagLine(long when, String text) { }
            List<TagLine> out = new ArrayList<>();
            try (RevWalk walk = new RevWalk(repo)) {
                for (Ref ref : g.tagList().call()) {
                    String name = Repository.shortenRefName(ref.getName());
                    if (filter != null && !filter.isBlank() && !name.toLowerCase(Locale.ROOT)
                            .contains(filter.trim().toLowerCase(Locale.ROOT))) {
                        continue;
                    }
                    RevObject obj = walk.parseAny(ref.getObjectId());
                    String message = "";
                    if (obj instanceof RevTag tag) {
                        message = "  „" + tag.getShortMessage() + "“";
                        obj = walk.peel(tag);
                    }
                    if (obj instanceof RevCommit c) {
                        walk.parseHeaders(c);
                        out.add(new TagLine(c.getCommitTime(), name + "  " + GitSupport.shortId(c) + "  "
                                + GitSupport.DATE.format(c.getAuthorIdent().getWhenAsInstant()) + "  "
                                + c.getShortMessage() + message));
                    } else {
                        out.add(new TagLine(0, name + "  " + GitSupport.shortId(obj) + message));
                    }
                }
            }
            if (out.isEmpty()) {
                return "(keine Tags)";
            }
            out.sort(Comparator.comparingLong(TagLine::when).reversed());
            return Text.limitLines(String.join("\n", out.stream().map(TagLine::text).toList()), git.maxLines());
        });
    }

    @Tool(name = "remotes", description = "Listet die Remotes mit Fetch-/Push-URL und deren Remote-Branches. "
            + "Statt `git remote -v` in der Shell verwenden." + ShellHints.GIT)
    public String remotes(@ToolParam(required = false, description = REPO_PARAM) String repository) {
        return git.with(repository, (g, root) -> {
            List<RemoteConfig> remotes = g.remoteList().call();
            if (remotes.isEmpty()) {
                return "(keine Remotes)";
            }
            StringBuilder sb = new StringBuilder();
            for (RemoteConfig r : remotes) {
                sb.append(r.getName()).append('\n');
                r.getURIs().forEach(u -> sb.append("  fetch: ").append(safe(u)).append('\n'));
                (r.getPushURIs().isEmpty() ? r.getURIs() : r.getPushURIs())
                        .forEach(u -> sb.append("  push:  ").append(safe(u)).append('\n'));
                long branches = g.getRepository().getRefDatabase()
                        .getRefsByPrefix(Constants.R_REMOTES + r.getName() + "/").size();
                sb.append("  Remote-Branches: ").append(branches).append('\n');
            }
            return sb.toString().strip();
        });
    }

    /** URL ohne Passwort. */
    private static String safe(URIish u) {
        return u.setPass(null).toString();
    }

    @Tool(name = "stash_list", description = "Listet die Stashes (stash@{n}) mit Branch, Datum und Nachricht. "
            + "Statt `git stash list` in der Shell verwenden." + ShellHints.GIT)
    public String stashList(@ToolParam(required = false, description = REPO_PARAM) String repository) {
        return git.with(repository, (g, root) -> {
            Collection<RevCommit> stashes = g.stashList().call();
            if (stashes.isEmpty()) {
                return "(keine Stashes)";
            }
            List<String> lines = new ArrayList<>();
            int i = 0;
            for (RevCommit c : stashes) {
                lines.add("stash@{" + i++ + "}  " + GitSupport.DATE.format(c.getAuthorIdent().getWhenAsInstant()) + "  "
                        + c.getShortMessage());
            }
            return String.join("\n", lines);
        });
    }

    @Tool(name = "reflog", description = "Reflog eines Refs (Standard HEAD): frühere Stände mit Aktion (commit, checkout, "
            + "reset, rebase …) – um verlorene Commits wiederzufinden. Statt `git reflog` in der Shell verwenden."
            + ShellHints.GIT)
    public String reflog(
            @ToolParam(required = false, description = REPO_PARAM) String repository,
            @ToolParam(required = false, description = "Ref, z.B. HEAD oder main; Standard HEAD") String ref,
            @ToolParam(required = false, description = "Maximale Anzahl (Standard 30, max. 500)") Integer maxCount) {
        int max = clamp(maxCount, 30, 500);
        return git.with(repository, (g, root) -> {
            String r = ref == null || ref.isBlank() ? Constants.HEAD : ref.trim();
            Collection<ReflogEntry> entries = g.reflog().setRef(r).call();
            if (entries.isEmpty()) {
                return "(kein Reflog für " + r + ")";
            }
            List<String> lines = new ArrayList<>();
            int i = 0;
            for (ReflogEntry e : entries) {
                if (i >= max) {
                    break;
                }
                lines.add(r + "@{" + i++ + "}  " + GitSupport.shortId(e.getNewId()) + "  "
                        + GitSupport.DATE.format(e.getWho().getWhenAsInstant()) + "  " + e.getComment());
            }
            return String.join("\n", lines);
        });
    }

    // ------------------------------------------------------------------ Vergleichen und Suchen

    @Tool(name = "compare", description = "Vergleicht zwei Branches/Revisionen: gemeinsamer Vorfahre (merge-base), wie "
            + "viele Commits 'head' voraus bzw. zurück ist und welche Commits nur auf einer Seite liegen – z.B. vor "
            + "Merge, Rebase oder Pull Request. Statt `git log a..b`/`git merge-base`/`git rev-list --count` in der "
            + "Shell verwenden." + ShellHints.GIT)
    public String compare(
            @ToolParam(required = false, description = REPO_PARAM) String repository,
            @ToolParam(description = "Basis, z.B. 'main' oder 'origin/main'") String base,
            @ToolParam(required = false, description = "Vergleichsstand; Standard HEAD") String head,
            @ToolParam(required = false, description = "Höchstens so viele Commits je Seite auflisten (Standard 30)") Integer maxCount) {
        int max = clamp(maxCount, 30, 500);
        return git.with(repository, (g, root) -> {
            Repository repo = g.getRepository();
            String h = head == null || head.isBlank() ? Constants.HEAD : head.trim();
            ObjectId baseId = GitSupport.resolveRev(repo, base);
            ObjectId headId = GitSupport.resolveRev(repo, h);
            RevCommit mergeBase;
            try (RevWalk walk = new RevWalk(repo)) {
                walk.setRevFilter(RevFilter.MERGE_BASE);
                walk.markStart(walk.parseCommit(baseId));
                walk.markStart(walk.parseCommit(headId));
                mergeBase = walk.next();
            }
            List<RevCommit> ahead = only(repo, headId, baseId);
            List<RevCommit> behind = only(repo, baseId, headId);
            StringBuilder sb = new StringBuilder(h + " gegenüber " + base.trim() + ": " + ahead.size() + " voraus, "
                    + behind.size() + " zurück\n");
            sb.append("merge-base: ").append(mergeBase == null ? "keiner (keine gemeinsame Historie)"
                    : GitSupport.shortId(mergeBase) + "  " + mergeBase.getShortMessage()).append('\n');
            if (behind.isEmpty() && !ahead.isEmpty()) {
                sb.append("Fast-Forward von ").append(base.trim()).append(" auf ").append(h).append(" möglich.\n");
            }
            appendCommits(sb, "Nur in " + h, ahead, max);
            appendCommits(sb, "Nur in " + base.trim(), behind, max);
            if (mergeBase != null && !ahead.isEmpty()) {
                sb.append("\nÄnderungen von ").append(h).append(": git_diff from=").append(GitSupport.shortId(mergeBase))
                        .append(" to=").append(h);
            }
            return Text.limitLines(sb.toString().strip(), git.maxLines());
        });
    }

    /** Commits, die von {@code include} aus erreichbar sind, aber nicht von {@code exclude}. */
    private static List<RevCommit> only(Repository repo, ObjectId include, ObjectId exclude) throws IOException {
        List<RevCommit> out = new ArrayList<>();
        try (RevWalk walk = new RevWalk(repo)) {
            walk.markStart(walk.parseCommit(include));
            walk.markUninteresting(walk.parseCommit(exclude));
            for (RevCommit c : walk) {
                out.add(c);
            }
        }
        return out;
    }

    private static void appendCommits(StringBuilder sb, String title, List<RevCommit> commits, int max) {
        if (commits.isEmpty()) {
            return;
        }
        sb.append('\n').append(title).append(" (").append(commits.size()).append("):\n");
        commits.stream().limit(max).forEach(c -> sb.append("  ").append(GitSupport.shortId(c)).append("  ")
                .append(GitSupport.DATE.format(c.getAuthorIdent().getWhenAsInstant())).append("  ")
                .append(c.getAuthorIdent().getName()).append("  ").append(c.getShortMessage()).append('\n'));
        if (commits.size() > max) {
            sb.append("  … ").append(commits.size() - max).append(" weitere\n");
        }
    }

    @Tool(name = "grep", description = "Durchsucht versionierte Dateien nach Text oder regulärem Ausdruck – im "
            + "Arbeitsverzeichnis oder in einer Revision. Ausgabe pfad:zeile: inhalt. Statt `git grep`/`grep -r` in der "
            + "Shell verwenden." + ShellHints.GIT)
    public String grep(
            @ToolParam(required = false, description = REPO_PARAM) String repository,
            @ToolParam(description = "Suchtext bzw. regulärer Ausdruck (mit regex=true)") String pattern,
            @ToolParam(required = false, description = "true = 'pattern' ist ein regulärer Ausdruck (Java-Syntax)") Boolean regex,
            @ToolParam(required = false, description = "true = Groß-/Kleinschreibung beachten (Standard: ignorieren)") Boolean caseSensitive,
            @ToolParam(required = false, description = "Nur unter diesem relativen Pfad (Datei oder Verzeichnis)") String path,
            @ToolParam(required = false, description = "Nur Dateien mit dieser Endung, z.B. '.java'") String extension,
            @ToolParam(required = false, description = "Revision (Branch, Tag, Commit); leer = Arbeitsverzeichnis") String revision,
            @ToolParam(required = false, description = "Maximale Trefferzahl (Standard 200, max. 2000)") Integer maxResults) {
        if (pattern == null || pattern.isEmpty()) {
            throw new IllegalArgumentException("Suchtext fehlt ('pattern').");
        }
        int max = clamp(maxResults, 200, 2000);
        int flags = Boolean.TRUE.equals(caseSensitive) ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        Pattern p;
        try {
            p = Pattern.compile(Boolean.TRUE.equals(regex) ? pattern : Pattern.quote(pattern), flags);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("Ungültiger regulärer Ausdruck: " + e.getDescription());
        }
        return git.with(repository, (g, root) -> {
            Repository repo = g.getRepository();
            String prefix = path == null || path.isBlank() ? null : Workspaces.relativePath(root, path);
            if (".".equals(prefix)) {
                prefix = null;
            }
            String ext = extension == null || extension.isBlank() ? null : extension.trim().toLowerCase(Locale.ROOT);
            List<String> hits = new ArrayList<>();
            int[] files = {0};
            if (revision == null || revision.isBlank()) {
                DirCache index = repo.readDirCache();
                for (int i = 0; i < index.getEntryCount() && hits.size() < max; i++) {
                    DirCacheEntry e = index.getEntry(i);
                    String file = e.getPathString();
                    if (!matchesPath(file, prefix, ext)) {
                        continue;
                    }
                    Path f = root.resolve(file);
                    if (!Files.isRegularFile(f) || Files.size(f) > MAX_BLOB) {
                        continue;
                    }
                    files[0]++;
                    search(file, Files.readAllBytes(f), p, hits, max);
                }
            } else {
                RevCommit c = GitSupport.commit(repo, revision);
                try (TreeWalk walk = new TreeWalk(repo)) {
                    walk.addTree(c.getTree());
                    walk.setRecursive(true);
                    while (walk.next() && hits.size() < max) {
                        String file = walk.getPathString();
                        if (!matchesPath(file, prefix, ext)) {
                            continue;
                        }
                        var loader = repo.open(walk.getObjectId(0));
                        if (loader.getSize() > MAX_BLOB) {
                            continue;
                        }
                        files[0]++;
                        search(file, loader.getBytes(), p, hits, max);
                    }
                }
            }
            if (hits.isEmpty()) {
                return "(keine Treffer in " + files[0] + " Datei(en))";
            }
            String head = hits.size() >= max ? "Mindestens " + max + " Treffer (abgeschnitten – genauer suchen):\n"
                    : hits.size() + " Treffer:\n";
            return Text.limitLines(head + String.join("\n", hits), git.maxLines());
        });
    }

    private static boolean matchesPath(String file, String prefix, String ext) {
        if (prefix != null && !(file.equals(prefix) || file.startsWith(prefix + "/"))) {
            return false;
        }
        return ext == null || file.toLowerCase(Locale.ROOT).endsWith(ext.startsWith(".") ? ext : "." + ext);
    }

    private static void search(String file, byte[] bytes, Pattern p, List<String> hits, int max) {
        if (RawText.isBinary(bytes)) {
            return;
        }
        String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\r?\n", -1);
        for (int i = 0; i < lines.length && hits.size() < max; i++) {
            Matcher m = p.matcher(lines[i]);
            if (m.find()) {
                String line = lines[i].strip();
                hits.add(file + ":" + (i + 1) + ": " + (line.length() > 300 ? line.substring(0, 300) + "…" : line));
            }
        }
    }

    static int clamp(Integer value, int def, int max) {
        if (value == null || value <= 0) {
            return def;
        }
        return Math.min(value, max);
    }
}
