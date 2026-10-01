package systems.grebe.devtools.mcp.modules.pr;

import java.util.List;
import java.util.Locale;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.modules.pr.spi.GitServer;

import static systems.grebe.devtools.mcp.modules.pr.PrTools.PROJECT;
import static systems.grebe.devtools.mcp.modules.pr.PrTools.PROVIDER;
import static systems.grebe.devtools.mcp.modules.pr.PrTools.REPOSITORY;
import static systems.grebe.devtools.mcp.modules.pr.PrTools.blankToNull;

/** Mergen (Schalter {@code allowMerge}). */
public class PrMergeTools {

    private final PrEnvironment env;

    PrMergeTools(PrEnvironment env) {
        this.env = env;
    }

    @Tool(name = "merge", description = "Führt einen Pull/Merge Request in den Ziel-Branch zusammen – nur auf ausdrücklichen "
            + "Auftrag des Nutzers und nach pr_get (Freigaben, Checks, Konflikte). Schlägt fehl, wenn der Server es "
            + "blockiert oder der Branch seit pr_get neue Commits bekam." + ShellHints.PR)
    public String merge(
            @ToolParam(description = "Pull Request: Nummer, voller Schlüssel oder URL (ausdrücklich, kein Standard)") String pr,
            @ToolParam(required = false, description = "merge, squash oder rebase. Leer = Standard des Repositories.") String method,
            @ToolParam(required = false, description = "Commit-Nachricht") String message,
            @ToolParam(required = false, description = "true = Quell-Branch danach löschen") Boolean deleteSourceBranch,
            @ToolParam(required = false, description = REPOSITORY) String repository,
            @ToolParam(required = false, description = PROJECT) String project,
            @ToolParam(required = false, description = PROVIDER) String provider) {
        if (pr == null || pr.isBlank()) {
            throw new IllegalArgumentException("Pull Request fehlt ('pr') – beim Mergen ausdrücklich angeben.");
        }
        String m = blankToNull(method);
        if (m != null) {
            m = m.toLowerCase(Locale.ROOT);
            if (!List.of("merge", "squash", "rebase").contains(m)) {
                throw new IllegalArgumentException("Unbekannte Merge-Methode '" + method + "' – erlaubt: merge, squash, rebase.");
            }
        }
        PrEnvironment.Target t = env.target(provider, repository, project, pr);
        env.checkWrite(t, pr, "Mergen");
        return PrTools.written(t.server().merge(pr.trim(), t.project(), new GitServer.MergeOptions(m, blankToNull(message),
                Boolean.TRUE.equals(deleteSourceBranch))));
    }
}
