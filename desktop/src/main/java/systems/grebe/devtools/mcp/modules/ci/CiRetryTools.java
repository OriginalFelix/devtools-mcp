package systems.grebe.devtools.mcp.modules.ci;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;

import static systems.grebe.devtools.mcp.modules.ci.CiTools.PROJECT;
import static systems.grebe.devtools.mcp.modules.ci.CiTools.PROVIDER;
import static systems.grebe.devtools.mcp.modules.ci.CiTools.REPOSITORY;

/** Builds wiederholen (Schalter {@code allowRetry}). */
public class CiRetryTools {

    private final CiEnvironment env;

    CiRetryTools(CiEnvironment env) {
        this.env = env;
    }

    @Tool(name = "retry", description = "Wiederholt einen Build: standardmäßig nur die fehlgeschlagenen bzw. abgebrochenen "
            + "Jobs (GitLab, GitHub), mit all=true den ganzen Lauf. Jenkins startet den Job mit denselben Parametern neu. "
            + "Nur auf Anweisung des Nutzers." + ShellHints.CI)
    public String retry(
            @ToolParam(description = "Build: Nummer bzw. ID, voller Schlüssel oder URL (ausdrücklich, kein Standard)") String build,
            @ToolParam(required = false, description = "true = ganzen Lauf wiederholen statt nur der fehlgeschlagenen Jobs") Boolean all,
            @ToolParam(required = false, description = REPOSITORY) String repository,
            @ToolParam(required = false, description = PROJECT) String project,
            @ToolParam(required = false, description = PROVIDER) String provider) {
        if (build == null || build.isBlank()) {
            throw new IllegalArgumentException("Build fehlt ('build') – beim Wiederholen ausdrücklich angeben.");
        }
        CiEnvironment.Target t = env.target(provider, repository, project, build);
        env.checkWrite(t, build, "Wiederholen");
        return CiTools.written(t.system().retry(build.trim(), t.project(), !Boolean.TRUE.equals(all)));
    }
}
