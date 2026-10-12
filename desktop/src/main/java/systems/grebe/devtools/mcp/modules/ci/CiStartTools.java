package systems.grebe.devtools.mcp.modules.ci;

import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.modules.ci.spi.CiSystem;

import static systems.grebe.devtools.mcp.modules.ci.CiTools.PROJECT;
import static systems.grebe.devtools.mcp.modules.ci.CiTools.PROVIDER;
import static systems.grebe.devtools.mcp.modules.ci.CiTools.REPOSITORY;
import static systems.grebe.devtools.mcp.modules.ci.CiTools.blankToNull;

/** Builds starten (Schalter {@code allowStart}). */
public class CiStartTools {

    private final CiEnvironment env;

    CiStartTools(CiEnvironment env) {
        this.env = env;
    }

    @Tool(name = "start", description = "Startet einen Build – Jenkins-Job (Multibranch: Branch-Job), GitLab-Pipeline bzw. "
            + "GitHub-Workflow (workflow_dispatch) – mit Branch und Parametern. Nur auf Anweisung des Nutzers; den neuen "
            + "Build danach mit ci_list/ci_get verfolgen." + ShellHints.CI)
    public String start(
            @ToolParam(required = false, description = "Branch oder Tag; 'current' = aktueller Branch des lokalen "
                    + "Repositories. Leer = Standard-Branch des Projekts (Jenkins: ohne Branch-Parameter).") String branch,
            @ToolParam(required = false, description = "GitHub: Workflow (ID, Dateiname wie build.yml oder Name aus "
                    + "ci_workflows) – Pflicht. Jenkins/GitLab: leer.") String workflow,
            @ToolParam(required = false, description = "Build-Parameter (Jenkins), Pipeline-Variablen (GitLab) bzw. "
                    + "Workflow-Inputs (GitHub) als Name → Wert") Map<String, String> parameters,
            @ToolParam(required = false, description = REPOSITORY) String repository,
            @ToolParam(required = false, description = PROJECT) String project,
            @ToolParam(required = false, description = PROVIDER) String provider) {
        CiEnvironment.Target t = env.target(provider, repository, project, null);
        env.checkWrite(t, null, "Starten");
        return CiTools.written(t.system().start(t.project(), new CiSystem.StartRequest(CiTools.branch(t, branch),
                blankToNull(workflow), parameters)));
    }
}
