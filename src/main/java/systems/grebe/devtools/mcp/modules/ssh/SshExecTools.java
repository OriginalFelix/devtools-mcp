package systems.grebe.devtools.mcp.modules.ssh;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.Text;

/** Befehle auf dem Server ausführen (nur wenn im Modul erlaubt). */
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
        String line = workDir == null || workDir.isBlank() ? command : "cd " + quote(workDir.trim()) + " && " + command;
        int t = timeoutSeconds == null || timeoutSeconds <= 0 ? env.maxExecSeconds()
                : Math.min(timeoutSeconds, env.maxExecSeconds());
        SshEnvironment.ExecResult r = env.exec(c, line, stdin, t);
        StringBuilder sb = new StringBuilder();
        sb.append(r.timedOut() ? "Zeitüberschreitung nach " + t + " s (Befehl abgebrochen)" : "Exit-Code " + r.exitCode())
                .append(" (").append(c.name()).append(", ").append(r.millis()).append(" ms)");
        String out = r.stdout().strip();
        String err = r.stderr().strip();
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

    /** POSIX-Quoting in einfachen Anführungszeichen. */
    static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
