package systems.grebe.devtools.mcp.modules.graph;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

/**
 * Git-Stand eines Projekts: ausgecheckter Branch, Commit und alle Branches, die es (lokal oder auf einem Remote) gibt.
 * Daran hängt, unter welchem Branch ein Graph gespeichert wird und welche gespeicherten Graphen veraltet sind.
 *
 * @param branch   Kurzname des ausgecheckten Branches; bei losgelöstem HEAD der abgekürzte Commit
 * @param commit   abgekürzter Commit von HEAD, {@code null} ohne Commits
 * @param branches lokale Branches und Remote-Branches ohne Remote-Präfix ({@code origin/feature/x} → {@code feature/x})
 */
record GitState(String branch, String commit, Set<String> branches) {

    /** Git-Stand oder {@code null}, wenn das Projekt nicht in einem Git-Repository liegt. */
    static GitState of(Path root) {
        FileRepositoryBuilder builder = new FileRepositoryBuilder().findGitDir(root.toFile());
        if (builder.getGitDir() == null) {
            return null;
        }
        try (Repository repo = builder.setMustExist(true).build()) {
            ObjectId head = repo.resolve(Constants.HEAD);
            String commit = head == null ? null : head.abbreviate(10).name();
            String full = repo.getFullBranch();
            String branch = full != null && full.startsWith(Constants.R_REFS)
                    ? Repository.shortenRefName(full) : commit;
            Set<String> branches = new TreeSet<>();
            for (Ref ref : repo.getRefDatabase().getRefsByPrefix(Constants.R_HEADS)) {
                branches.add(ref.getName().substring(Constants.R_HEADS.length()));
            }
            for (Ref ref : repo.getRefDatabase().getRefsByPrefix(Constants.R_REMOTES)) {
                String name = ref.getName().substring(Constants.R_REMOTES.length());
                int slash = name.indexOf('/');
                if (slash > 0 && !name.endsWith("/" + Constants.HEAD)) {
                    branches.add(name.substring(slash + 1));
                }
            }
            if (branch != null) {
                branches.add(branch);
            }
            return new GitState(branch, commit, branches);
        } catch (IOException | IllegalArgumentException e) {
            return null; // kaputtes/fremdes Repository: wie ohne Git behandeln
        }
    }

    /** Ob ein gespeicherter Branch-Graph noch zu einem existierenden Branch gehört. */
    boolean exists(String storedBranch) {
        return storedBranch != null && branches.contains(storedBranch);
    }
}
