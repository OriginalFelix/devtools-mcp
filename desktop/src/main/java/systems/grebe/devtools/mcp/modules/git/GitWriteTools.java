package systems.grebe.devtools.mcp.modules.git;

import java.util.List;
import java.util.Locale;

import org.eclipse.jgit.api.AddCommand;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.Workspaces;
import systems.grebe.devtools.mcp.core.ShellHints;

/** Schreibende Git-Tools (Schalter {@code allowWrite}). Remote-Abgleich, Integrieren und Verwerfen haben eigene Klassen. */
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
        return git.withWrite(repository, (g, root) -> {
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
        return git.withWrite(repository, (g, root) -> {
            g.checkout().setName(branch).call();
            return "Ausgecheckt: " + g.getRepository().getBranch();
        });
    }

    @Tool(name = "stage", description = "Nimmt Dateien in den Index auf (git add). Ohne Pfade werden alle Änderungen inkl. Löschungen und neuer Dateien gestaged."
            + " Statt `git add` in der Shell verwenden." + ShellHints.GIT)
    public String stage(
            @ToolParam(required = false, description = REPO_PARAM) String repository,
            @ToolParam(required = false, description = "Relative Pfade; leer = alles") List<String> paths) {
        return git.withWrite(repository, (g, root) -> {
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
        return git.withWrite(repository, (g, root) -> {
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
        return git.withWrite(repository, (g, root) -> {
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

    @Tool(name = "rename_branch", description = "Benennt einen lokalen Branch um (Standard: den aktuellen)."
            + " Statt `git branch -m` in der Shell verwenden." + ShellHints.GIT)
    public String renameBranch(
            @ToolParam(required = false, description = REPO_PARAM) String repository,
            @ToolParam(required = false, description = "Bisheriger Name; leer = aktueller Branch") String from,
            @ToolParam(description = "Neuer Name") String to) {
        if (to == null || to.isBlank()) {
            throw new IllegalArgumentException("Neuer Name fehlt ('to').");
        }
        return git.withWrite(repository, (g, root) -> {
            String old = from == null || from.isBlank() ? g.getRepository().getBranch() : from.trim();
            g.branchRename().setOldName(old).setNewName(to.trim()).call();
            return "Branch '" + old + "' in '" + to.trim() + "' umbenannt.";
        });
    }

    @Tool(name = "stash", description = "Stash-Operationen: push (lokale Änderungen beiseitelegen, optional mit "
            + "unversionierten Dateien), apply (wieder anwenden, Stash bleibt), pop (anwenden und entfernen). "
            + "Liste: git_stash_list. Statt `git stash`/`git stash pop` in der Shell verwenden." + ShellHints.GIT)
    public String stash(
            @ToolParam(required = false, description = REPO_PARAM) String repository,
            @ToolParam(description = "push, apply oder pop") String action,
            @ToolParam(required = false, description = "push: Nachricht") String message,
            @ToolParam(required = false, description = "push: auch unversionierte Dateien") Boolean includeUntracked,
            @ToolParam(required = false, description = "apply/pop: Index n aus stash@{n} (Standard 0)") Integer index) {
        String a = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
        return git.withWrite(repository, (g, root) -> switch (a) {
            case "push", "save" -> {
                var cmd = g.stashCreate().setIncludeUntracked(Boolean.TRUE.equals(includeUntracked));
                if (message != null && !message.isBlank()) {
                    cmd.setWorkingDirectoryMessage(message.strip());
                }
                RevCommit c = cmd.call();
                yield c == null ? "Nichts zu stashen – keine lokalen Änderungen."
                        : "Gestasht als stash@{0}: " + c.getShortMessage();
            }
            case "apply", "pop" -> {
                int n = index == null || index < 0 ? 0 : index;
                g.stashApply().setStashRef("stash@{" + n + "}").call();
                if ("pop".equals(a)) {
                    g.stashDrop().setStashRef(n).call();
                    yield "stash@{" + n + "} angewendet und entfernt.";
                }
                yield "stash@{" + n + "} angewendet (bleibt erhalten).";
            }
            default -> throw new IllegalArgumentException("Unbekannte Aktion '" + action + "' – erlaubt: push, apply, pop. "
                    + "Löschen: git_stash_drop.");
        });
    }

    @Tool(name = "tag", description = "Legt einen Tag an – mit Nachricht annotiert, sonst leichtgewichtig – auf HEAD oder "
            + "einer Revision. Kein Push (dafür git_push mit tags=true). Statt `git tag` in der Shell verwenden."
            + ShellHints.GIT)
    public String tag(
            @ToolParam(required = false, description = REPO_PARAM) String repository,
            @ToolParam(description = "Tag-Name, z.B. 'v1.2.0'") String name,
            @ToolParam(required = false, description = "Revision; Standard HEAD") String revision,
            @ToolParam(required = false, description = "Nachricht – macht den Tag annotiert") String message) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Tag-Name fehlt ('name').");
        }
        return git.withWrite(repository, (g, root) -> {
            Repository repo = g.getRepository();
            RevCommit c = GitSupport.commit(repo, revision);
            boolean annotated = message != null && !message.isBlank();
            var cmd = g.tag().setName(name.trim()).setObjectId(c).setAnnotated(annotated);
            if (annotated) {
                cmd.setMessage(message.strip());
            }
            cmd.call();
            return (annotated ? "Annotierter Tag '" : "Tag '") + name.trim() + "' auf " + GitSupport.shortId(c) + " ("
                    + c.getShortMessage() + ") angelegt.";
        });
    }

    @Tool(name = "reset", description = "Setzt den aktuellen Branch auf eine Revision zurück: soft (Änderungen bleiben "
            + "gestaged), mixed (Standard; Änderungen bleiben im Arbeitsverzeichnis), hard (verwirft alle lokalen "
            + "Änderungen – nur mit Schalter 'Verwerfen'). Commits bleiben über git_reflog auffindbar."
            + " Statt `git reset` in der Shell verwenden." + ShellHints.GIT)
    public String reset(
            @ToolParam(required = false, description = REPO_PARAM) String repository,
            @ToolParam(description = "Ziel, z.B. 'HEAD~1', 'origin/main' oder ein Commit") String revision,
            @ToolParam(required = false, description = "soft, mixed (Standard) oder hard") String mode) {
        String m = mode == null || mode.isBlank() ? "mixed" : mode.trim().toLowerCase(Locale.ROOT);
        ResetCommand.ResetType type = switch (m) {
            case "soft" -> ResetCommand.ResetType.SOFT;
            case "mixed" -> ResetCommand.ResetType.MIXED;
            case "hard" -> ResetCommand.ResetType.HARD;
            default -> throw new IllegalArgumentException("Unbekannter Modus '" + mode + "' – erlaubt: soft, mixed, hard.");
        };
        if (type == ResetCommand.ResetType.HARD && !git.allowDiscard()) {
            throw new IllegalStateException("reset mode=hard verwirft lokale Änderungen und ist nur mit dem Schalter "
                    + "'Verwerfen und Löschen erlauben' (Modul Git) möglich – soft/mixed verwenden oder den Nutzer fragen.");
        }
        return git.withWrite(repository, (g, root) -> {
            Repository repo = g.getRepository();
            ObjectId before = repo.resolve(Constants.HEAD);
            RevCommit target = GitSupport.commit(repo, revision);
            g.reset().setMode(type).setRef(target.getName()).call();
            return repo.getBranch() + " zurückgesetzt (" + m + ") von " + GitSupport.shortId(before) + " auf "
                    + GitSupport.shortId(target) + " – " + target.getShortMessage()
                    + "\nVorheriger Stand: " + GitSupport.shortId(before) + " (git_reflog)";
        });
    }
}
