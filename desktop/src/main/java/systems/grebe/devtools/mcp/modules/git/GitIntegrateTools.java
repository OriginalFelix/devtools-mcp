package systems.grebe.devtools.mcp.modules.git;

import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.RebaseResult;
import org.eclipse.jgit.api.RevertCommand;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;

/**
 * Integrieren (Schalter {@code allowIntegrate}): merge, rebase, revert. Lokal über JGit – kein Netzwerk, kein Push.
 * Angehaltene Operationen setzen {@link GitResolveTools} fort; Cherry-Pick liegt in {@link GitCherryPickTools}.
 */
public class GitIntegrateTools {

    private static final String REPO_PARAM = "Repository-Name (Ordnername) oder Pfad; leer = Standard-Repository";

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
            GitSupport.requireSafe(repo);
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
                        + GitSupport.CONFLICT_HINT;
                case ABORTED -> "Kein Fast-Forward möglich (ff-only) – " + target + " und " + branch.trim()
                        + " sind auseinandergelaufen; mode=ff/no-ff oder git_rebase.";
                case FAILED -> throw new IllegalStateException("Merge nicht möglich – lokale Änderungen würden "
                        + "überschrieben: " + GitSupport.failing(r.getFailingPaths()) + ". Zuerst committen oder git_stash.");
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
            GitSupport.requireSafe(repo);
            String branch = repo.getBranch();
            RebaseResult r = g.rebase().setUpstream(GitSupport.resolveRev(repo, onto)).call();
            return GitSupport.describe(r, branch, onto.trim());
        });
    }

    @Tool(name = "revert", description = "Erzeugt einen Commit, der die Änderungen eines Commits rückgängig macht (die "
            + "Historie bleibt erhalten). Statt `git revert` in der Shell verwenden." + ShellHints.GIT)
    public String revert(
            @ToolParam(required = false, description = REPO_PARAM) String repository,
            @ToolParam(description = "Rückgängig zu machender Commit") String commit) {
        return git.withWrite(repository, (g, root) -> {
            Repository repo = g.getRepository();
            GitSupport.requireSafe(repo);
            RevCommit target = GitSupport.commit(repo, commit);
            RevertCommand cmd = g.revert().include(target);
            RevCommit c = cmd.call();
            if (c != null) {
                return "Revert-Commit " + GitSupport.shortId(c) + ": " + c.getShortMessage();
            }
            if (cmd.getFailingResult() != null) {
                throw new IllegalStateException("Revert nicht möglich – lokale Änderungen würden überschrieben: "
                        + GitSupport.failing(cmd.getFailingResult().getFailingPaths()) + ". Zuerst committen oder git_stash.");
            }
            return "Revert von " + GitSupport.shortId(target) + " hat Konflikte." + GitSupport.conflicts(g) + GitSupport.CONFLICT_HINT;
        });
    }
}
