package systems.grebe.devtools.mcp.modules.graph;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.StatusCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
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

    /**
     * Lokale Branches mit ihrem Commit (voll) und der ausgecheckte Branch – was {@code GraphAutoIndexer} beobachtet.
     *
     * @param branch ausgecheckter Branch, bei losgelöstem HEAD {@code null}
     * @param head   Commit von HEAD, {@code null} ohne Commits
     */
    record Heads(String branch, String head, Map<String, String> branches) {

        /** {@code null}, wenn das Projekt nicht in einem Git-Repository liegt. */
        static Heads of(Path root) {
            FileRepositoryBuilder builder = new FileRepositoryBuilder().findGitDir(root.toFile());
            if (builder.getGitDir() == null) {
                return null;
            }
            try (Repository repo = builder.setMustExist(true).build()) {
                ObjectId id = repo.resolve(Constants.HEAD);
                String full = repo.getFullBranch();
                String branch = full != null && full.startsWith(Constants.R_HEADS) ? Repository.shortenRefName(full)
                        : null;
                Map<String, String> branches = new TreeMap<>();
                for (Ref ref : repo.getRefDatabase().getRefsByPrefix(Constants.R_HEADS)) {
                    if (ref.getObjectId() != null) {
                        branches.put(ref.getName().substring(Constants.R_HEADS.length()), ref.getObjectId().name());
                    }
                }
                return new Heads(branch, id == null ? null : id.name(), branches);
            } catch (IOException | IllegalArgumentException e) {
                return null;
            }
        }
    }

    /**
     * Keine Änderungen an Java-Dateien im Arbeitsverzeichnis gegenüber HEAD (auch keine neuen) – dann entspricht der
     * Graph des ausgecheckten Branches genau seinem Commit.
     */
    static boolean javaClean(Path root) {
        FileRepositoryBuilder builder = new FileRepositoryBuilder().findGitDir(root.toFile());
        if (builder.getGitDir() == null) {
            return false;
        }
        try (Repository repo = builder.setMustExist(true).build(); Git git = new Git(repo)) {
            StatusCommand cmd = git.status();
            Path work = repo.getWorkTree().toPath().toAbsolutePath().normalize();
            String rel = work.relativize(root.toAbsolutePath().normalize()).toString().replace('\\', '/');
            if (!rel.isEmpty()) {
                cmd.addPath(rel);
            }
            Status st = cmd.call();
            return Stream.of(st.getAdded(), st.getChanged(), st.getModified(), st.getMissing(),
                            st.getRemoved(), st.getUntracked(), st.getConflicting())
                    .flatMap(Set::stream).noneMatch(p -> p.endsWith(".java"));
        } catch (IOException | GitAPIException | RuntimeException e) {
            return false;
        }
    }
}
