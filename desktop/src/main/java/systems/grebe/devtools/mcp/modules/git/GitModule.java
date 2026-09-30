package systems.grebe.devtools.mcp.modules.git;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
        return "Status, Log, Diffs, Blame und Dateistände aus lokalen Git-Repositories; optional Branches anlegen, "
                + "stagen und committen.";
    }

    @Override
    public String instructions() {
        return """
                Für Git-Aufgaben IMMER diese Tools verwenden, sobald sie angeboten werden – NICHT `git` im Terminal/\
                in der Shell. Das gilt auch für kurze Fragen („welcher Branch?“, „was ist geändert?“) und wenn der \
                Nutzer einen konkreten git-Befehl nennt.

                | Aufgabe | Tool statt Shell |
                |---|---|
                | Repositories/aktueller Branch | `git_list_repositories` (statt `git branch --show-current`, `git rev-parse`) |
                | Arbeitsstand | `git_status` (statt `git status`) |
                | Historie | `git_log` (statt `git log`) |
                | Änderungen | `git_diff` (statt `git diff`, `git diff --staged`, `git diff a..b`) |
                | Commit ansehen | `git_show_commit` (statt `git show`) |
                | Branches | `git_branches` (statt `git branch -a`) |
                | Zeilenherkunft | `git_blame` (statt `git blame`) |
                | Datei in Revision | `git_file_at_revision` (statt `git show rev:pfad`) |
                | Branch anlegen/wechseln | `git_create_branch`, `git_checkout` (statt `git checkout -b`, `git switch`) |
                | Stagen/Unstagen | `git_stage`, `git_unstage` (statt `git add`, `git reset --`) |
                | Committen | `git_commit` (statt `git commit`) |

                Vorgehen:
                - Unklar, welches Repository gemeint ist? Zuerst `git_list_repositories` aufrufen und den \
                Ordnernamen als `repository` übergeben. Nur dort aufgeführte Repositories sind freigegeben.
                - Liegt ein Repository nicht in dieser Liste („nicht freigegeben“), dem Nutzer das sagen und ihm \
                anbieten, es in der DevTools-App freizugeben; erst auf seinen Wunsch die Shell verwenden.
                - Schreibende Tools (`git_create_branch`, `git_checkout`, `git_stage`, `git_unstage`, `git_commit`) \
                fehlen, wenn „Schreibende Operationen erlauben“ aus ist – dann nachfragen statt per Shell schreiben.
                - Nur für das, was hier fehlt, darf `git` in der Shell verwendet werden: push, pull, fetch, merge, \
                rebase, stash, tag, cherry-pick, reset auf Commits, remote. Push nie ohne ausdrücklichen Auftrag.""";
    }

    @Override
    public boolean enabledByDefault() {
        return true;
    }

    @Override
    public List<ConfigField> configSchema() {
        return List.of(
                ConfigField.of(REPOSITORIES, "Repositories", FieldType.DIRECTORY_LIST).asRequired()
                        .withHelp("Repository-Verzeichnisse oder Sammelordner (deren direkte Unterordner mit .git "
                                + "werden übernommen). Nur diese sind für das LLM zugänglich."),
                ConfigField.of(DEFAULT_REPOSITORY, "Standard-Repository", FieldType.STRING)
                        .withHelp("Name (Ordnername) des Repositories, das ohne Angabe verwendet wird."),
                ConfigField.of(ALLOW_WRITE, "Schreibende Operationen erlauben", FieldType.BOOLEAN).withDefault("true")
                        .withHelp("Branch anlegen, Checkout, Stage/Unstage und Commit. Push wird nie angeboten."),
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
        return tools;
    }

    @Override
    public ConnectionTestResult testConnection(ModuleConfig config) {
        List<String> errors = config.validate();
        if (!errors.isEmpty()) {
            return ConnectionTestResult.failed(String.join("\n", errors));
        }
        Workspaces repos = new GitSupport(config).repositories();
        if (repos.isEmpty()) {
            return ConnectionTestResult.failed("In den angegebenen Verzeichnissen wurde kein Git-Repository gefunden.");
        }
        return ConnectionTestResult.ok(repos.all().size() + " Repository(s) gefunden:\n"
                + String.join("\n", repos.describe()));
    }

    static boolean isRepository(Path dir) {
        return Files.exists(dir.resolve(".git"));
    }
}
