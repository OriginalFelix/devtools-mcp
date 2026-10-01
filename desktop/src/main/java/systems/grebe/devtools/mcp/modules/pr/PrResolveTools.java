package systems.grebe.devtools.mcp.modules.pr;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;

import static systems.grebe.devtools.mcp.modules.pr.PrTools.PR;
import static systems.grebe.devtools.mcp.modules.pr.PrTools.PROJECT;
import static systems.grebe.devtools.mcp.modules.pr.PrTools.PROVIDER;
import static systems.grebe.devtools.mcp.modules.pr.PrTools.REPOSITORY;

/** Threads auflösen (Schalter {@code allowResolve}). */
public class PrResolveTools {

    private final PrEnvironment env;

    PrResolveTools(PrEnvironment env) {
        this.env = env;
    }

    @Tool(name = "resolve", description = "Markiert einen Kommentar-Thread (ID aus pr_comments) als erledigt bzw. öffnet "
            + "ihn wieder (resolved=false). Nur Threads auflösen, deren Anmerkung umgesetzt und gepusht ist; GitHub löst "
            + "nur Code-Threads auf. Nur auf Anweisung des Nutzers." + ShellHints.PR)
    public String resolve(
            @ToolParam(description = "Thread-ID aus pr_comments (in eckigen Klammern)") String thread,
            @ToolParam(required = false, description = "false = wieder öffnen (Standard true)") Boolean resolved,
            @ToolParam(required = false, description = PR + ". Leer = zum aktuellen Branch.") String pr,
            @ToolParam(required = false, description = REPOSITORY) String repository,
            @ToolParam(required = false, description = PROJECT) String project,
            @ToolParam(required = false, description = PROVIDER) String provider) {
        if (thread == null || thread.isBlank()) {
            throw new IllegalArgumentException("Thread-ID fehlt ('thread', aus pr_comments).");
        }
        PrEnvironment.Target t = env.target(provider, repository, project, pr);
        String ref = PrTools.refOrCurrent(t, pr);
        env.checkWrite(t, ref, "Thread auflösen");
        return PrTools.written(t.server().resolve(ref, t.project(), PrCommentTools.stripBrackets(thread),
                !Boolean.FALSE.equals(resolved)));
    }
}
