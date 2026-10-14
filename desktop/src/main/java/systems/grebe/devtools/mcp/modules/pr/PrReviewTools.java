package systems.grebe.devtools.mcp.modules.pr;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.Signature;
import systems.grebe.devtools.mcp.core.SignatureModule;
import systems.grebe.devtools.mcp.modules.pr.spi.GitServer.Verdict;

import static systems.grebe.devtools.mcp.modules.pr.PrTools.PROJECT;
import static systems.grebe.devtools.mcp.modules.pr.PrTools.PROVIDER;
import static systems.grebe.devtools.mcp.modules.pr.PrTools.REPOSITORY;
import static systems.grebe.devtools.mcp.modules.pr.PrTools.blankToNull;

/** Review-Urteil abgeben: freigeben oder Änderungen anfordern (Schalter {@code allowReview}). */
public class PrReviewTools {

    private static final String PR_EXPLICIT = "Pull Request: Nummer, voller Schlüssel oder URL (ausdrücklich, kein Standard)";

    private final PrEnvironment env;

    PrReviewTools(PrEnvironment env) {
        this.env = env;
    }

    @Tool(name = "approve", description = "Gibt einen Pull/Merge Request als angemeldeter Benutzer frei (Approve), "
            + "optional mit Kommentar. Nur auf ausdrücklichen Auftrag des Nutzers und nach pr_get/pr_diff; eigene Pull "
            + "Requests lassen die Server meist nicht freigeben." + ShellHints.PR)
    public String approve(
            @ToolParam(description = PR_EXPLICIT) String pr,
            @ToolParam(required = false, description = "Kommentar zur Freigabe (Markdown); leer = ohne Kommentar") String body,
            @ToolParam(required = false, description = REPOSITORY) String repository,
            @ToolParam(required = false, description = PROJECT) String project,
            @ToolParam(required = false, description = PROVIDER) String provider,
            @ToolParam(required = false, description = Signature.MODEL_PARAM) String aiModel,
            ToolContext toolContext) {
        String text = blankToNull(body) == null ? null
                : env.commentBody(body, SignatureModule.modelName(aiModel, toolContext));
        return review(pr, Verdict.APPROVE, text, repository, project, provider, "Freigeben");
    }

    @Tool(name = "changes_required", description = "Fordert als angemeldeter Benutzer Änderungen an einem Pull/Merge "
            + "Request an (Request changes / Needs work) – mit Begründung, was zu ändern ist. Einzelne Stellen vorher "
            + "mit pr_comment an Datei und Zeile anmerken. Nur auf ausdrücklichen Auftrag des Nutzers. GitLab setzt den "
            + "Status über die Quick Action /submit_review (ältere Versionen: nur Kommentar)." + ShellHints.PR)
    public String changesRequired(
            @ToolParam(description = PR_EXPLICIT) String pr,
            @ToolParam(description = "Begründung: was geändert werden muss (Markdown)") String body,
            @ToolParam(required = false, description = REPOSITORY) String repository,
            @ToolParam(required = false, description = PROJECT) String project,
            @ToolParam(required = false, description = PROVIDER) String provider,
            @ToolParam(required = false, description = Signature.MODEL_PARAM) String aiModel,
            ToolContext toolContext) {
        if (blankToNull(body) == null) {
            throw new IllegalArgumentException("Begründung fehlt ('body') – angeben, was geändert werden muss.");
        }
        return review(pr, Verdict.REQUEST_CHANGES, env.commentBody(body, SignatureModule.modelName(aiModel, toolContext)),
                repository, project, provider, "Änderungen anfordern");
    }

    private String review(String pr, Verdict verdict, String body, String repository, String project, String provider,
                          String action) {
        if (pr == null || pr.isBlank()) {
            throw new IllegalArgumentException("Pull Request fehlt ('pr') – für ein Review-Urteil ausdrücklich angeben.");
        }
        PrEnvironment.Target t = env.target(provider, repository, project, pr);
        env.checkWrite(t, pr, action);
        return PrTools.written(t.server().review(pr.trim(), t.project(), verdict, body));
    }
}
