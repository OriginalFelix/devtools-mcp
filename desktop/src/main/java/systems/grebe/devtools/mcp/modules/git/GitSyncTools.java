package systems.grebe.devtools.mcp.modules.git;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.eclipse.jgit.lib.BranchConfig;
import org.eclipse.jgit.lib.BranchTrackingStatus;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.Text;

/**
 * Remote-Abgleich (Schalter {@code allowSync}): fetch, pull, push über das installierte git ({@link GitCli}).
 * Nie Force-Push; geschützte Branches ({@code protectedBranches}) werden nicht gepusht.
 */
public class GitSyncTools {

    private static final String REPO_PARAM = "Repository-Name (Ordnername) oder Pfad; leer = Standard-Repository";

    private final GitSupport git;

    GitSyncTools(GitSupport git) {
        this.git = git;
    }

    @Tool(name = "fetch", description = "Holt neue Commits, Branches und Tags vom Remote (ändert keine lokalen Branches) "
            + "und zeigt danach, wie weit der aktuelle Branch voraus/zurück ist. Statt `git fetch` in der Shell verwenden."
            + ShellHints.GIT)
    public String fetch(
            @ToolParam(required = false, description = REPO_PARAM) String repository,
            @ToolParam(required = false, description = "Remote; leer = alle Remotes") String remote,
            @ToolParam(required = false, description = "true = auf dem Remote gelöschte Branches auch lokal entfernen") Boolean prune) {
        return git.withWrite(repository, (g, root) -> {
            List<String> args = new ArrayList<>(List.of("fetch", "--tags"));
            if (Boolean.TRUE.equals(prune)) {
                args.add("--prune");
            }
            args.add(remote == null || remote.isBlank() ? "--all" : name(remote, "Remote"));
            String out = GitCli.run(root, git.networkTimeout(), "git fetch", args);
            return Text.limitLines((out.isBlank() ? "Fetch: nichts Neues." : "Fetch:\n" + out) + "\n"
                    + tracking(g.getRepository()), git.maxLines());
        });
    }

    @Tool(name = "pull", description = "Aktualisiert den aktuellen Branch vom Upstream: ff-only (Standard; nur wenn keine "
            + "eigenen Commits im Weg sind), rebase (eigene Commits obenauf) oder merge (Merge-Commit). Bei Konflikten: "
            + "git_status, lösen, git_stage, git_continue – oder git_abort. Statt `git pull` in der Shell verwenden."
            + ShellHints.GIT)
    public String pull(
            @ToolParam(required = false, description = REPO_PARAM) String repository,
            @ToolParam(required = false, description = "ff-only (Standard), rebase oder merge") String mode,
            @ToolParam(required = false, description = "Remote; leer = Upstream des Branches") String remote,
            @ToolParam(required = false, description = "Remote-Branch; leer = Upstream des Branches") String branch) {
        String m = mode == null || mode.isBlank() ? "ff-only" : mode.trim().toLowerCase(Locale.ROOT);
        String flag = switch (m) {
            case "ff-only", "ff" -> "--ff-only";
            case "rebase" -> "--rebase";
            case "merge" -> "--no-rebase";
            default -> throw new IllegalArgumentException("Unbekannter Modus '" + mode + "' – erlaubt: ff-only, rebase, merge.");
        };
        return git.withWrite(repository, (g, root) -> {
            List<String> args = new ArrayList<>(List.of("pull", flag));
            if (remote != null && !remote.isBlank()) {
                args.add(name(remote, "Remote"));
                if (branch != null && !branch.isBlank()) {
                    args.add(name(branch, "Branch"));
                }
            } else if (branch != null && !branch.isBlank()) {
                throw new IllegalArgumentException("'branch' braucht auch 'remote'.");
            }
            String out = GitCli.run(root, git.networkTimeout(), "git pull", args);
            return Text.limitLines(out + "\n" + tracking(g.getRepository()), git.maxLines());
        });
    }

    @Tool(name = "push", description = "Pusht einen lokalen Branch (Standard: den aktuellen) auf das Remote und setzt den "
            + "Upstream; optional einen Tag. Nie Force-Push – lehnt das Remote ab, zuerst git_pull. Geschützte Branches "
            + "(Standard main/master) werden nicht gepusht. Nur auf Anweisung des Nutzers."
            + " Statt `git push` in der Shell verwenden." + ShellHints.GIT)
    public String push(
            @ToolParam(required = false, description = REPO_PARAM) String repository,
            @ToolParam(required = false, description = "Branch; leer = aktueller Branch") String branch,
            @ToolParam(required = false, description = "Remote; leer = Upstream-Remote bzw. origin") String remote,
            @ToolParam(required = false, description = "Statt eines Branches diesen Tag pushen") String tag) {
        return git.withWrite(repository, (g, root) -> {
            Repository repo = g.getRepository();
            String current = repo.getFullBranch() != null && repo.getFullBranch().startsWith(Constants.R_HEADS)
                    ? repo.getBranch() : null;
            if (tag != null && !tag.isBlank()) {
                String t = name(tag, "Tag");
                if (repo.exactRef(Constants.R_TAGS + t) == null) {
                    throw new IllegalArgumentException("Tag '" + t + "' existiert lokal nicht (git_tags).");
                }
                String r = remote == null || remote.isBlank() ? remoteOf(repo, current) : name(remote, "Remote");
                String out = GitCli.run(root, git.networkTimeout(), "git push",
                        List.of("push", "--porcelain", r, Constants.R_TAGS + t + ":" + Constants.R_TAGS + t));
                return "Tag " + t + " auf " + r + " gepusht.\n" + Text.limitLines(out, 20);
            }
            String b = branch == null || branch.isBlank() ? current : name(branch, "Branch");
            if (b == null) {
                throw new IllegalStateException("Kein Branch ausgecheckt (losgelöster HEAD) – 'branch' angeben.");
            }
            if (repo.exactRef(Constants.R_HEADS + b) == null) {
                throw new IllegalArgumentException("Lokaler Branch '" + b + "' existiert nicht.");
            }
            if (git.isProtected(b)) {
                throw new IllegalStateException("'" + b + "' ist geschützt (Modul Git → 'Nie pushen auf') – Änderungen "
                        + "über einen eigenen Branch und Pull Request einbringen oder den Nutzer selbst pushen lassen.");
            }
            String r = remote == null || remote.isBlank() ? remoteOf(repo, b) : name(remote, "Remote");
            String out = GitCli.run(root, git.networkTimeout(), "git push",
                    List.of("push", "--porcelain", "-u", r, Constants.R_HEADS + b + ":" + Constants.R_HEADS + b));
            return "Branch " + b + " auf " + r + " gepusht.\n" + Text.limitLines(out, 20) + "\n" + tracking(repo);
        });
    }

    /** Upstream-Remote des Branches, sonst {@code origin}. */
    private static String remoteOf(Repository repo, String branch) {
        if (branch != null) {
            String r = new BranchConfig(repo.getConfig(), branch).getRemote();
            if (r != null && !r.isBlank()) {
                return r;
            }
        }
        return "origin";
    }

    /** Remote-, Branch- oder Tag-Name; nichts, was git als Option lesen könnte. */
    static String name(String value, String what) {
        String v = value.trim();
        if (v.startsWith("-") || v.contains("..") || !v.matches("[\\w./@+-]+")) {
            throw new IllegalArgumentException("Ungültiger " + what + "-Name '" + value + "'.");
        }
        return v;
    }

    private static String tracking(Repository repo) {
        try {
            String branch = repo.getBranch();
            BranchTrackingStatus t = BranchTrackingStatus.of(repo, branch);
            if (t == null) {
                return "Branch " + branch + ": kein Upstream.";
            }
            return "Branch " + branch + " gegenüber " + Repository.shortenRefName(t.getRemoteTrackingBranch()) + ": "
                    + t.getAheadCount() + " voraus, " + t.getBehindCount() + " zurück.";
        } catch (Exception e) {
            return "";
        }
    }
}
