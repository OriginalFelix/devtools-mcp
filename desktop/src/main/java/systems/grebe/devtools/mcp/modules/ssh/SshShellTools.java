package systems.grebe.devtools.mcp.modules.ssh;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.jcraft.jsch.JSchException;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.Text;
import systems.grebe.devtools.mcp.core.ToolHints;

/**
 * Interaktive Shells: Befehl ausführen → Ausgabe stückweise lesen, solange er läuft → Eingaben schicken → nächster
 * Befehl in derselben Shell. Das Ende eines Befehls erkennt {@code ssh_shell_exec} an einer Markierung mit dem Exit-Code,
 * die nach dem Befehl ausgegeben wird (POSIX-Shell auf dem Server).
 */
@ToolHints(destructive = true)
public class SshShellTools {

    private static final String SHELL = "Shell-ID aus ssh_shell_open (z.B. sh1); leer = die einzige offene Shell";
    private static final String WAIT_FOR = "Regulärer Ausdruck: Warten beenden, sobald er in der neuen Ausgabe vorkommt "
            + "(z.B. ein Prompt wie '\\$ $' oder 'Password:')";
    /**
     * Markierungen vor und nach jedem Befehl, von der Shell per {@code printf} aus getrennten Teilen zusammengesetzt: das
     * Echo der Eingabe (mit PTY) enthält deshalb nie eine fertige Markierung, nur die echte Ausgabe (Idee aus ssh-mcp).
     * Die Endmarkierung trägt Exit-Code und Arbeitsverzeichnis.
     */
    private static final Pattern BEGIN = Pattern.compile("DTMCP_B_([0-9a-f]{16})\\n?");
    private static final Pattern END = Pattern.compile("DTMCP_E_([0-9a-f]{16})__(\\d+)__([^\\n]*)\\n?");
    private static final long IDLE_MILLIS = 700;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SshEnvironment env;

    SshShellTools(SshEnvironment env) {
        this.env = env;
    }

    @Tool(name = "shell_open", description = "Öffnet eine interaktive Shell auf einem SSH-Server und liefert ihre ID. "
            + "Anders als ssh_exec bleibt der Zustand (Verzeichnis nach cd, Variablen, aktivierte Umgebungen) zwischen "
            + "Befehlen erhalten, und lang laufende Befehle lassen sich mit ssh_shell_read schrittweise mitlesen. Danach: "
            + "ssh_shell_exec für Befehle, ssh_shell_send für Eingaben an laufende Programme, am Ende ssh_shell_close."
            + ShellHints.SSH)
    @ToolHints(destructive = false)
    public String open(
            @ToolParam(required = false, description = SshTools.CONNECTION) String connection,
            @ToolParam(required = false, description = "Mit Terminal (PTY): nötig für Programme, die ein Terminal "
                    + "erwarten (sudo-Passwortabfrage, top, less), und damit ctrl=c zuverlässig abbricht. Ausgabe enthält "
                    + "dann Prompt und Echo der Eingaben. Standard false") Boolean pty) {
        SshConnection c = env.resolve(connection);
        env.shells().closeIdle(env.shellIdleMillis());
        SshShells.Shell shell;
        try {
            shell = env.shells().open(c, env.open(c), Boolean.TRUE.equals(pty), env.maxShells(), env.maxBytes());
        } catch (JSchException e) {
            throw new IllegalStateException(env.describe(c, e), e);
        }
        String banner = render(shell, SshShells.clean(collect(shell, 1_500, null).text()));
        return "Shell " + shell.id + " geöffnet (" + c.name() + ", " + c.target() + ", "
                + (shell.pty ? "mit PTY" : "ohne PTY") + ")." + (banner.isBlank() ? "" : "\n" + banner);
    }

    @Tool(name = "shell_exec", description = "Führt einen Befehl in einer offenen Shell aus und wartet auf sein Ende "
            + "(Exit-Code). Ist er nach 'timeoutSeconds' nicht fertig, kommt die bisherige Ausgabe zurück und er läuft "
            + "weiter: mit ssh_shell_read weiterlesen (liefert am Ende den Exit-Code), mit ssh_shell_send ctrl=c abbrechen. "
            + "Wartet ein Programm auf Eingabe (Rückfrage y/n, REPL), zeigt die Teilausgabe das – Antwort per ssh_shell_send."
            + ShellHints.SSH)
    public String exec(
            @ToolParam(required = false, description = SHELL) String shell,
            @ToolParam(description = "Befehlszeile, z.B. \"cd /var/www && git pull\"") String command,
            @ToolParam(required = false, description = "Sekunden, die auf das Ende gewartet wird (Standard 30, max. "
                    + "Einstellung im Modul)") Integer timeoutSeconds) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("'command' fehlt.");
        }
        SshShells.Shell s = shell(shell);
        if (s.running != null) {
            throw new IllegalStateException("In Shell " + s.id + " läuft noch ein Befehl – erst mit ssh_shell_read zu Ende "
                    + "lesen oder mit ssh_shell_send ctrl=c abbrechen.");
        }
        byte[] seed = new byte[8]; // nicht erratbar: sonst ließe sich ein Exit-Code fälschen
        RANDOM.nextBytes(seed);
        String nonce = HexFormat.of().formatHex(seed);
        s.running = nonce;
        long start = System.currentTimeMillis();
        // Als ein Block: die Shell liest ihn ganz, bevor der Befehl startet – so kann ein Programm, das von stdin liest,
        // die Endmarkierung nicht als Eingabe verschlucken; Eingaben kommen dann per ssh_shell_send. Der Zeilenumbruch vor
        // '}' beendet auch einen Kommentar am Ende des Befehls.
        s.write("{ printf '%s%s\\n' 'DTMCP_B_' '" + nonce + "'; " + command.strip() + "\n"
                + "}; printf '%s%s__%s__%s\\n' 'DTMCP_E_' '" + nonce + "' \"$?\" \"$PWD\"\n");
        int t = timeoutSeconds == null || timeoutSeconds <= 0 ? Math.min(30, env.maxExecSeconds())
                : Math.min(timeoutSeconds, env.maxExecSeconds());
        SshShells.Chunk chunk = collect(s, t * 1000L, Pattern.compile("DTMCP_E_" + nonce + "__\\d+__"));
        long millis = System.currentTimeMillis() - start;
        String text = SshShells.clean(chunk.text());
        String head = end(text, nonce)
                .map(m -> "Exit-Code " + m.group(2) + " (" + s.id + ", " + m.group(3) + ", " + millis + " ms)")
                .orElse(s.ended() ? "Shell beendet (" + s.id + ")"
                        : "Läuft noch nach " + t + " s (" + s.id + ") – weiter mit ssh_shell_read, abbrechen mit "
                        + "ssh_shell_send ctrl=c. Ausgabe bisher:");
        return finish(s, head, chunk);
    }

    @Tool(name = "shell_read", description = "Liest die neue Ausgabe einer Shell seit dem letzten Aufruf – zum Mitlesen "
            + "lang laufender Befehle (Build, Deployment, tail -f). Wartet bis 'waitSeconds', kehrt aber früher zurück, "
            + "sobald neue Ausgabe kurz ruht oder 'waitFor' vorkommt. Meldet, wenn ein Befehl aus ssh_shell_exec fertig ist."
            + ShellHints.SSH)
    @ToolHints(readOnly = true)
    public String read(
            @ToolParam(required = false, description = SHELL) String shell,
            @ToolParam(required = false, description = "Höchstens so viele Sekunden auf Ausgabe warten (Standard 10)") Integer waitSeconds,
            @ToolParam(required = false, description = WAIT_FOR) String waitFor) {
        SshShells.Shell s = shell(shell);
        int w = waitSeconds == null || waitSeconds < 0 ? 10 : Math.min(waitSeconds, env.maxExecSeconds());
        SshShells.Chunk chunk = collect(s, w * 1000L, pattern(waitFor));
        return finish(s, null, chunk);
    }

    @Tool(name = "shell_send", description = "Schickt Eingabe an eine Shell bzw. an das darin laufende Programm – Antwort "
            + "auf eine Rückfrage (y/n), Zeile für eine REPL, oder ein Steuerzeichen (ctrl=c bricht ab, ctrl=d = Dateiende). "
            + "Liefert die Ausgabe danach wie ssh_shell_read. Für normale Befehle ssh_shell_exec verwenden." + ShellHints.SSH)
    public String send(
            @ToolParam(required = false, description = SHELL) String shell,
            @ToolParam(required = false, description = "Text; leer, wenn nur 'ctrl' geschickt wird") String input,
            @ToolParam(required = false, description = "Zeilenumbruch anhängen (Standard true)") Boolean newline,
            @ToolParam(required = false, description = "Steuerzeichen statt/nach Text: c (Abbruch), d (EOF), z (anhalten), \\ (Quit)") String ctrl,
            @ToolParam(required = false, description = "Höchstens so viele Sekunden auf Ausgabe warten (Standard 3)") Integer waitSeconds,
            @ToolParam(required = false, description = WAIT_FOR) String waitFor) {
        SshShells.Shell s = shell(shell);
        boolean anything = false;
        if (input != null && !input.isEmpty()) {
            s.write(Boolean.FALSE.equals(newline) ? input : input + "\n");
            anything = true;
        }
        if (ctrl != null && !ctrl.isBlank()) {
            String k = ctrl.trim().toLowerCase().replace("ctrl-", "").replace("^", "");
            char code = switch (k) {
                case "c" -> '\u0003';
                case "d" -> '\u0004';
                case "z" -> '\u001a';
                case "\\" -> '\u001c';
                default -> throw new IllegalArgumentException("Unbekanntes Steuerzeichen '" + ctrl + "' – c, d, z oder \\.");
            };
            s.write(String.valueOf(code));
            if (!s.pty && code == '\u0003') {
                s.signal("INT"); // ohne PTY wertet niemand ^C aus – Signal an den Prozess versuchen
            }
            anything = true;
        } else if (!anything && Boolean.TRUE.equals(newline)) {
            s.write("\n");
            anything = true;
        }
        if (!anything) {
            throw new IllegalArgumentException("Weder 'input' noch 'ctrl' angegeben.");
        }
        int w = waitSeconds == null || waitSeconds < 0 ? 3 : Math.min(waitSeconds, env.maxExecSeconds());
        return finish(s, null, collect(s, w * 1000L, pattern(waitFor)));
    }

    @Tool(name = "shell_close", description = "Schließt eine Shell und liefert ihre letzte, noch nicht gelesene Ausgabe. "
            + "Ein laufender Befehl wird abgebrochen (^C, exit, dann Signale INT → TERM → KILL); das Ergebnis sagt, ob das "
            + "Ende bestätigt ist." + ShellHints.SSH)
    public String close(@ToolParam(required = false, description = SHELL) String shell) {
        SshShells.Shell s = shell(shell);
        String rest = render(s, SshShells.clean(collect(s, 0, null).text()));
        boolean stopped;
        try {
            stopped = env.shells().terminate(s.id);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Abgebrochen.", e);
        }
        return "Shell " + s.id + (stopped ? " beendet und geschlossen."
                : " geschlossen, Ende aber nicht bestätigt: ein darin gestarteter Prozess läuft womöglich weiter (der "
                + "Server nimmt keine Signale an oder der Prozess ignoriert sie). Mit ssh_exec prüfen, z.B. ps -u $USER.")
                + (rest.isBlank() ? "" : "\nLetzte Ausgabe:\n" + rest);
    }

    // ------------------------------------------------------------------ intern

    /** Offene Shell nach ID; räumt vorher lange unbenutzte Shells ab. */
    private SshShells.Shell shell(String id) {
        env.shells().closeIdle(env.shellIdleMillis());
        return env.shells().get(id);
    }

    private static SshShells.Chunk collect(SshShells.Shell s, long waitMillis, Pattern until) {
        try {
            return s.collect(waitMillis, IDLE_MILLIS, until);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Abgebrochen.", e);
        }
    }

    /** {@code waitFor} als Muster; ohne Angabe wartet collect auf Ruhe nach neuer Ausgabe. */
    private static Pattern pattern(String waitFor) {
        if (waitFor == null || waitFor.isBlank()) {
            return null;
        }
        try {
            return Pattern.compile(waitFor, Pattern.MULTILINE);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("'waitFor' ist kein gültiger regulärer Ausdruck: " + e.getDescription());
        }
    }

    /** Endmarkierung eines bestimmten Befehls in bereinigter Ausgabe. */
    private static java.util.Optional<java.util.regex.MatchResult> end(String text, String nonce) {
        if (nonce == null) {
            return java.util.Optional.empty();
        }
        return END.matcher(text).results().filter(r -> r.group(1).equals(nonce)).findFirst();
    }

    /** Kopfzeile + Ausgabe; erkennt das Ende des laufenden Befehls und schließt beendete Shells. */
    private String finish(SshShells.Shell s, String head, SshShells.Chunk chunk) {
        StringBuilder sb = new StringBuilder();
        String text = SshShells.clean(chunk.text());
        String out = render(s, text);
        var finished = end(text, s.running);
        if (finished.isPresent()) {
            s.running = null;
            if (head == null) {
                head = "Befehl beendet: Exit-Code " + finished.get().group(2) + " (" + s.id + ", "
                        + finished.get().group(3) + ")";
            }
        } else if (head == null && s.running != null && !s.ended()) {
            head = "Befehl läuft noch (" + s.id + ")";
        }
        boolean ended = s.ended();
        if (head != null) {
            sb.append(head);
        }
        if (chunk.dropped() > 0) {
            sb.append(sb.isEmpty() ? "" : "\n").append("… [").append(chunk.dropped())
                    .append(" ältere Zeichen verworfen – Ausgabe öfter lesen oder eingrenzen]");
        }
        sb.append(sb.isEmpty() ? "" : "\n").append(out.isBlank() ? "(keine neue Ausgabe)" : out);
        if (ended) {
            int status = s.exitStatus();
            env.shells().close(s.id);
            sb.append("\n[Shell ").append(s.id).append(" beendet").append(status >= 0 ? ", Exit-Code " + status : "")
                    .append(" und geschlossen]");
        }
        return sb.toString();
    }

    /**
     * Bereinigte Ausgabe ohne Markierungen. Alles vor der Anfangsmarkierung des laufenden Befehls entfällt – das ist mit
     * PTY das Echo der Eingabe. Die Endmarkierung des laufenden Befehls steht in der Kopfzeile; die eines früheren (z.B.
     * per ctrl=c abgebrochenen) wird als Zeile angezeigt.
     */
    private String render(SshShells.Shell s, String text) {
        if (s.running != null) {
            Matcher b = BEGIN.matcher(text);
            while (b.find()) {
                if (b.group(1).equals(s.running)) {
                    text = text.substring(b.end());
                    break;
                }
            }
        }
        text = BEGIN.matcher(text).replaceAll("");
        text = END.matcher(text).replaceAll(r -> r.group(1).equals(s.running) ? ""
                : Matcher.quoteReplacement("[Befehl beendet – Exit-Code " + r.group(2) + ", " + r.group(3) + "]\n"));
        List<String> lines = text.strip().lines().toList();
        return Text.tailLines(lines, env.maxLines());
    }
}
