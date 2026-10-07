package systems.grebe.devtools.mcp.modules.git;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.ConnectionTestResult;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolModule;
import systems.grebe.devtools.mcp.core.Workspaces;

/** Git-Integration auf Basis von JGit (kein installiertes git nötig). */
@Component
public class GitModule implements ToolModule {

    static final String REPOSITORIES = "repositories";
    static final String DEFAULT_REPOSITORY = "defaultRepository";
    static final String ALLOW_WRITE = "allowWrite";
    static final String ALLOW_SYNC = "allowSync";
    static final String ALLOW_INTEGRATE = "allowIntegrate";
    static final String ALLOW_DISCARD = "allowDiscard";
    static final String PROTECTED_BRANCHES = "protectedBranches";
    static final String NETWORK_TIMEOUT = "networkTimeoutSeconds";
    static final String MAX_LINES = "maxOutputLines";

    @Override
    public String id() {
        return "git";
    }

    @Override
    public String displayName() {
        return "Git";
    }

    @Override
    public String description() {
        return "Status, Log, Diffs, Blame, Suche, Tags, Stashes, Reflog und Branch-Vergleiche aus lokalen Git-Repositories "
                + "(inkl. Worktrees); je Schalter Branches, Stage/Commit, Stash und Tags, Fetch/Pull/Push, Merge/Rebase/"
                + "Cherry-Pick/Revert sowie Verwerfen und Löschen.";
    }

    @Override
    public String instructions() {
        return """
                Für Git-Aufgaben IMMER diese Tools verwenden, sobald sie angeboten werden – NICHT `git` im Terminal/\
                in der Shell. Das gilt auch für kurze Fragen („welcher Branch?“, „was ist geändert?“) und wenn der \
                Nutzer einen konkreten git-Befehl nennt.

                | Aufgabe | Tool statt Shell |
                |---|---|
                | Repositories, Worktrees, aktueller Branch | `git_list_repositories` (statt `git branch --show-current`, `git worktree list`) |
                | Arbeitsstand, laufender Merge/Rebase | `git_status` (statt `git status`) |
                | Historie, wann kam Code hinein | `git_log` (statt `git log`, `git log -S`) |
                | Änderungen | `git_diff` (statt `git diff`, `git diff --staged`, `git diff a..b`) |
                | Commit ansehen | `git_show_commit` (statt `git show`) |
                | Branches, Tags, Remotes | `git_branches`, `git_tags`, `git_remotes` (statt `git branch -a`, `git tag`, `git remote -v`) |
                | Stashes, Reflog | `git_stash_list`, `git_reflog` (statt `git stash list`, `git reflog`) |
                | Branches vergleichen | `git_compare` (statt `git log a..b`, `git merge-base`, `git rev-list --count`) |
                | Code durchsuchen | `git_grep` (statt `git grep`, `grep -r`) |
                | Zeilenherkunft, Datei in Revision | `git_blame`, `git_file_at_revision` (statt `git blame`, `git show rev:pfad`) |
                | Branch anlegen/wechseln/umbenennen | `git_create_branch`, `git_checkout`, `git_rename_branch` (statt `git checkout -b`, `git switch`, `git branch -m`) |
                | Stagen/Unstagen | `git_stage`, `git_unstage` (statt `git add`, `git reset -- pfad`) |
                | Committen | `git_commit` (statt `git commit`) |
                | Zurücksetzen | `git_reset` (statt `git reset --soft/--mixed/--hard`) |
                | Stash, Tag | `git_stash`, `git_tag` (statt `git stash push/apply/pop`, `git tag`) |
                | Remote abgleichen | `git_fetch`, `git_pull`, `git_push` (statt `git fetch`, `git pull`, `git push`) |
                | Integrieren | `git_merge`, `git_rebase`, `git_cherry_pick`, `git_revert` (statt `git merge`, `git rebase`, `git cherry-pick`, `git revert`) |
                | Konflikte fortsetzen/abbrechen | `git_continue`, `git_abort` (statt `git … --continue`, `git … --abort`) |
                | Verwerfen/Löschen | `git_restore`, `git_delete_branch`, `git_delete_tag`, `git_stash_drop` (statt `git restore`, `git clean`, `git branch -d`, `git tag -d`, `git stash drop`) |

                Vorgehen:
                - Unklar, welches Repository gemeint ist? Zuerst `git_list_repositories` aufrufen und den \
                Namen als `repository` übergeben. Worktrees heißen `<repository>/<ordner>`; ein Pfad darin wird \
                ebenfalls erkannt. Nur dort aufgeführte Repositories sind freigegeben.
                - Liegt ein Repository nicht in dieser Liste („nicht freigegeben“), dem Nutzer das sagen und ihm \
                anbieten, es in der DevTools-App freizugeben; erst auf seinen Wunsch die Shell verwenden.
                - Fehlt eines der Tools aus der Tabelle (z.B. `git_push`), ist sein Schalter in der App aus: \
                `allowWrite` (Branches, Stage, Commit, Stash, Tag), `allowSync` (`git_fetch`, `git_pull`, `git_push`), \
                `allowIntegrate` (Merge, Rebase, Cherry-Pick, Revert, Continue/Abort), `allowDiscard` (Restore, \
                Löschen). Dann NICHT per Shell ausweichen, sondern mit `permissions_request` (z.B. `tool=git_push`, \
                kurze Begründung) beim Nutzer anfragen; lehnt er ab, ihm sagen, was fehlt.
                - Konflikte nach Merge/Rebase/Cherry-Pick: `git_status` zeigt Dateien und Zustand; Dateien bereinigen, \
                `git_stage`, dann `git_continue` – oder `git_abort` für den Ausgangszustand.
                - `git_push` und Verwerfendes (`git_restore`, `git_reset mode=hard`, Löschen) nur auf ausdrücklichen \
                Auftrag. Force-Push gibt es nicht.
                - Shell-`git` nur für das, was hier fehlt (z.B. submodule, bisect, interaktiver Rebase).""";

    }

    @Override
    public boolean enabledByDefault() {
        return true;
    }

    @Override
    public Set<String> sharedDirectoryFields() {
        return Set.of(REPOSITORIES);
    }

    @Override
    public List<ConfigField> configSchema() {
        return List.of(
                ConfigField.of(REPOSITORIES, "Repositories", FieldType.DIRECTORY_LIST)
                        .withHelp("Repository-Verzeichnisse oder Sammelordner (deren direkte Unterordner mit .git "
                                + "werden übernommen). Nur diese und die unter „Freigaben“ global freigegebenen sind "
                                + "für das LLM zugänglich."),
                ConfigField.of(DEFAULT_REPOSITORY, "Standard-Repository", FieldType.STRING)
                        .withHelp("Name (Ordnername) des Repositories, das ohne Angabe verwendet wird."),
                ConfigField.of(ALLOW_WRITE, "Schreibende Operationen erlauben", FieldType.BOOLEAN).withDefault("true")
                        .withHelp("Branch anlegen/umbenennen, Checkout, Stage/Unstage, Commit, Reset (soft/mixed), "
                                + "Stash (push/apply/pop) und Tags anlegen."),
                ConfigField.of(ALLOW_SYNC, "Remote-Abgleich erlauben (fetch, pull, push)", FieldType.BOOLEAN)
                        .withDefault("false").withHelp("Über das installierte git mit den Zugangsdaten des Rechners. "
                                + "Nie Force-Push."),
                ConfigField.of(PROTECTED_BRANCHES, "Nie pushen auf", FieldType.STRING_LIST).withDefault("main\nmaster")
                        .withHelp("Branches, die git_push ablehnt (ein Name je Zeile)."),
                ConfigField.of(NETWORK_TIMEOUT, "Timeout Remote-Abgleich (Sekunden)", FieldType.INT).withDefault("120"),
                ConfigField.of(ALLOW_INTEGRATE, "Integrieren erlauben (merge, rebase, cherry-pick, revert)", FieldType.BOOLEAN)
                        .withDefault("false").withHelp("Inklusive git_continue/git_abort bei Konflikten."),
                ConfigField.of(ALLOW_DISCARD, "Verwerfen und Löschen erlauben", FieldType.BOOLEAN).withDefault("false")
                        .withHelp("git_restore (lokale Änderungen verwerfen), git_reset mode=hard, Branches/Tags/Stashes "
                                + "löschen – nicht rückgängig zu machen."),
                ConfigField.of(MAX_LINES, "Max. Ausgabezeilen", FieldType.INT).withDefault("1500")
                        .withHelp("Längere Diffs/Dateien werden gekürzt, um den Kontext des LLM zu schonen."));
    }

    @Override
    public List<ToolCallback> createTools(ModuleConfig config) {
        GitSupport git = new GitSupport(config);
        List<ToolCallback> tools = new ArrayList<>(List.of(ToolCallbacks.from(new GitReadTools(git))));
        if (config.getBoolean(ALLOW_WRITE)) {
            tools.addAll(List.of(ToolCallbacks.from(new GitWriteTools(git))));
        }
        if (config.getBoolean(ALLOW_SYNC)) {
            tools.addAll(List.of(ToolCallbacks.from(new GitSyncTools(git))));
        }
        if (config.getBoolean(ALLOW_INTEGRATE)) {
            tools.addAll(List.of(ToolCallbacks.from(new GitIntegrateTools(git))));
        }
        if (config.getBoolean(ALLOW_DISCARD)) {
            tools.addAll(List.of(ToolCallbacks.from(new GitDiscardTools(git))));
        }
        return tools;
    }

    @Override
    public ConnectionTestResult testConnection(ModuleConfig config) {
        List<String> errors = config.validate();
        if (!errors.isEmpty()) {
            return ConnectionTestResult.failed(String.join("\n", errors));
        }
        GitSupport git = new GitSupport(config);
        Workspaces repos = git.repositories();
        if (repos.isEmpty()) {
            return Workspaces.unrestricted()
                    ? ConnectionTestResult.ok("Keine Repositories eingetragen. " + repos.unrestrictedHint())
                    : ConnectionTestResult.failed("In den angegebenen Verzeichnissen wurde kein Git-Repository gefunden.");
        }
        List<String> lines = new ArrayList<>(repos.describe());
        git.worktrees().forEach(w -> lines.add(w.name() + " -> " + w.dir() + " (Worktree)"));
        return ConnectionTestResult.ok(repos.all().size() + " Repository(s) gefunden:\n" + String.join("\n", lines));
    }

    static boolean isRepository(Path dir) {
        return Files.exists(dir.resolve(".git"));
    }
}
