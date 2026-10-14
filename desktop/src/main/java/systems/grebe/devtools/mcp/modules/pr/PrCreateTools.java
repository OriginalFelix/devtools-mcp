package systems.grebe.devtools.mcp.modules.pr;

import java.util.List;
import java.util.regex.Pattern;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.Signature;
import systems.grebe.devtools.mcp.core.SignatureModule;
import systems.grebe.devtools.mcp.modules.pr.spi.GitServer;

import static systems.grebe.devtools.mcp.modules.pr.PrTools.PR;
import static systems.grebe.devtools.mcp.modules.pr.PrTools.PROJECT;
import static systems.grebe.devtools.mcp.modules.pr.PrTools.PROVIDER;
import static systems.grebe.devtools.mcp.modules.pr.PrTools.REPOSITORY;
import static systems.grebe.devtools.mcp.modules.pr.PrTools.blankToNull;

/** Anlegen (auch als Entwurf), bereit markieren und Bearbeiten (Schalter {@code allowCreate}). */
public class PrCreateTools {

    private final PrEnvironment env;

    PrCreateTools(PrEnvironment env) {
        this.env = env;
    }

    @Tool(name = "create", description = "Legt einen Pull/Merge Request an. Quell-Branch ist standardmäßig der aktuelle "
            + "Branch des lokalen Repositories, Ziel der Standard-Branch. Der Branch muss gepusht sein (pr_push). Titel und "
            + "Beschreibung aus den Commits/Änderungen zusammenfassen. Nur auf Anweisung des Nutzers." + ShellHints.PR)
    public String create(
            @ToolParam(description = "Titel") String title,
            @ToolParam(required = false, description = "Beschreibung (Markdown): was und warum, Testhinweise, Ticket-Bezug") String description,
            @ToolParam(required = false, description = "Quell-Branch. Leer = aktueller Branch des lokalen Repositories.") String source,
            @ToolParam(required = false, description = "Ziel-Branch. Leer = Standard-Branch des Repositories.") String target,
            @ToolParam(required = false, description = "true = als Entwurf (Draft) anlegen") Boolean draft,
            @ToolParam(required = false, description = "Reviewer (Benutzernamen; GitHub-Teams als team:slug; Bitbucket Cloud: "
                    + "Account-ID oder UUID)") List<String> reviewers,
            @ToolParam(required = false, description = "true = Quell-Branch nach dem Merge löschen (GitLab, Bitbucket Cloud)") Boolean deleteSourceBranch,
            @ToolParam(required = false, description = REPOSITORY) String repository,
            @ToolParam(required = false, description = PROJECT) String project,
            @ToolParam(required = false, description = PROVIDER) String provider,
            @ToolParam(required = false, description = Signature.MODEL_PARAM) String aiModel,
            ToolContext toolContext) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Titel fehlt ('title').");
        }
        PrEnvironment.Target t = env.target(provider, repository, project, null);
        String src = blankToNull(source);
        if (src == null) {
            if (t.local() == null || t.local().branch() == null) {
                throw new IllegalArgumentException("Quell-Branch fehlt ('source') und es gibt keinen ausgecheckten lokalen "
                        + "Branch.");
            }
            src = t.local().branch();
        }
        String tgt = blankToNull(target);
        if (tgt == null && t.local() != null) {
            tgt = PrEnvironment.remoteDefaultBranch(t.local());
        }
        if (src.equals(tgt)) {
            throw new IllegalArgumentException("Quell- und Ziel-Branch sind beide '" + src + "' – zuerst einen eigenen "
                    + "Branch anlegen (git_create_branch) und dort committen.");
        }
        String note = "";
        if (t.local() != null) {
            int unpushed = PrEnvironment.unpushed(t.local(), src);
            if (unpushed < 0 && PrEnvironment.hasBranch(t.local(), src)) {
                throw new IllegalStateException("Branch '" + src + "' ist (laut lokalem Stand) nicht auf '"
                        + t.local().remote() + "' – zuerst pushen: pr_push (falls angeboten) bzw. den Nutzer bitten, "
                        + "'git push -u " + t.local().remote() + " " + src + "' auszuführen.");
            }
            if (unpushed > 0) {
                note = "\nHinweis: " + unpushed + " lokale(r) Commit(s) auf " + src + " sind noch nicht gepusht (pr_push).";
            }
        }
        env.checkWrite(t, null, "Pull Request anlegen");
        String body = env.signed(description, SignatureModule.modelName(aiModel, toolContext));
        GitServer.WriteResult r = t.server().create(t.project(), new GitServer.NewPullRequest(title.strip(),
                blankToNull(body), src, tgt, Boolean.TRUE.equals(draft), reviewers,
                Boolean.TRUE.equals(deleteSourceBranch)));
        return PrTools.written(r) + note + bindHint(r.url());
    }

    @Tool(name = "create_draft", description = "Legt einen Pull/Merge Request als Entwurf (Draft) an – noch nicht "
            + "bereit zum Review, nicht mergebar; später mit pr_mark_ready freigeben. Quell-Branch ist standardmäßig der "
            + "aktuelle Branch des lokalen Repositories, Ziel der Standard-Branch. Der Branch muss gepusht sein (pr_push). "
            + "Titel und Beschreibung aus den Commits/Änderungen zusammenfassen. Nur auf Anweisung des Nutzers."
            + ShellHints.PR)
    public String createDraft(
            @ToolParam(description = "Titel") String title,
            @ToolParam(required = false, description = "Beschreibung (Markdown): was und warum, Testhinweise, Ticket-Bezug") String description,
            @ToolParam(required = false, description = "Quell-Branch. Leer = aktueller Branch des lokalen Repositories.") String source,
            @ToolParam(required = false, description = "Ziel-Branch. Leer = Standard-Branch des Repositories.") String target,
            @ToolParam(required = false, description = "Reviewer (Benutzernamen; GitHub-Teams als team:slug; Bitbucket Cloud: "
                    + "Account-ID oder UUID)") List<String> reviewers,
            @ToolParam(required = false, description = "true = Quell-Branch nach dem Merge löschen (GitLab, Bitbucket Cloud)") Boolean deleteSourceBranch,
            @ToolParam(required = false, description = REPOSITORY) String repository,
            @ToolParam(required = false, description = PROJECT) String project,
            @ToolParam(required = false, description = PROVIDER) String provider,
            @ToolParam(required = false, description = Signature.MODEL_PARAM) String aiModel,
            ToolContext toolContext) {
        return create(title, description, source, target, true, reviewers, deleteSourceBranch, repository, project,
                provider, aiModel, toolContext);
    }

    @Tool(name = "mark_ready", description = "Markiert einen Entwurf (Draft) als bereit zum Review – danach können "
            + "Reviewer freigeben und der Pull Request lässt sich mergen. Ist er kein Entwurf, bleibt er unverändert. "
            + "Nur auf Anweisung des Nutzers." + ShellHints.PR)
    public String markReady(
            @ToolParam(required = false, description = PR + ". Leer = zum aktuellen Branch.") String pr,
            @ToolParam(required = false, description = REPOSITORY) String repository,
            @ToolParam(required = false, description = PROJECT) String project,
            @ToolParam(required = false, description = PROVIDER) String provider) {
        PrEnvironment.Target t = env.target(provider, repository, project, pr);
        String ref = PrTools.refOrCurrent(t, pr);
        env.checkWrite(t, ref, "Als bereit markieren");
        return PrTools.written(t.server().markReady(ref, t.project()));
    }

    /**
     * Der MCP-Server kann den neuen PR nicht selbst an die Client-Sitzung binden; Clients wie Claude Code Desktop
     * erkennen PRs, die über diesen Server angelegt wurden, nicht von selbst – das Modell holt es mit dem Werkzeug des
     * Clients nach. Claude Code nimmt dabei nur URLs der Form {@code https://<host>/<owner>/<repo>/pull/<nummer>} an
     * (GitHub); Bitbucket- und GitLab-URLs lehnt es ab, für sie entfällt der Hinweis.
     */
    static final String BIND_HINT = "\nAn die Sitzung binden: Bietet der Client ein Werkzeug dafür an (z.B. bind_pr in "
            + "Claude Code), den Pull Request jetzt mit dieser URL binden – sonst zeigt die App ihn nicht an und "
            + "überwacht seine CI nicht.";

    private static final Pattern BINDABLE_URL = Pattern.compile("https?://[^/]+/[^/]+/[^/]+/pull/\\d+/?");

    static String bindHint(String url) {
        return url != null && BINDABLE_URL.matcher(url).matches() ? BIND_HINT : "";
    }

    @Tool(name = "update", description = "Ändert Titel, Beschreibung oder Ziel-Branch eines Pull/Merge Requests; nicht "
            + "angegebene Felder bleiben. Nur auf Anweisung des Nutzers." + ShellHints.PR)
    public String update(
            @ToolParam(required = false, description = PR + ". Leer = zum aktuellen Branch.") String pr,
            @ToolParam(required = false, description = "Neuer Titel") String title,
            @ToolParam(required = false, description = "Neue Beschreibung (ersetzt die alte vollständig)") String description,
            @ToolParam(required = false, description = "Neuer Ziel-Branch") String target,
            @ToolParam(required = false, description = REPOSITORY) String repository,
            @ToolParam(required = false, description = PROJECT) String project,
            @ToolParam(required = false, description = PROVIDER) String provider,
            @ToolParam(required = false, description = Signature.MODEL_PARAM) String aiModel,
            ToolContext toolContext) {
        GitServer.PrUpdate u = new GitServer.PrUpdate(blankToNull(title), description == null ? null
                : env.signed(description, SignatureModule.modelName(aiModel, toolContext)), blankToNull(target));
        if (u.isEmpty()) {
            throw new IllegalArgumentException("Nichts zu ändern – title, description oder target angeben.");
        }
        PrEnvironment.Target t = env.target(provider, repository, project, pr);
        String ref = PrTools.refOrCurrent(t, pr);
        env.checkWrite(t, ref, "Pull Request bearbeiten");
        return PrTools.written(t.server().update(ref, t.project(), u));
    }
}
