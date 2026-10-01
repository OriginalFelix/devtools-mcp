package systems.grebe.devtools.mcp.modules.pr;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.CommandRunner;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.Text;
import systems.grebe.devtools.mcp.core.Workspaces;

import static systems.grebe.devtools.mcp.modules.pr.PrTools.REPOSITORY;
import static systems.grebe.devtools.mcp.modules.pr.PrTools.blankToNull;

/**
 * Branch pushen (Schalter {@code allowPush}) – über das installierte {@code git}, damit SSH-Schlüssel und
 * Credential-Manager des Rechners gelten. Nie Force-Push, nie auf den Standard-Branch.
 */
public class PrPushTools {

    /** Nie interaktiv nachfragen (Passwort, Credential-Manager-Dialog) – sonst hinge der Aufruf bis zum Timeout. */
    private static final Map<String, String> NON_INTERACTIVE = Map.of("GIT_TERMINAL_PROMPT", "0", "GCM_INTERACTIVE", "never");

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
        CommandRunner.Result res = CommandRunner.run(List.of("git", "push", "--porcelain", "-u", local.remote(), refspec),
                env.pushTimeout(), StandardCharsets.UTF_8, local.dir(), NON_INTERACTIVE);
        if (res.timedOut()) {
            throw new IllegalStateException("git push: Zeitüberschreitung – evtl. wartet git auf Zugangsdaten. Der Nutzer "
                    + "sollte einmal selbst pushen, damit die Zugangsdaten gespeichert werden.");
        }
        if (!res.ok()) {
            String out = res.output().strip();
            String hint = out.contains("rejected") || out.contains("non-fast-forward")
                    ? "\nDas Remote hat neuere Commits – Nutzer fragen (pull/rebase); Force-Push ist nicht vorgesehen."
                    : out.contains("Authentication") || out.contains("terminal prompts disabled")
                    || out.contains("Permission denied") ? "\nAnmeldung fehlgeschlagen – der Nutzer muss die "
                    + "Git-Zugangsdaten (SSH-Schlüssel, Credential Manager) für " + local.remoteUrl() + " einrichten." : "";
            throw new IllegalStateException("git push fehlgeschlagen (Exit-Code " + res.exitCode() + "):\n"
                    + Text.limitLines(out, 30) + hint);
        }
        return "Branch " + b + " auf " + local.remote() + " (" + local.remoteUrl() + ") gepusht"
                + (before > 0 ? ", " + before + " Commit(s)" : " (neu)") + ".\n" + Text.limitLines(res.output().strip(), 20);
    }
}
