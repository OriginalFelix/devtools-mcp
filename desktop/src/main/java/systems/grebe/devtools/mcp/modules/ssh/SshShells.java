package systems.grebe.devtools.mcp.modules.ssh;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import systems.grebe.devtools.mcp.core.ToolProgress;

/**
 * Offene interaktive Shells. Jede Shell hat eine eigene SSH-Sitzung; ihre Ausgabe (stdout und stderr in
 * Ankunftsreihenfolge) landet fortlaufend in einem Puffer, den die Tools stückweise abholen – so kann das LLM die
 * Ausgabe eines laufenden Befehls lesen, weitere Eingaben schicken und danach den nächsten Befehl in derselben Shell
 * (gleiches Verzeichnis, gleiche Variablen) ausführen. Über alle Konfigurationsänderungen hinweg dieselbe Instanz.
 */
final class SshShells {

    /** Steuersequenzen (Farben, Cursor, Fenstertitel) und {@code \r} – für das LLM nur Rauschen. */
    private static final Pattern ANSI = Pattern.compile(
            "\u001B\\[[0-?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)|\u001B[()][A-Za-z0-9]|\u001B[=>]|\r");

    /** Eine offene Shell. Ausgabe wird unter {@code this} gepuffert; Warten per {@code wait/notifyAll}. */
    static final class Shell {
        final String id;
        final SshConnection connection;
        final boolean pty;
        final long opened = System.currentTimeMillis();
        private final Session session;
        private final ChannelShell channel;
        private final OutputStream stdin;
        private final int maxChars;
        private final StringBuilder pending = new StringBuilder();
        private long dropped;
        private long lastOutput = System.currentTimeMillis();
        private volatile long lastUsed = System.currentTimeMillis();
        private int readers = 2;
        /** Markierung des Befehls aus ssh_shell_exec, dessen Ende noch aussteht, sonst {@code null}. */
        volatile String running;

        private Shell(String id, SshConnection connection, boolean pty, Session session, ChannelShell channel,
                      OutputStream stdin, int maxChars) {
            this.id = id;
            this.connection = connection;
            this.pty = pty;
            this.session = session;
            this.channel = channel;
            this.stdin = stdin;
            this.maxChars = maxChars;
            channel.setOutputStream(new Sink());
            channel.setExtOutputStream(new Sink());
        }

        /**
         * Nimmt einen Ausgabestrom des Kanals entgegen. JSch schreibt stdout und stderr aus seinem Sitzungs-Thread in der
         * Reihenfolge, in der sie ankommen – direkt in den Puffer statt über je einen Lese-Thread, damit sie nicht
         * gegeneinander laufen: sonst kann stderr eines Befehls erst nach seiner Endmarkierung im Puffer landen (fehlt im
         * Ergebnis) oder vor seiner Anfangsmarkierung (wird mit dem Echo abgeschnitten).
         */
        private final class Sink extends OutputStream {
            private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
            /** Angefangenes Mehrbyte-Zeichen vom Ende des letzten Pakets. */
            private ByteBuffer rest = ByteBuffer.allocate(0);
            private boolean closed;

            @Override
            public void write(int b) {
                write(new byte[] {(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) {
                synchronized (Shell.this) {
                    if (closed) {
                        return;
                    }
                    ByteBuffer in = ByteBuffer.allocate(rest.remaining() + len).put(rest).put(b, off, len).flip();
                    CharBuffer out = CharBuffer.allocate(in.remaining());
                    decoder.decode(in, out, false);
                    rest = in.slice();
                    append(out.flip());
                }
            }

            @Override
            public void close() {
                synchronized (Shell.this) {
                    if (closed) {
                        return;
                    }
                    closed = true;
                    CharBuffer out = CharBuffer.allocate(rest.remaining() + 1);
                    decoder.decode(rest, out, true);
                    decoder.flush(out);
                    append(out.flip());
                    readers--;
                    Shell.this.notifyAll();
                }
            }
        }

        /** Hängt Ausgabe an den Puffer; bei Überlauf fällt der älteste Teil weg. Nur unter {@code this}. */
        private void append(CharSequence text) {
            if (text.isEmpty()) {
                return;
            }
            pending.append(text);
            if (pending.length() > maxChars) {
                int cut = pending.length() - maxChars;
                pending.delete(0, cut);
                dropped += cut;
            }
            lastOutput = System.currentTimeMillis();
            notifyAll();
        }

        /** Beide Ausgabeströme sind zu Ende (Shell mit exit beendet oder Verbindung weg). */
        synchronized boolean ended() {
            return readers <= 0 || channel.isClosed();
        }

        /** Exit-Code der Shell selbst, sobald sie beendet ist, sonst -1. */
        int exitStatus() {
            return channel.getExitStatus();
        }

        long lastUsed() {
            return lastUsed;
        }

        void write(String text) {
            lastUsed = System.currentTimeMillis();
            try {
                stdin.write(text.getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            } catch (IOException e) {
                throw new UncheckedIOException("Shell " + id + " nimmt keine Eingabe mehr an (beendet?).", e);
            }
        }

        /** Signal an den laufenden Prozess; nicht jeder Server unterstützt das. */
        void signal(String name) {
            try {
                channel.sendSignal(name);
            } catch (Exception ignored) {
                // best effort – mit PTY wirkt ohnehin das Steuerzeichen
            }
        }

        /**
         * Wartet auf Ausgabe und holt sie ab. Ende des Wartens: {@code until} gefunden; oder – ohne {@code until} – nach
         * {@code idleMillis} Ruhe, sobald es neue Ausgabe gibt; oder die Shell ist beendet; spätestens nach
         * {@code waitMillis}.
         *
         * @return abgeholte Ausgabe (roh, noch mit Steuersequenzen) und ob {@code until} gefunden wurde
         */
        Chunk collect(long waitMillis, long idleMillis, Pattern until) throws InterruptedException {
            lastUsed = System.currentTimeMillis();
            long deadline = System.currentTimeMillis() + Math.max(0, waitMillis);
            synchronized (this) {
                boolean matched = false;
                while (true) {
                    long now = System.currentTimeMillis();
                    if (until != null && until.matcher(pending).find()) {
                        matched = true;
                        break;
                    }
                    if (ended() || now >= deadline) {
                        break;
                    }
                    if (until == null && !pending.isEmpty() && now - lastOutput >= idleMillis) {
                        break;
                    }
                    if (ToolProgress.due()) {
                        String line = lastLine(pending);
                        if (!line.isEmpty()) {
                            ToolProgress.report(id + ": " + line);
                        }
                    }
                    wait(Math.max(1, Math.min(deadline - now, 50)));
                }
                String text = pending.toString();
                long lost = dropped;
                pending.setLength(0);
                dropped = 0;
                return new Chunk(text, lost, matched);
            }
        }

        void close() {
            channel.disconnect();
            session.disconnect();
        }

        /**
         * Beendet die Shell geordnet: laufenden Befehl mit ^C abbrechen (wirkt mit PTY), {@code exit} schicken; schließt
         * sich der Kanal nicht, folgt die Signal-Leiter INT → TERM → KILL. Danach wird in jedem Fall getrennt.
         *
         * @return {@code true}, wenn die Shell nachweislich beendet ist; {@code false}, wenn ein Prozess womöglich weiterläuft
         */
        boolean terminate() throws InterruptedException {
            try {
                if (!channel.isClosed()) {
                    try {
                        if (running != null && pty) {
                            write("\u0003");
                        }
                        write("exit\n");
                    } catch (UncheckedIOException ignored) {
                        // nimmt keine Eingabe mehr an – dann entscheiden die Signale
                    }
                    if (!SshEnvironment.awaitClosed(channel, 1000)) {
                        SshEnvironment.stop(channel);
                    }
                }
                return channel.isClosed();
            } finally {
                close();
            }
        }
    }

    record Chunk(String text, long dropped, boolean matched) {
    }

    private final Map<String, Shell> shells = new LinkedHashMap<>();
    private int counter;

    /** Öffnet eine Shell auf einer eigenen, bereits verbundenen Sitzung. */
    Shell open(SshConnection c, Session session, boolean pty, int maxShells, int maxChars) throws JSchException {
        synchronized (this) {
            if (shells.size() >= maxShells) {
                session.disconnect();
                throw new IllegalStateException("Bereits " + shells.size() + " Shells offen (Maximum " + maxShells
                        + ") – nicht mehr benötigte mit ssh_shell_close schließen: " + shells.keySet());
            }
        }
        ChannelShell ch = (ChannelShell) session.openChannel("shell");
        ch.setPty(pty);
        if (pty) {
            ch.setPtyType("xterm", 200, 50, 0, 0);
        }
        Shell shell;
        try {
            OutputStream in = ch.getOutputStream();
            synchronized (this) {
                shell = new Shell("sh" + (++counter), c, pty, session, ch, in, maxChars);
            }
            ch.connect(15_000);
        } catch (IOException e) {
            ch.disconnect();
            session.disconnect();
            throw new UncheckedIOException(e);
        } catch (JSchException | RuntimeException e) {
            ch.disconnect();
            session.disconnect();
            throw e;
        }
        synchronized (this) {
            shells.put(shell.id, shell);
        }
        return shell;
    }

    synchronized Shell get(String id) {
        if (id == null || id.isBlank()) {
            if (shells.size() == 1) {
                return shells.values().iterator().next();
            }
            throw new IllegalArgumentException(shells.isEmpty()
                    ? "Keine Shell offen – zuerst ssh_shell_open aufrufen."
                    : "Mehrere Shells offen " + shells.keySet() + " – 'shell' angeben.");
        }
        Shell s = shells.get(id.trim());
        if (s == null) {
            throw new IllegalArgumentException("Shell '" + id + "' ist nicht (mehr) offen. Offen: " + shells.keySet()
                    + ". Neue mit ssh_shell_open.");
        }
        return s;
    }

    synchronized List<Shell> list() {
        return new ArrayList<>(shells.values());
    }

    void close(String id) {
        Shell s;
        synchronized (this) {
            s = shells.remove(id);
        }
        if (s != null) {
            s.close();
        }
    }

    /** Beendet eine Shell geordnet (siehe {@link Shell#terminate()}); {@code true} = nachweislich beendet. */
    boolean terminate(String id) throws InterruptedException {
        Shell s;
        synchronized (this) {
            s = shells.remove(id);
        }
        return s == null || s.terminate();
    }

    /**
     * Schließt Shells, die länger als {@code idleMillis} nicht benutzt wurden. Beendete Shells bleiben bis zum nächsten
     * Lesen offen, damit ihre letzte Ausgabe nicht verloren geht.
     */
    void closeIdle(long idleMillis) {
        long now = System.currentTimeMillis();
        List<Shell> stale = new ArrayList<>();
        synchronized (this) {
            shells.values().removeIf(s -> {
                boolean old = now - s.lastUsed() > idleMillis;
                if (old) {
                    stale.add(s);
                }
                return old;
            });
        }
        stale.forEach(Shell::close);
    }

    void closeAll() {
        List<Shell> all;
        synchronized (this) {
            all = new ArrayList<>(shells.values());
            shells.clear();
        }
        all.forEach(Shell::close);
    }

    /** Letzte nicht leere Zeile ohne Steuersequenzen und Markierungen – für Fortschrittsmeldungen; sonst leer. */
    static String lastLine(CharSequence text) {
        int end = text.length();
        while (end > 0) {
            int start = end - 1;
            while (start > 0 && text.charAt(start - 1) != '\n') {
                start--;
            }
            String line = clean(text.subSequence(start, end).toString()).strip();
            if (!line.isEmpty()) {
                return line.contains("DTMCP_") ? "" : line;
            }
            end = start - 1;
        }
        return "";
    }

    /** Ausgabe für das LLM: ohne Steuersequenzen. */
    static String clean(String raw) {
        return ANSI.matcher(raw).replaceAll("");
    }
}
