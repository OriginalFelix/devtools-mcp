package systems.grebe.devtools.mcp.modules.git;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.jgit.api.CommitCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.RebaseCommand;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
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
            requireResumable(state);
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
            requireResumable(state);
            if (state.isRebasing()) {
                g.rebase().setOperation(RebaseCommand.Operation.ABORT).call();
                return "Rebase abgebrochen – " + repo.getBranch() + " ist auf dem Stand davor.";
            }
            // Wie git's „--abort“ (reset --merge): nicht gestagte Änderungen an Dateien, die die Operation nicht
            // angefasst hat, bleiben erhalten – ein harter Reset würde sie still verwerfen.
            Map<String, byte[]> kept = unstagedChanges(g, root);
            try {
                g.reset().setMode(ResetCommand.ResetType.HARD).setRef(Constants.HEAD).call();
                repo.writeMergeHeads(null);
                repo.writeCherryPickHead(null);
                repo.writeRevertHead(null);
                repo.writeMergeCommitMsg(null);
            } finally {
                restore(root, kept);
            }
            return GitSupport.describe(state).replaceAll(" mit Konflikten| \\(.*", "") + " abgebrochen – " + repo.getBranch() + " ist auf dem Stand davor ("
                    + GitSupport.shortId(repo.resolve(Constants.HEAD)) + ")."
                    + (kept.isEmpty() ? "" : " Nicht gestagte Änderungen an " + kept.size() + " Datei(en) beibehalten.");
        });
    }

    /** Nur Merge, Cherry-Pick, Revert und Rebase lassen sich hier fortsetzen/abbrechen – Bisect und {@code git am} nicht. */
    private static void requireResumable(RepositoryState state) {
        boolean ok = switch (state) {
            case MERGING, MERGING_RESOLVED, CHERRY_PICKING, CHERRY_PICKING_RESOLVED, REVERTING, REVERTING_RESOLVED,
                 REBASING, REBASING_REBASING, REBASING_MERGE, REBASING_INTERACTIVE -> true;
            default -> false;
        };
        if (!ok) {
            throw new IllegalStateException("Es läuft: " + GitSupport.describe(state)
                    + " – nicht über git_continue/git_abort; in der Shell beenden (git bisect reset bzw. git am --abort).");
        }
    }

    /**
     * Nicht gestagte Änderungen (geändert/gelöscht) an Dateien ohne Konflikt und ohne Merge-Ergebnis im Index:
     * Dort entspricht HEAD dem Index vor der Operation, der Inhalt (null = gelöscht) lässt sich also nach dem Reset
     * unverändert zurückschreiben.
     */
    private static Map<String, byte[]> unstagedChanges(Git g, Path root) throws GitAPIException, IOException {
        Status st = g.status().call();
        Set<String> keep = new TreeSet<>(st.getModified());
        keep.addAll(st.getMissing());
        keep.removeAll(st.getConflicting());
        keep.removeAll(st.getChanged());
        keep.removeAll(st.getAdded());
        keep.removeAll(st.getRemoved());
        Map<String, byte[]> saved = new LinkedHashMap<>();
        for (String path : keep) {
            Path file = root.resolve(path);
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                saved.put(path, null);
            } else if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                saved.put(path, Files.readAllBytes(file));
            } else {
                throw new IllegalStateException("Abbruch würde nicht gestagte Änderungen an '" + path
                        + "' (Link/Ordner) verwerfen – erst sichern oder git_stage/git_commit.");
            }
        }
        return saved;
    }

    private static void restore(Path root, Map<String, byte[]> saved) throws IOException {
        for (Map.Entry<String, byte[]> e : saved.entrySet()) {
            Path file = root.resolve(e.getKey());
            if (e.getValue() == null) {
                Files.deleteIfExists(file);
            } else {
                Files.write(file, e.getValue());
            }
        }
    }
}
