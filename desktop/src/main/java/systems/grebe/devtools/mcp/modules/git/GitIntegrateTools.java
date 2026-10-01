package systems.grebe.devtools.mcp.modules.git;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

import org.eclipse.jgit.api.CherryPickResult;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.RebaseCommand;
import org.eclipse.jgit.api.RebaseResult;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.RevertCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryState;
import org.eclipse.jgit.merge.ResolveMerger;
import org.eclipse.jgit.revwalk.RevCommit;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;

/**
 * Integrieren (Schalter {@code allowIntegrate}): merge, rebase, cherry-pick, revert sowie continue/abort bei
 * Konflikten. Lokal über JGit – kein Netzwerk, kein Push.
 */
public class GitIntegrateTools {

    private static final String REPO_PARAM = "Repository-Name (Ordnername) oder Pfad; leer = Standard-Repository";
    private static final String CONFLICT_HINT = "\nKonflikte lösen (Dateien bearbeiten), git_stage, dann git_continue – "
            + "oder git_abort für den Ausgangszustand.";

    private final GitSupport git;

    GitIntegrateTools(GitSupport git) {
        this.git = git;
    }

    @Tool(name = "merge", description = "Führt einen Branch/eine Revision in den aktuellen Branch zusammen: ff (Standard; "
            + "Fast-Forward wenn möglich), no-ff (immer Merge-Commit), ff-only oder squash (Änderungen nur stagen). "
            + "Meldet Konflikte. Statt `git merge` in der Shell verwenden." + ShellHints.GIT)
    public String merge(
            @ToolParam(required = false, description = REPO_PARAM) String repository,
            @ToolParam(description = "Branch oder Revision, z.B. 'origin/main' oder 'feature/x'") String branch,
            @ToolParam(required = false, description = "ff (Standard), no-ff, ff-only oder squash") String mode,
            @ToolParam(required = false, description = "Nachricht des Merge-Commits") String message) {
        String m = mode == null || mode.isBlank() ? "ff" : mode.trim().toLowerCase(Locale.ROOT);
        return git.withWrite(repository, (g, root) -> {
            Repository repo = g.getRepository();
            requireSafe(repo);
            MergeCommand cmd = g.merge();
            Ref ref = repo.findRef(branch.trim());
            if (ref != null) {
                cmd.include(ref);
            } else {
                cmd.include(GitSupport.resolveRev(repo, branch));
            }
            switch (m) {
                case "ff" -> cmd.setFastForward(MergeCommand.FastForwardMode.FF);
                case "no-ff" -> cmd.setFastForward(MergeCommand.FastForwardMode.NO_FF);
                case "ff-only" -> cmd.setFastForward(MergeCommand.FastForwardMode.FF_ONLY);
                case "squash" -> cmd.setSquash(true);
                default -> throw new IllegalArgumentException("Unbekannter Modus '" + mode
                        + "' – erlaubt: ff, no-ff, ff-only, squash.");
            }
            if (message != null && !message.isBlank()) {
                cmd.setMessage(message.strip());
            }
            MergeResult r = cmd.call();
            String target = repo.getBranch();
            return switch (r.getMergeStatus()) {
                case ALREADY_UP_TO_DATE -> target + " enthält " + branch.trim() + " bereits.";
                case FAST_FORWARD, FAST_FORWARD_SQUASHED -> target + " per Fast-Forward auf "
                        + GitSupport.shortId(r.getNewHead()) + " gebracht.";
                case MERGED -> branch.trim() + " in " + target + " gemergt: " + GitSupport.shortId(r.getNewHead());
                case MERGED_SQUASHED, MERGED_SQUASHED_NOT_COMMITTED -> branch.trim() + " als Squash gestaged – mit "
                        + "git_commit abschließen.";
                case CONFLICTING -> "Merge von " + branch.trim() + " hat Konflikte in:\n  "
                        + String.join("\n  ", new TreeSet<>(r.getConflicts() == null ? List.of() : r.getConflicts().keySet()))
                        + CONFLICT_HINT;
                case ABORTED -> "Kein Fast-Forward möglich (ff-only) – " + target + " und " + branch.trim()
                        + " sind auseinandergelaufen; mode=ff/no-ff oder git_rebase.";
                case FAILED -> throw new IllegalStateException("Merge nicht möglich – lokale Änderungen würden "
                        + "überschrieben: " + failing(r.getFailingPaths()) + ". Zuerst committen oder git_stash.");
                default -> "Merge: " + r.getMergeStatus();
            };
        });
    }

    @Tool(name = "rebase", description = "Setzt die eigenen Commits des aktuellen Branches auf eine neue Basis (z.B. "
            + "origin/main). Hält bei Konflikten an – dann lösen, git_stage, git_continue oder git_abort. Nicht für "
            + "bereits geteilte Branches ohne Absprache. Statt `git rebase` in der Shell verwenden." + ShellHints.GIT)
    public String rebase(
            @ToolParam(required = false, description = REPO_PARAM) String repository,
            @ToolParam(description = "Neue Basis, z.B. 'origin/main'") String onto) {
        return git.withWrite(repository, (g, root) -> {
            Repository repo = g.getRepository();
            requireSafe(repo);
            String branch = repo.getBranch();
            RebaseResult r = g.rebase().setUpstream(GitSupport.resolveRev(repo, onto)).call();
            return describe(r, branch, onto.trim());
        });
    }

    @Tool(name = "cherry_pick", description = "Übernimmt einzelne Commits (in der angegebenen Reihenfolge) auf den "
            + "aktuellen Branch. Hält bei Konflikten an. Statt `git cherry-pick` in der Shell verwenden." + ShellHints.GIT)
    public String cherryPick(
            @ToolParam(required = false, description = REPO_PARAM) String repository,
            @ToolParam(description = "Commits (Hash, Branch, Tag), ältester zuerst") List<String> commits) {
        if (commits == null || commits.isEmpty()) {
            throw new IllegalArgumentException("Keine Commits angegeben ('commits').");
        }
        return git.withWrite(repository, (g, root) -> {
            Repository repo = g.getRepository();
            requireSafe(repo);
            StringBuilder sb = new StringBuilder();
            for (String c : commits) {
                RevCommit commit = GitSupport.commit(repo, c);
                CherryPickResult r = g.cherryPick().include(commit).call();
                switch (r.getStatus()) {
                    case OK -> sb.append("Übernommen: ").append(GitSupport.shortId(commit)).append(" → ")
                            .append(GitSupport.shortId(r.getNewHead())).append("  ").append(commit.getShortMessage())
                            .append('\n');
                    case CONFLICTING -> {
                        return sb.append("Konflikt beim Übernehmen von ").append(GitSupport.shortId(commit))
                                .append(" (").append(commit.getShortMessage()).append(") – weitere Commits nicht übernommen.")
                                .append(conflicts(g)).append(CONFLICT_HINT).toString();
                    }
                    case FAILED -> throw new IllegalStateException("Cherry-Pick von " + GitSupport.shortId(commit)
                            + " nicht möglich – lokale Änderungen würden überschrieben: " + failing(r.getFailingPaths())
                            + ". Zuerst committen oder git_stash." + (sb.isEmpty() ? "" : "\nBereits übernommen:\n" + sb));
                    default -> throw new IllegalStateException("Cherry-Pick: " + r.getStatus());
                }
            }
            return sb.toString().strip();
        });
    }

    @Tool(name = "revert", description = "Erzeugt einen Commit, der die Änderungen eines Commits rückgängig macht (die "
            + "Historie bleibt erhalten). Statt `git revert` in der Shell verwenden." + ShellHints.GIT)
    public String revert(
            @ToolParam(required = false, description = REPO_PARAM) String repository,
            @ToolParam(description = "Rückgängig zu machender Commit") String commit) {
        return git.withWrite(repository, (g, root) -> {
            Repository repo = g.getRepository();
            requireSafe(repo);
            RevCommit target = GitSupport.commit(repo, commit);
            RevertCommand cmd = g.revert().include(target);
            RevCommit c = cmd.call();
            if (c != null) {
                return "Revert-Commit " + GitSupport.shortId(c) + ": " + c.getShortMessage();
            }
            if (cmd.getFailingResult() != null) {
                throw new IllegalStateException("Revert nicht möglich – lokale Änderungen würden überschrieben: "
                        + failing(cmd.getFailingResult().getFailingPaths()) + ". Zuerst committen oder git_stash.");
            }
            return "Revert von " + GitSupport.shortId(target) + " hat Konflikte." + conflicts(g) + CONFLICT_HINT;
        });
    }

    @Tool(name = "continue", description = "Setzt einen angehaltenen Rebase, Merge, Cherry-Pick oder Revert fort, nachdem "
            + "alle Konflikte gelöst und gestaged sind (git_stage). Statt `git rebase --continue`/`git merge --continue`"
            + "/`git cherry-pick --continue` in der Shell verwenden." + ShellHints.GIT)
    public String continueOperation(
            @ToolParam(required = false, description = REPO_PARAM) String repository,
            @ToolParam(required = false, description = "Commit-Nachricht (Merge/Cherry-Pick/Revert); leer = vorbereitete") String message) {
        return git.withWrite(repository, (g, root) -> {
            Repository repo = g.getRepository();
            RepositoryState state = repo.getRepositoryState();
            if (state == RepositoryState.SAFE) {
                return "Es läuft kein Merge, Rebase, Cherry-Pick oder Revert.";
            }
            if (state.isRebasing()) {
                return describe(g.rebase().setOperation(RebaseCommand.Operation.CONTINUE).call(), repo.getBranch(), null);
            }
            var conflicting = g.status().call().getConflicting();
            if (!conflicting.isEmpty()) {
                throw new IllegalStateException("Noch ungelöste Konflikte – bearbeiten und git_stage:\n  "
                        + String.join("\n  ", new TreeSet<>(conflicting)));
            }
            String msg = message != null && !message.isBlank() ? message.strip() : repo.readMergeCommitMsg();
            if (msg == null || msg.isBlank()) {
                throw new IllegalArgumentException("Keine vorbereitete Commit-Nachricht – 'message' angeben.");
            }
            RevCommit c = g.commit().setMessage(msg.strip()).call();
            return GitSupport.describe(state).replaceAll(" \\(.*", "") + " abgeschlossen: Commit " + GitSupport.shortId(c) + "  " + c.getShortMessage();
        });
    }

    @Tool(name = "abort", description = "Bricht einen angehaltenen Rebase, Merge, Cherry-Pick oder Revert ab und stellt den "
            + "Stand davor wieder her (Konfliktlösungen gehen verloren). Statt `git rebase --abort`/`git merge --abort`/"
            + "`git cherry-pick --abort` in der Shell verwenden." + ShellHints.GIT)
    public String abort(@ToolParam(required = false, description = REPO_PARAM) String repository) {
        return git.withWrite(repository, (g, root) -> {
            Repository repo = g.getRepository();
            RepositoryState state = repo.getRepositoryState();
            if (state == RepositoryState.SAFE) {
                return "Es läuft kein Merge, Rebase, Cherry-Pick oder Revert.";
            }
            if (state.isRebasing()) {
                g.rebase().setOperation(RebaseCommand.Operation.ABORT).call();
                return "Rebase abgebrochen – " + repo.getBranch() + " ist auf dem Stand davor.";
            }
            g.reset().setMode(ResetCommand.ResetType.HARD).setRef(Constants.HEAD).call();
            repo.writeMergeHeads(null);
            repo.writeCherryPickHead(null);
            repo.writeRevertHead(null);
            repo.writeMergeCommitMsg(null);
            return GitSupport.describe(state).replaceAll(" mit Konflikten| \\(.*", "") + " abgebrochen – " + repo.getBranch() + " ist auf dem Stand davor ("
                    + GitSupport.shortId(repo.resolve(Constants.HEAD)) + ").";
        });
    }

    // ------------------------------------------------------------------ Hilfen

    private static void requireSafe(Repository repo) {
        RepositoryState state = repo.getRepositoryState();
        if (state != RepositoryState.SAFE) {
            throw new IllegalStateException("Es läuft bereits: " + GitSupport.describe(state)
                    + " – zuerst git_continue oder git_abort.");
        }
    }

    private static String describe(RebaseResult r, String branch, String onto) throws IOException {
        return switch (r.getStatus()) {
            case OK -> branch + (onto == null ? " fertig rebased." : " auf " + onto + " rebased.");
            case UP_TO_DATE -> branch + " ist bereits auf " + (onto == null ? "der Basis" : onto) + ".";
            case FAST_FORWARD -> branch + " per Fast-Forward auf " + (onto == null ? "die Basis" : onto) + " gebracht.";
            case STOPPED, CONFLICTS -> "Rebase angehalten bei Commit "
                    + (r.getCurrentCommit() == null ? "?" : GitSupport.shortId(r.getCurrentCommit()) + " ("
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

    private static String conflicts(Git g) throws GitAPIException {
        Collection<String> c = g.status().call().getConflicting();
        return c.isEmpty() ? "" : "\nKonflikte in:\n  " + String.join("\n  ", new TreeSet<>(c));
    }

    private static String failing(Map<String, ResolveMerger.MergeFailureReason> paths) {
        return paths == null || paths.isEmpty() ? "?" : String.join(", ", new TreeSet<>(paths.keySet()));
    }
}
