package systems.grebe.devtools.mcp.modules.git;

import java.util.TreeSet;

import org.eclipse.jgit.api.CommitCommand;
import org.eclipse.jgit.api.RebaseCommand;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.lib.CommitConfig;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryState;
import org.eclipse.jgit.revwalk.RevCommit;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;

/**
 * Angehaltene Operationen fortsetzen oder abbrechen (Schalter {@code allowIntegrate} oder {@code allowCherryPick}):
 * nach Konflikten bei Merge, Rebase, Cherry-Pick und Revert.
 */
public class GitResolveTools {

    private static final String REPO_PARAM = "Repository-Name (Ordnername) oder Pfad; leer = Standard-Repository";

    private final GitSupport git;

    GitResolveTools(GitSupport git) {
        this.git = git;
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
                return GitSupport.describe(g.rebase().setOperation(RebaseCommand.Operation.CONTINUE).call(), repo.getBranch(), null);
            }
            var conflicting = g.status().call().getConflicting();
            if (!conflicting.isEmpty()) {
                throw new IllegalStateException("Noch ungelöste Konflikte – bearbeiten und git_stage:\n  "
                        + String.join("\n  ", new TreeSet<>(conflicting)));
            }
            CommitCommand commit = g.commit();
            if (message != null && !message.isBlank()) {
                commit.setMessage(message.strip());
            } else {
                String prepared = repo.readMergeCommitMsg();
                if (prepared == null || prepared.isBlank()) {
                    throw new IllegalArgumentException("Keine vorbereitete Commit-Nachricht – 'message' angeben.");
                }
                // Die vorbereitete Nachricht enthält Kommentarzeilen („# Conflicts: …“) – wie git entfernen.
                commit.setMessage(prepared).setCleanupMode(CommitConfig.CleanupMode.STRIP);
            }
            ObjectId picked = repo.readCherryPickHead();
            if (picked != null) {
                // Wie git cherry-pick --continue: Autor des übernommenen Commits behalten.
                commit.setAuthor(GitSupport.commit(repo, picked.name()).getAuthorIdent());
            }
            RevCommit c = commit.call();
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
}
