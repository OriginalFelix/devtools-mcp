package systems.grebe.devtools.mcp.modules.ssh;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.ToolHints;

/**
 * Befehle mit sudo (nur wenn im Modul erlaubt). Das Passwort geht per stdin an {@code sudo -S} – nie in die
 * Befehlszeile (dort stünde es in der Prozessliste) und nie zum LLM; taucht es in der Ausgabe auf, wird es maskiert.
 */
@ToolHints(destructive = true)
public class SshSudoTools {

    private final SshEnvironment env;

    SshSudoTools(SshEnvironment env) {
        this.env = env;
    }

    @Tool(name = "sudo", description = "Führt einen Befehl mit Root-Rechten (sudo) auf einem SSH-Server aus und liefert "
            + "Exit-Code, stdout und stderr. Das sudo-Passwort ist in der DevTools-App hinterlegt – nie danach fragen und "
            + "nicht 'sudo' in ssh_exec verwenden. Nur auf ausdrückliche Anweisung des Nutzers." + ShellHints.SSH)
    public String sudo(
            @ToolParam(required = false, description = SshTools.CONNECTION) String connection,
            @ToolParam(description = "Befehlszeile ohne 'sudo', z.B. \"systemctl restart nginx\"; läuft in sh -c") String command,
            @ToolParam(required = false, description = "Arbeitsverzeichnis auf dem Server") String workDir,
            @ToolParam(required = false, description = "Benutzer statt root (sudo -u)") String user,
            @ToolParam(required = false, description = "Text, der dem Befehl über stdin übergeben wird") String stdin,
            @ToolParam(required = false, description = "Timeout in Sekunden (Standard und Maximum: Einstellung im Modul)") Integer timeoutSeconds) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("'command' fehlt.");
        }
        if (user != null && !user.isBlank() && !user.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("Ungültiger Benutzer: " + user);
        }
        SshConnection c = env.resolve(connection);
        int t = SshExecTools.timeout(env, timeoutSeconds);
        String as = user == null || user.isBlank() ? "" : "-u " + user.trim() + " ";

        // Verlangt sudo gerade kein Passwort (NOPASSWD, noch gültiger Zeitstempel), würde es das Passwort nicht lesen –
        // es landete dann auf dem stdin des Befehls. Deshalb erst ohne Passwort prüfen.
        boolean needsPassword = env.exec(c, "sudo -n " + as + "true", null, 15).exitCode() != 0;
        String secret = c.sudoSecret();
        if (needsPassword && secret == null) {
            throw new IllegalStateException("sudo verlangt auf '" + c.name() + "' ein Passwort, für die Verbindung ist "
                    + "aber keines hinterlegt. Der Nutzer kann in der DevTools-App ein sudo-Passwort eintragen.");
        }
        String line = "sudo " + (needsPassword ? "-S -p '' " : "-n ") + as + "-- sh -c "
                + SshExecTools.quote(SshExecTools.inDir(workDir, command));
        String input = (needsPassword ? secret + "\n" : "") + (stdin == null ? "" : stdin);
        SshEnvironment.ExecResult r = env.exec(c, line, input, t);
        String result = SshExecTools.format(env, c, r, t, secret);
        if (needsPassword && (r.stderr().contains("incorrect password") || r.stderr().contains("Sorry, try again"))) {
            result += "\n[sudo hat das hinterlegte Passwort abgelehnt – der Nutzer muss es in der DevTools-App korrigieren]";
        } else if (r.stderr().contains("is not in the sudoers file") || r.stderr().contains("not allowed to execute")) {
            result += "\n[Der Benutzer " + c.username() + " darf diesen Befehl nicht per sudo ausführen]";
        }
        return result;
    }
}
