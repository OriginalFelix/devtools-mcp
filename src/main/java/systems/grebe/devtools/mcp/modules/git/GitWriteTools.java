package systems.grebe.devtools.mcp.modules.git;

import java.util.List;

import org.eclipse.jgit.api.AddCommand;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.Workspaces;
import systems.grebe.devtools.mcp.core.ShellHints;

/** Schreibende Git-Tools (nur registriert, wenn in der Konfiguration erlaubt). Push wird bewusst nicht angeboten. */
public class GitWriteTools {

    private static final String REPO_PARAM = "Repository-Name (Ordnername) oder Pfad; leer = Standard-Repository";

    private final GitSupport git;

    GitWriteTools(GitSupport git) {
        this.git = git;
    }

    @Tool(name = "create_branch", description = "Legt einen neuen lokalen Branch an, optional ab einer Startrevision, und wechselt optional darauf."
            + " Statt `git checkout -b`/`git switch -c` in der Shell verwenden." + ShellHints.GIT)
    public String createBranch(
            @ToolParam(required = false, description = REPO_PARAM) String repository,
            @ToolParam(description = "Name des neuen Branches, z.B. 'feature/login'") String name,
            @ToolParam(required = false, description = "Startrevision; Standard HEAD") String startPoint,
            @ToolParam(required = false, description = "Direkt auschecken (Standard true)") Boolean checkout) {
        return git.with(repository, (g, root) -> {
            Repository repo = g.getRepository();
            String start = startPoint == null || startPoint.isBlank() ? "HEAD" : startPoint;
            GitSupport.resolveRev(repo, start);
            if (checkout == null || checkout) {
                g.checkout().setCreateBranch(true).setName(name).setStartPoint(start).call();
                return "Branch '" + name + "' ab " + start + " angelegt und ausgecheckt.";
            }
            g.branchCreate().setName(name).setStartPoint(start).call();
            return "Branch '" + name + "' ab " + start + " angelegt.";
        });
    }

    @Tool(name = "checkout", description = "Wechselt auf einen bestehenden lokalen Branch. Schlägt fehl, wenn lokale Änderungen überschrieben würden."
            + " Statt `git checkout`/`git switch` in der Shell verwenden." + ShellHints.GIT)
    public String checkout(
            @ToolParam(required = false, description = REPO_PARAM) String repository,
            @ToolParam(description = "Branch-Name") String branch) {
        return git.with(repository, (g, root) -> {
            g.checkout().setName(branch).call();
            return "Ausgecheckt: " + g.getRepository().getBranch();
        });
    }

    @Tool(name = "stage", description = "Nimmt Dateien in den Index auf (git add). Ohne Pfade werden alle Änderungen inkl. Löschungen und neuer Dateien gestaged."
            + " Statt `git add` in der Shell verwenden." + ShellHints.GIT)
    public String stage(
            @ToolParam(required = false, description = REPO_PARAM) String repository,
            @ToolParam(required = false, description = "Relative Pfade; leer = alles") List<String> paths) {
        return git.with(repository, (g, root) -> {
            List<String> rels = paths == null || paths.isEmpty() ? List.of(".")
                    : paths.stream().map(p -> Workspaces.relativePath(root, p)).toList();
            AddCommand add = g.add();
            AddCommand remove = g.add().setUpdate(true); // erfasst gelöschte Dateien
            rels.forEach(p -> {
                add.addFilepattern(p);
                remove.addFilepattern(p);
            });
            add.call();
            remove.call();
            Status s = g.status().call();
            return "Gestaged. Im Index: " + (s.getAdded().size() + s.getChanged().size() + s.getRemoved().size())
                    + " Datei(en); nicht gestaged: " + (s.getModified().size() + s.getMissing().size())
                    + "; unversioniert: " + s.getUntracked().size();
        });
    }

    @Tool(name = "unstage", description = "Entfernt Dateien aus dem Index (git reset -- <pfade>); die Änderungen im Arbeitsverzeichnis bleiben erhalten."
            + " Statt `git reset -- <pfade>` in der Shell verwenden." + ShellHints.GIT)
    public String unstage(
            @ToolParam(required = false, description = REPO_PARAM) String repository,
            @ToolParam(required = false, description = "Relative Pfade; leer = alles") List<String> paths) {
        return git.with(repository, (g, root) -> {
            ResetCommand reset = g.reset();
            if (paths == null || paths.isEmpty()) {
                reset.setMode(ResetCommand.ResetType.MIXED);
            } else {
                paths.forEach(p -> reset.addPath(Workspaces.relativePath(root, p)));
            }
            reset.call();
            return "Aus dem Index entfernt.";
        });
    }

    @Tool(name = "commit", description = "Erstellt einen Commit aus dem aktuellen Index. Autor aus der Git-Konfiguration (user.name/user.email). Kein Push."
            + " Statt `git commit` in der Shell verwenden." + ShellHints.GIT)
    public String commit(
            @ToolParam(required = false, description = REPO_PARAM) String repository,
            @ToolParam(description = "Commit-Nachricht (erste Zeile = Betreff)") String message,
            @ToolParam(required = false, description = "true = vorher alle geänderten, bereits versionierten Dateien stagen (wie git commit -a)") Boolean all) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Commit-Nachricht darf nicht leer sein.");
        }
        return git.with(repository, (g, root) -> {
            Status s = g.status().call();
            boolean staged = !s.getAdded().isEmpty() || !s.getChanged().isEmpty() || !s.getRemoved().isEmpty();
            boolean commitAll = Boolean.TRUE.equals(all);
            if (!staged && !(commitAll && (!s.getModified().isEmpty() || !s.getMissing().isEmpty()))) {
                throw new IllegalStateException("Nichts zu committen – zuerst Dateien stagen.");
            }
            RevCommit c = g.commit().setMessage(message.strip()).setAll(commitAll).call();
            return "Commit " + GitSupport.shortId(c) + " auf " + g.getRepository().getBranch() + ": " + c.getShortMessage();
        });
    }
}
