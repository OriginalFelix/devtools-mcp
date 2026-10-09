package systems.grebe.devtools.mcp.modules.ci;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;

import static systems.grebe.devtools.mcp.modules.ci.CiTools.PROJECT;
import static systems.grebe.devtools.mcp.modules.ci.CiTools.PROVIDER;
import static systems.grebe.devtools.mcp.modules.ci.CiTools.REPOSITORY;

/** Builds abbrechen (Schalter {@code allowCancel}). */
public class CiCancelTools {

    private final CiEnvironment env;

    CiCancelTools(CiEnvironment env) {
        this.env = env;
    }

    @Tool(name = "cancel", description = "Bricht einen laufenden oder wartenden Build bzw. eine Pipeline/einen "
            + "Workflow-Lauf ab. Nur auf Anweisung des Nutzers." + ShellHints.CI)
    public String cancel(
            @ToolParam(description = "Build: Nummer bzw. ID, voller Schlüssel oder URL (ausdrücklich, kein Standard)") String build,
            @ToolParam(required = false, description = REPOSITORY) String repository,
            @ToolParam(required = false, description = PROJECT) String project,
            @ToolParam(required = false, description = PROVIDER) String provider) {
        if (build == null || build.isBlank()) {
            throw new IllegalArgumentException("Build fehlt ('build') – beim Abbrechen ausdrücklich angeben.");
        }
        CiEnvironment.Target t = env.target(provider, repository, project, build);
        env.checkWrite(t, build, "Abbrechen");
        return CiTools.written(t.system().cancel(build.trim(), t.project()));
    }
}
