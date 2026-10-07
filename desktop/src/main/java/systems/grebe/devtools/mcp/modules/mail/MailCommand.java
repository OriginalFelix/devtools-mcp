package systems.grebe.devtools.mcp.modules.mail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * „Befehl bei neuer E-Mail“: startet je gemeldeter Mail einen Prozess, typischerweise einen Agenten ohne offene Sitzung
 * ({@code claude -p "…"}). Läufe kommen nacheinander dran, höchstens {@link #QUEUE} warten.
 *
 * <p>Ohne Shell: Der Befehl wird erst in Argumente zerlegt (Anführungszeichen gruppieren), dann werden darin die
 * Platzhalter {@code {account}}, {@code {folder}} und {@code {uid}} ersetzt. Absender und Betreff stammen von außen und
 * gehen deshalb nur als Umgebungsvariablen mit ({@code DEVTOOLS_MAIL_FROM}, {@code DEVTOOLS_MAIL_SUBJECT}) – nie in die
 * Befehlszeile, wo sie Argumente oder Anweisungen einschleusen könnten.
 */
final class MailCommand implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(MailCommand.class);
    static final int QUEUE = 20;
    private static final int MAX_OUTPUT = 64 * 1024;

    record Settings(String command, Path directory, int timeoutSeconds) {
        boolean active() {
            return command != null && !command.isBlank();
        }
    }

    private final ThreadPoolExecutor runner = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(QUEUE), Thread.ofPlatform().daemon().name("mail-command").factory(),
            (r, ex) -> LOG.warn("Befehl bei neuer E-Mail: Warteschlange voll ({}), Lauf verworfen", QUEUE));
    private volatile String lastRun;

    void submit(Settings s, MailWatcher.NewMail m) {
        if (!s.active()) {
            return;
        }
        runner.execute(() -> run(s, m));
    }

    /** Zuletzt gelaufener Befehl (Zeit, Mail, Ergebnis) oder {@code null}. */
    String lastRun() {
        return lastRun;
    }

    String run(Settings s, MailWatcher.NewMail m) {
        List<String> args = arguments(s.command(), m);
        ProcessBuilder pb = new ProcessBuilder(args).redirectErrorStream(true);
        if (s.directory() != null) {
            pb.directory(s.directory().toFile());
        }
        Map<String, String> env = pb.environment();
        env.put("DEVTOOLS_MAIL_ACCOUNT", m.account());
        env.put("DEVTOOLS_MAIL_FOLDER", m.folder());
        env.put("DEVTOOLS_MAIL_UID", Long.toString(m.uid()));
        env.put("DEVTOOLS_MAIL_FROM", m.from());
        env.put("DEVTOOLS_MAIL_SUBJECT", m.subject());
        String what = m.account() + "/" + m.folder() + " UID " + m.uid();
        String result;
        try {
            Process p = pb.start();
            p.getOutputStream().close(); // kein stdin: der Befehl soll nicht auf Eingaben warten
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thread reader = Thread.ofVirtual().start(() -> copy(p.getInputStream(), out));
            boolean done = p.waitFor(Math.max(10, s.timeoutSeconds()), TimeUnit.SECONDS);
            if (!done) {
                p.descendants().forEach(ProcessHandle::destroy);
                p.destroy();
                if (!p.waitFor(5, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
            }
            reader.join(2000);
            String output;
            synchronized (out) {
                output = out.toString(StandardCharsets.UTF_8).strip();
            }
            result = done ? "Exit-Code " + p.exitValue() : "nach " + s.timeoutSeconds() + " s abgebrochen";
            LOG.info("Befehl bei neuer E-Mail ({}): {}{}", what, result,
                    output.isEmpty() ? "" : "\n" + tail(output, 20));
        } catch (IOException e) {
            result = "nicht startbar: " + e.getMessage();
            LOG.warn("Befehl bei neuer E-Mail ({}) {}", what, result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result = "abgebrochen";
        }
        lastRun = Instant.now() + " – " + what + ": " + result;
        return result;
    }

    private static void copy(InputStream in, ByteArrayOutputStream out) {
        byte[] buf = new byte[8192];
        try (in) {
            int n;
            while ((n = in.read(buf)) > 0) {
                synchronized (out) {
                    if (out.size() < MAX_OUTPUT) {
                        out.write(buf, 0, Math.min(n, MAX_OUTPUT - out.size()));
                    }
                }
            }
        } catch (IOException ignored) {
            // Prozess beendet
        }
    }

    private static String tail(String text, int lines) {
        String[] all = text.split("\n");
        return String.join("\n", java.util.Arrays.copyOfRange(all, Math.max(0, all.length - lines), all.length));
    }

    /** Argumente mit ersetzten Platzhaltern. */
    static List<String> arguments(String command, MailWatcher.NewMail m) {
        List<String> out = new ArrayList<>();
        for (String arg : tokenize(command)) {
            out.add(arg.replace("{account}", m.account()).replace("{folder}", m.folder())
                    .replace("{uid}", Long.toString(m.uid())));
        }
        return out;
    }

    /** Zerlegt an Leerraum; "…" und '…' gruppieren (ohne Escape-Sequenzen). */
    static List<String> tokenize(String command) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inToken = false;
        char quote = 0;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    cur.append(c);
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
                inToken = true;
            } else if (Character.isWhitespace(c)) {
                if (inToken) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    inToken = false;
                }
            } else {
                cur.append(c);
                inToken = true;
            }
        }
        if (quote != 0) {
            throw new IllegalArgumentException("Befehl bei neuer E-Mail: Anführungszeichen nicht geschlossen.");
        }
        if (inToken) {
            out.add(cur.toString());
        }
        return out;
    }

    @Override
    public void close() {
        runner.shutdownNow();
    }
}
