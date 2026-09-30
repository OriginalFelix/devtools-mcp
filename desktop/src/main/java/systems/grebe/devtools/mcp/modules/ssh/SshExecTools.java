package systems.grebe.devtools.mcp.modules.ssh;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.Text;
import systems.grebe.devtools.mcp.core.ToolHints;

/** Befehle auf dem Server ausführen (nur wenn im Modul erlaubt). */
@ToolHints(destructive = true)
public class SshExecTools {

    private final SshEnvironment env;

    SshExecTools(SshEnvironment env) {
        this.env = env;
    }

    @Tool(name = "exec", description = "Führt einen Befehl auf einem SSH-Server aus (in der Login-Shell des Benutzers, "
            + "Pipes und Umleitungen sind also möglich) und liefert Exit-Code, stdout und stderr. Kein Terminal (PTY): "
            + "interaktive Programme (vim, top ohne -b, sudo mit Passwortabfrage) funktionieren nicht. Die Sitzung wird "
            + "zwischen Aufrufen wiederverwendet, Zustand (cd, Variablen) aber nicht – dafür 'workDir' angeben oder Befehle "
            + "mit && verketten." + ShellHints.SSH)
    public String exec(
            @ToolParam(required = false, description = SshTools.CONNECTION) String connection,
            @ToolParam(description = "Befehlszeile, z.B. \"systemctl status nginx --no-pager\" oder \"df -h && free -m\"") String command,
            @ToolParam(required = false, description = "Arbeitsverzeichnis auf dem Server (POSIX-Shell: wird per cd vorangestellt)") String workDir,
            @ToolParam(required = false, description = "Text, der dem Befehl über stdin übergeben wird") String stdin,
            @ToolParam(required = false, description = "Timeout in Sekunden (Standard und Maximum: Einstellung im Modul)") Integer timeoutSeconds) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("'command' fehlt.");
        }
        SshConnection c = env.resolve(connection);
        String line = inDir(workDir, command);
        int t = timeout(env, timeoutSeconds);
        return format(env, c, env.exec(c, line, stdin, t), t, null);
    }

    /** Timeout aus dem Aufruf, begrenzt auf die Einstellung. */
    static int timeout(SshEnvironment env, Integer timeoutSeconds) {
        return timeoutSeconds == null || timeoutSeconds <= 0 ? env.maxExecSeconds()
                : Math.min(timeoutSeconds, env.maxExecSeconds());
    }

    /** Befehlszeile mit vorangestelltem {@code cd}, falls ein Arbeitsverzeichnis angegeben ist. */
    static String inDir(String workDir, String command) {
        return workDir == null || workDir.isBlank() ? command : "cd " + quote(workDir.trim()) + " && " + command;
    }

    /**
     * Ergebnis für das LLM: Kopfzeile mit Exit-Code, stdout und stderr getrennt und begrenzt.
     *
     * @param secret wird in der Ausgabe maskiert (z.B. ein sudo-Passwort), oder {@code null}
     */
    static String format(SshEnvironment env, SshConnection c, SshEnvironment.ExecResult r, int t, String secret) {
        StringBuilder sb = new StringBuilder();
        sb.append(!r.timedOut() ? "Exit-Code " + r.exitCode()
                        : "Zeitüberschreitung nach " + t + " s " + (r.stopped() ? "(Befehl abgebrochen)"
                        : "(Abbruch nicht bestätigt – der Server nimmt keine Signale an, der Befehl läuft womöglich weiter)"))
                .append(" (").append(c.name()).append(", ").append(r.millis()).append(" ms)");
        String out = mask(r.stdout(), secret).strip();
        String err = mask(r.stderr(), secret).strip();
        if (out.isEmpty() && err.isEmpty()) {
            sb.append("\n(keine Ausgabe)");
        }
        if (!out.isEmpty()) {
            sb.append("\n--- stdout ---\n").append(Text.limitLines(out, env.maxLines()));
        }
        if (!err.isEmpty()) {
            sb.append("\n--- stderr ---\n").append(Text.limitLines(err, Math.max(50, env.maxLines() / 2)));
        }
        if (r.truncated()) {
            sb.append("\n… [Ausgabe nach ").append(env.maxBytes() / 1024).append(" KB abgeschnitten – Ausgabe eingrenzen, "
                    + "z.B. mit | head, | tail oder grep]");
        }
        return sb.toString();
    }

    private static String mask(String text, String secret) {
        return secret == null || secret.isEmpty() ? text : text.replace(secret, "********");
    }

    /** POSIX-Quoting in einfachen Anführungszeichen. */
    static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
