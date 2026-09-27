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
