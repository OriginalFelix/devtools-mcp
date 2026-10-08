package systems.grebe.devtools.mcp.modules.git;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import systems.grebe.devtools.mcp.core.CommandRunner;
import systems.grebe.devtools.mcp.core.Text;

/**
 * Netzwerk-Operationen (fetch, pull, push) über das installierte {@code git} – damit gelten SSH-Schlüssel,
 * Credential Manager und Proxy-Einstellungen des Rechners, die JGit nicht kennt. Nie interaktiv: fehlen
 * Zugangsdaten, scheitert der Aufruf sofort mit einem Hinweis statt bis zum Timeout zu warten.
 */
public final class GitCli {

    private static final Map<String, String> NON_INTERACTIVE = Map.of("GIT_TERMINAL_PROMPT", "0",
            "GCM_INTERACTIVE", "never", "GIT_ASKPASS", "", "SSH_ASKPASS", "");

    private GitCli() {
    }

    /**
     * Führt {@code git <args>} im Verzeichnis aus und wirft bei Fehlern eine Meldung mit Hinweis (Anmeldung,
     * abgelehnter Push, fehlendes git).
     *
     * @param what Aktion für Meldungen, z.B. „git push“
     */
    public static String run(Path dir, Duration timeout, String what, List<String> args) {
        List<String> cmd = new ArrayList<>(List.of("git", "-c", "core.quotepath=false"));
        cmd.addAll(args);
        CommandRunner.Result res;
        try {
            res = CommandRunner.run(cmd, timeout, StandardCharsets.UTF_8, dir, NON_INTERACTIVE);
        } catch (CommandRunner.NotStartable e) {
            throw new IllegalStateException(what + ": git ist nicht installiert oder nicht im PATH – für Netzwerk-"
                    + "Operationen wird das installierte git benötigt. " + e.getMessage(), e);
        }
        if (res.timedOut()) {
            throw new IllegalStateException(what + ": Zeitüberschreitung nach " + timeout.toSeconds() + " s – evtl. "
                    + "wartet git auf Zugangsdaten. Der Nutzer sollte die Aktion einmal selbst ausführen, damit die "
                    + "Zugangsdaten gespeichert werden.");
        }
        String out = res.output().strip();
        if (!res.ok()) {
            throw new IllegalStateException(what + " fehlgeschlagen (Exit-Code " + res.exitCode() + "):\n"
                    + Text.limitLines(out, 40) + hint(out));
        }
        return out;
    }

    static String hint(String out) {
        String o = out.toLowerCase(java.util.Locale.ROOT);
        if (o.contains("non-fast-forward") || o.contains("[rejected]") || o.contains("fetch first")) {
            return "\nDas Remote hat neuere Commits – zuerst git_fetch und git_pull (bzw. Rebase); Force-Push ist "
                    + "nicht vorgesehen.";
        }
        if (o.contains("authentication") || o.contains("terminal prompts disabled") || o.contains("permission denied")
                || o.contains("could not read username") || o.contains("403")) {
            return "\nAnmeldung fehlgeschlagen – der Nutzer muss die Git-Zugangsdaten (SSH-Schlüssel, Credential "
                    + "Manager) für dieses Remote einrichten.";
        }
        if (o.contains("not possible to fast-forward") || o.contains("diverging branches")) {
            return "\nLokaler und Remote-Branch sind auseinandergelaufen – git_pull mit mode=rebase oder git_merge.";
        }
        if (o.contains("conflict")) {
            return "\nKonflikte: git_status zeigt die Dateien; lösen, git_stage, dann git_continue – oder git_abort.";
        }
        return "";
    }
}
