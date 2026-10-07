package systems.grebe.devtools.mcp.modules.git;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.eclipse.jgit.api.CherryPickCommand;
import org.eclipse.jgit.api.CherryPickCommitMessageProvider;
import org.eclipse.jgit.api.CherryPickResult;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;

/**
 * Cherry-Pick (Schalter {@code allowCherryPick}): einzelne Commits oder Bereiche auf den aktuellen Branch übernehmen.
 * Lokal über JGit; bei Konflikten weiter mit {@link GitResolveTools}.
 */
public class GitCherryPickTools {

    private static final String REPO_PARAM = "Repository-Name (Ordnername) oder Pfad; leer = Standard-Repository";
    /** Schützt vor versehentlich riesigen Bereichen (z.B. falsche Basis bei 'a..b'). */
    static final int MAX_COMMITS = 200;

    private final GitSupport git;

    GitCherryPickTools(GitSupport git) {
        this.git = git;
    }

    @Tool(name = "cherry_pick", description = "Übernimmt Commits auf den aktuellen Branch – einzeln (Hash, Branch, Tag) "
            + "oder als Bereich 'a..b' (alle Commits nach a bis einschließlich b), ältester zuerst. Optional mit "
            + "Herkunftsvermerk (wie -x), nur stagen ohne Commit (wie -n) und mit Hauptlinie für Merge-Commits (wie -m). "
            + "Bereits enthaltene Änderungen werden übersprungen, der Autor bleibt erhalten. Hält bei Konflikten an – "
            + "dann lösen, git_stage, git_continue oder git_abort. Statt `git cherry-pick` in der Shell verwenden."
            + ShellHints.GIT)
    public String cherryPick(
            @ToolParam(required = false, description = REPO_PARAM) String repository,
            @ToolParam(description = "Commits (Hash, Branch, Tag) oder Bereiche 'a..b', ältester zuerst") List<String> commits,
            @ToolParam(required = false, description = "true = '(cherry picked from commit …)' an die Nachricht hängen (wie -x)") Boolean recordOrigin,
            @ToolParam(required = false, description = "true = Änderungen nur übernehmen und stagen, nicht committen (wie -n)") Boolean noCommit,
            @ToolParam(required = false, description = "Für Merge-Commits: Nummer der Eltern-Hauptlinie, meist 1 (wie -m)") Integer mainline) {
        if (commits == null || commits.isEmpty()) {
            throw new IllegalArgumentException("Keine Commits angegeben ('commits').");
        }
        boolean origin = Boolean.TRUE.equals(recordOrigin);
        boolean stageOnly = Boolean.TRUE.equals(noCommit);
        if (origin && stageOnly) {
            throw new IllegalArgumentException("'recordOrigin' wirkt nur beim Committen – nicht zusammen mit 'noCommit'.");
        }
        if (mainline != null && mainline < 1) {
            throw new IllegalArgumentException("'mainline' beginnt bei 1 (erster Elternteil).");
        }
        return git.withWrite(repository, (g, root) -> {
            Repository repo = g.getRepository();
            GitSupport.requireSafe(repo);
            List<RevCommit> picks = expand(repo, commits);
            validateParents(picks, mainline);
            if (!stageOnly) {
                return pick(g, picks, origin, mainline, false);
            }
            // JGit kann mit noCommit keinen zweiten Commit auf einen geänderten Index setzen – daher einzeln committen
            // und danach per Soft-Reset nur die Änderungen gestaged lassen (Ergebnis wie git cherry-pick -n).
            ObjectId start = repo.resolve(Constants.HEAD);
            try {
                return pick(g, picks, false, mainline, true);
            } finally {
                g.reset().setMode(ResetCommand.ResetType.SOFT).setRef(start.name()).call();
                repo.writeCherryPickHead(null);
                repo.writeMergeCommitMsg(null);
            }
        });
    }

    private static String pick(Git g, List<RevCommit> picks, boolean origin, Integer mainline, boolean stageOnly)
            throws IOException, GitAPIException {
        Repository repo = g.getRepository();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < picks.size(); i++) {
            RevCommit commit = picks.get(i);
            CherryPickCommand cmd = g.cherryPick().include(commit);
            if (origin) {
                cmd.setCherryPickCommitMessageProvider(CherryPickCommitMessageProvider.ORIGINAL_WITH_REFERENCE);
            }
            if (commit.getParentCount() > 1) {
                cmd.setMainlineParentNumber(mainline);
            }
            CherryPickResult r = cmd.call();
            String label = GitSupport.shortId(commit) + "  " + commit.getShortMessage();
            switch (r.getStatus()) {
                case OK -> {
                    if (r.getCherryPickedRefs().isEmpty()) {
                        sb.append("Übersprungen (Änderungen bereits enthalten): ").append(label).append('\n');
                    } else if (stageOnly) {
                        sb.append("Gestaged: ").append(label).append('\n');
                    } else {
                        sb.append("Übernommen: ").append(GitSupport.shortId(commit)).append(" → ")
                                .append(GitSupport.shortId(r.getNewHead())).append("  ")
                                .append(commit.getShortMessage()).append('\n');
                    }
                }
                case CONFLICTING -> {
                    if (origin) {
                        withOriginInPreparedMessage(repo, commit);
                    }
                    sb.append("Konflikt beim Übernehmen von ").append(label).append('.').append(GitSupport.conflicts(g));
                    List<RevCommit> rest = picks.subList(i + 1, picks.size());
                    if (!rest.isEmpty()) {
                        sb.append("\nNoch nicht übernommen (danach erneut git_cherry_pick): ").append(ids(rest));
                    }
                    return sb.append(stageOnly
                            ? "\nKonflikte lösen (Dateien bearbeiten) und git_stage – es wird nichts committet."
                            : GitSupport.CONFLICT_HINT).toString();
                }
                case FAILED -> throw new IllegalStateException("Cherry-Pick von " + GitSupport.shortId(commit)
                        + " nicht möglich – lokale Änderungen würden überschrieben: " + GitSupport.failing(r.getFailingPaths())
                        + ". Zuerst committen oder git_stash." + (sb.isEmpty() ? "" : "\nBereits erledigt:\n" + sb));
                default -> throw new IllegalStateException("Cherry-Pick: " + r.getStatus());
            }
        }
        if (stageOnly) {
            sb.append("Nicht committet – prüfen und mit git_commit abschließen.");
        }
        return sb.toString().strip();
    }

    // ------------------------------------------------------------------ Hilfen

    /** Löst Einträge auf; 'a..b' wird zu allen Commits aus b, die nicht in a sind (ältester zuerst, wie git). */
    static List<RevCommit> expand(Repository repo, List<String> specs) throws IOException {
        List<RevCommit> result = new ArrayList<>();
        for (String spec : specs) {
            String s = spec == null ? "" : spec.trim();
            if (s.isEmpty()) {
                throw new IllegalArgumentException("Leerer Eintrag in 'commits'.");
            }
            if (s.contains("...")) {
                throw new IllegalArgumentException("Symmetrische Bereiche 'a...b' werden nicht unterstützt – 'a..b' verwenden.");
            }
            int dots = s.indexOf("..");
            if (dots < 0) {
                result.add(GitSupport.commit(repo, s));
                continue;
            }
            List<RevCommit> range = new ArrayList<>();
            try (RevWalk walk = new RevWalk(repo)) {
                walk.sort(RevSort.TOPO, true);
                walk.sort(RevSort.REVERSE, true);
                walk.markStart(walk.parseCommit(GitSupport.resolveRev(repo, s.substring(dots + 2))));
                walk.markUninteresting(walk.parseCommit(GitSupport.resolveRev(repo, s.substring(0, dots))));
                for (RevCommit c : walk) {
                    range.add(c);
                    if (result.size() + range.size() > MAX_COMMITS) {
                        throw new IllegalArgumentException("Mehr als " + MAX_COMMITS + " Commits – kleineren Bereich wählen.");
                    }
                }
            }
            if (range.isEmpty()) {
                throw new IllegalArgumentException("Bereich '" + s + "' enthält keine Commits.");
            }
            result.addAll(range);
        }
        if (result.size() > MAX_COMMITS) {
            throw new IllegalArgumentException("Mehr als " + MAX_COMMITS + " Commits – kleineren Bereich wählen.");
        }
        return result;
    }

    /** Prüft vor dem ersten Commit, damit nicht auf halber Strecke abgebrochen wird. */
    private static void validateParents(List<RevCommit> picks, Integer mainline) {
        for (RevCommit c : picks) {
            if (c.getParentCount() <= 1) {
                continue;
            }
            if (mainline == null) {
                throw new IllegalArgumentException(GitSupport.shortId(c) + " (" + c.getShortMessage() + ") ist ein "
                        + "Merge-Commit – 'mainline' angeben (meist 1) oder ihn auslassen.");
            }
            if (mainline > c.getParentCount()) {
                throw new IllegalArgumentException(GitSupport.shortId(c) + " hat nur " + c.getParentCount()
                        + " Eltern – 'mainline' " + mainline + " gibt es nicht.");
            }
        }
    }

    /** JGit legt die Nachricht für git_continue ohne Herkunftsvermerk ab – wie bei git -x ergänzen. */
    private static void withOriginInPreparedMessage(Repository repo, RevCommit commit) throws IOException {
        String prepared = repo.readMergeCommitMsg();
        String original = commit.getFullMessage();
        if (prepared != null && prepared.startsWith(original)) {
            repo.writeMergeCommitMsg(CherryPickCommitMessageProvider.ORIGINAL_WITH_REFERENCE
                    .getCherryPickedCommitMessage(commit) + prepared.substring(original.length()));
        }
    }

    private static String ids(List<RevCommit> commits) {
        return commits.stream().map(GitSupport::shortId).collect(Collectors.joining(" "));
    }
}
