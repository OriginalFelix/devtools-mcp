package systems.grebe.devtools.mcp.modules.pr;

import java.util.List;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.Text;
import systems.grebe.devtools.mcp.core.Workspaces;
import systems.grebe.devtools.mcp.modules.git.GitCli;

import static systems.grebe.devtools.mcp.modules.pr.PrTools.REPOSITORY;
import static systems.grebe.devtools.mcp.modules.pr.PrTools.blankToNull;

/**
 * Branch pushen (Schalter {@code allowPush}) – über das installierte {@code git}, damit SSH-Schlüssel und
 * Credential-Manager des Rechners gelten. Nie Force-Push, nie auf den Standard-Branch.
 */
public class PrPushTools {

    private final PrEnvironment env;

    PrPushTools(PrEnvironment env) {
        this.env = env;
    }

    @Tool(name = "push", description = "Pusht einen lokalen Branch (Standard: den aktuellen) auf das Remote und setzt "
            + "den Upstream (git push -u) – vor pr_create und nach Commits, die Review-Anmerkungen umsetzen. Kein "
            + "Force-Push; der Standard-Branch (main/master) wird nie gepusht. Nur auf Anweisung des Nutzers."
            + ShellHints.PR)
    public String push(
            @ToolParam(required = false, description = REPOSITORY) String repository,
            @ToolParam(required = false, description = "Branch. Leer = aktueller Branch.") String branch) {
        PrEnvironment.LocalRepo local = env.local(repository);
        Workspaces.requireWritable(local.dir());
        String b = blankToNull(branch);
        if (b == null) {
            b = local.branch();
            if (b == null) {
                throw new IllegalStateException("'" + local.name() + "' hat keinen ausgecheckten Branch (losgelöster HEAD).");
            }
        }
        if (!b.matches("[\\w./-]+") || b.startsWith("-") || b.contains("..")) {
            throw new IllegalArgumentException("Ungültiger Branch-Name '" + b + "'.");
        }
        String defaultBranch = PrEnvironment.remoteDefaultBranch(local);
        if (b.equals(defaultBranch) || List.of("main", "master").contains(b)) {
            throw new IllegalStateException("'" + b + "' ist der Standard-Branch – pr_push pusht nur Feature-Branches. "
                    + "Änderungen über einen eigenen Branch und pr_create einbringen.");
        }
        if (local.remoteUrl() == null) {
            throw new IllegalStateException("'" + local.name() + "' hat kein Remote '" + local.remote() + "'.");
        }
        if (!PrEnvironment.hasBranch(local, b)) {
            throw new IllegalArgumentException("Lokaler Branch '" + b + "' existiert in '" + local.name() + "' nicht.");
        }
        int before = PrEnvironment.unpushed(local, b);
        if (before == 0) {
            return b + " ist bereits auf " + local.remote() + " aktuell – nichts zu pushen.";
        }
        String refspec = "refs/heads/" + b + ":refs/heads/" + b;
        String out = GitCli.run(local.dir(), env.pushTimeout(), "git push",
                List.of("push", "--porcelain", "-u", local.remote(), refspec));
        return "Branch " + b + " auf " + local.remote() + " (" + local.remoteUrl() + ") gepusht"
                + (before > 0 ? ", " + before + " Commit(s)" : " (neu)") + ".\n" + Text.limitLines(out, 20);
    }
}
