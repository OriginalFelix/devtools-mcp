package systems.grebe.devtools.mcp.modules.ssh;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.UIKeyboardInteractive;
import com.jcraft.jsch.UserInfo;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolProgress;
import systems.grebe.devtools.mcp.core.LocalFiles;

/** Ausgewertete Konfiguration des SSH-Moduls: Verbindungen, Host-Key-Prüfung, Grenzen – und die Ausführung selbst. */
final class SshEnvironment {

    /** Ergebnis eines entfernten Befehls; Ausgaben sind auf {@code maxBytes} begrenzt. */
    /** @param stopped nach einer Zeitüberschreitung: ob der Prozess nachweislich beendet wurde */
    record ExecResult(int exitCode, String stdout, String stderr, boolean timedOut, boolean stopped, boolean truncated,
                      long millis) {
    }

    @FunctionalInterface
    interface SftpAction<T> {
        T run(ChannelSftp sftp) throws SftpException, IOException;
    }

    private final Map<String, SshConnection> connections = new LinkedHashMap<>();
    private final List<String> duplicates = new ArrayList<>();
    private final SshSessions sessions;
    private final SshShells shells;
    private final Path knownHosts;
    private final boolean acceptNewHostKeys;
    private final Duration connectTimeout;
    private final int maxExecSeconds;
    private final int maxLines;
    private final int maxBytes;
    private final int maxShells;
    private final long shellIdleMillis;
    private final List<Path> localRoots = new ArrayList<>();
    private final KnownHostsProvider hostKeyStores;

    /** Liefert den gemeinsamen {@link KnownHostsStore} zu einer known_hosts-Datei. */
    @FunctionalInterface
    interface KnownHostsProvider {
        KnownHostsStore open(Path file) throws JSchException;
    }

    SshEnvironment(ModuleConfig c, SshSessions sessions, SshShells shells, Path defaultKnownHosts,
                   KnownHostsProvider hostKeyStores) {
        this.hostKeyStores = hostKeyStores;
        this.sessions = sessions;
        this.shells = shells;
        this.maxShells = Math.max(1, c.getInt(SshModule.MAX_SHELLS, 5));
        this.shellIdleMillis = Math.max(1, c.getInt(SshModule.SHELL_IDLE_MINUTES, 30)) * 60_000L;
        for (String dir : c.getList(SshModule.LOCAL_DIRS)) {
            try {
                Path root = Path.of(LocalFiles.expandHome(dir)).toAbsolutePath().normalize();
                if (Files.isDirectory(root)) {
                    localRoots.add(root);
                }
            } catch (java.nio.file.InvalidPathException ignored) {
                // ungültig -> ignorieren, validate() meldet es in der App
            }
        }
        for (Map<String, String> r : c.getRecords(SshModule.CONNECTIONS)) {
            SshConnection conn = SshConnection.of(r);
            if (conn.name().isEmpty()) {
                continue;
            }
            String key = conn.name().toLowerCase(Locale.ROOT);
            if (connections.putIfAbsent(key, conn) != null) {
                duplicates.add(conn.name());
            }
        }
        String file = c.getString(SshModule.KNOWN_HOSTS, "");
        this.knownHosts = file.isBlank() ? defaultKnownHosts : Path.of(LocalFiles.expandHome(file));
        this.acceptNewHostKeys = !"strict".equals(c.getString(SshModule.HOST_KEY_POLICY, "accept-new"));
        this.connectTimeout = Duration.ofSeconds(Math.max(3, c.getInt(SshModule.CONNECT_TIMEOUT, 15)));
        this.maxExecSeconds = Math.max(1, c.getInt(SshModule.MAX_EXEC_SECONDS, 300));
        this.maxLines = Math.max(50, c.getInt(SshModule.MAX_LINES, 400));
        this.maxBytes = Math.max(64 * 1024, c.getInt(SshModule.MAX_OUTPUT_KB, 1024) * 1024);
    }

    // ------------------------------------------------------------------ Verbindungen

    List<SshConnection> connections() {
        return List.copyOf(connections.values());
    }

    boolean isOpen(SshConnection c) {
        return sessions.isOpen(c.name());
    }

    /** Trennt die wiederverwendete Sitzung der Verbindung; {@code true}, wenn eine offen war. Shells bleiben offen. */
    boolean disconnect(SshConnection c) {
        return sessions.evict(c.name());
    }

    /** Verbindung nach Name (ohne Groß-/Kleinschreibung); ohne Name die einzige konfigurierte. */
    SshConnection resolve(String name) {
        if (connections.isEmpty()) {
            throw new IllegalStateException("Keine SSH-Verbindung konfiguriert – in der DevTools-App unter Module → SSH "
                    + "eine Verbindung anlegen (Name, Host, Port, Benutzer, Passwort).");
        }
        if (name == null || name.isBlank()) {
            if (connections.size() == 1) {
                return connections.values().iterator().next();
            }
            throw new IllegalArgumentException("Mehrere SSH-Verbindungen konfiguriert – 'connection' angeben: "
                    + names() + " (siehe ssh_connections).");
        }
        String key = name.trim().toLowerCase(Locale.ROOT);
        if (duplicates.stream().anyMatch(d -> d.equalsIgnoreCase(key))) {
            throw new IllegalStateException("Der Verbindungsname '" + name + "' ist mehrfach vergeben – der Nutzer muss "
                    + "ihn in der DevTools-App eindeutig machen.");
        }
        SshConnection c = connections.get(key);
        if (c == null) {
            throw new IllegalArgumentException("Unbekannte SSH-Verbindung '" + name + "'. Konfiguriert: " + names()
                    + ". Neue Verbindungen legt der Nutzer in der DevTools-App an.");
        }
        return c;
    }

    private List<String> names() {
        return connections.values().stream().map(SshConnection::name).toList();
    }

    List<String> duplicates() {
        return List.copyOf(duplicates);
    }

    Path knownHosts() {
        return knownHosts;
    }

    boolean acceptNewHostKeys() {
        return acceptNewHostKeys;
    }

    int maxExecSeconds() {
        return maxExecSeconds;
    }

    int maxLines() {
        return maxLines;
    }

    int maxBytes() {
        return maxBytes;
    }

    SshShells shells() {
        return shells;
    }

    /**
     * Lokaler Pfad innerhalb der freigegebenen Verzeichnisse. Relativ nur, wenn genau eines freigegeben ist. Geprüft wird
     * auch der echte Pfad des nächsten vorhandenen Vorfahren, damit ein Symlink nicht aus der Freigabe herausführt.
     */
    Path localPath(String path) {
        if (localRoots.isEmpty()) {
            throw new IllegalStateException("Kein lokales Verzeichnis für Übertragungen freigegeben – der Nutzer kann es in "
                    + "der DevTools-App unter Module → SSH → 'Lokale Verzeichnisse für Übertragungen' eintragen.");
        }
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Lokaler Pfad fehlt. Freigegeben: " + localRoots);
        }
        Path p;
        try {
            p = Path.of(LocalFiles.expandHome(path));
        } catch (java.nio.file.InvalidPathException e) {
            throw new IllegalArgumentException("Ungültiger lokaler Pfad: " + path);
        }
        if (!p.isAbsolute()) {
            if (localRoots.size() != 1) {
                throw new IllegalArgumentException("Mehrere lokale Verzeichnisse freigegeben – absoluten Pfad angeben: "
                        + localRoots);
            }
            p = localRoots.getFirst().resolve(p);
        }
        Path target = p.toAbsolutePath().normalize();
        for (Path root : localRoots) {
            if (target.startsWith(root) && LocalFiles.realPathInside(target, root)) {
                return target;
            }
        }
        throw new IllegalArgumentException("Lokaler Pfad " + target + " ist nicht freigegeben. Freigegeben: " + localRoots);
    }

    int maxShells() {
        return maxShells;
    }

    long shellIdleMillis() {
        return shellIdleMillis;
    }

    // ------------------------------------------------------------------ Sitzungen

    /** Baut eine neue Sitzung auf (ohne Pool) – für die Verbindungsprüfung. */
    Session open(SshConnection c) throws JSchException {
        if (c.host().isEmpty() || c.username().isEmpty()) {
            throw new IllegalStateException("Verbindung '" + c.name() + "': Host und Benutzer sind Pflicht.");
        }
        if (c.password() == null && c.privateKey() == null) {
            throw new IllegalStateException("Verbindung '" + c.name() + "': weder Passwort noch Schlüsseldatei gesetzt.");
        }
        JSch jsch = new JSch();
        jsch.setHostKeyRepository(new TofuHostKeys(hostKeyStores.open(knownHosts), acceptNewHostKeys));
        if (c.privateKey() != null) {
            String keyFile = LocalFiles.expandHome(c.privateKey());
            if (!Files.isRegularFile(Path.of(keyFile))) {
                throw new IllegalStateException("Verbindung '" + c.name() + "': Schlüsseldatei nicht gefunden: " + keyFile);
            }
            jsch.addIdentity(keyFile, c.passphrase());
        }
        Session s = jsch.getSession(c.username(), c.host(), c.port());
        if (c.password() != null) {
            s.setPassword(c.password());
        }
        s.setUserInfo(new Credentials(c));
        s.setConfig("StrictHostKeyChecking", "yes"); // unbekannte Hosts entscheidet TofuHostKeys
        s.setConfig("PreferredAuthentications", c.privateKey() != null
                ? "publickey,keyboard-interactive,password" : "keyboard-interactive,password");
        s.setServerAliveInterval(30_000);
        s.setServerAliveCountMax(4);
        try {
            s.connect((int) connectTimeout.toMillis());
        } catch (JSchException e) {
            throw new IllegalStateException(describe(c, e), e);
        }
        return s;
    }

    /**
     * Führt {@code action} mit einer (wiederverwendeten) Sitzung aus. Ist die gemerkte Sitzung inzwischen tot (Server neu
     * gestartet, Netzwerk), wird einmal neu verbunden.
     */
    <T> T withSession(SshConnection c, SessionAction<T> action) {
        for (int attempt = 0; ; attempt++) {
            Session s;
            try {
                s = sessions.get(c, this::open);
            } catch (JSchException e) {
                throw new IllegalStateException(describe(c, e), e);
            }
            try {
                return action.run(s);
            } catch (JSchException e) {
                sessions.evict(c.name());
                if (attempt == 0 && !s.isConnected()) {
                    continue;
                }
                throw new IllegalStateException(describe(c, e), e);
            }
        }
    }

    @FunctionalInterface
    interface SessionAction<T> {
        T run(Session session) throws JSchException;
    }

    // ------------------------------------------------------------------ Befehle

    ExecResult exec(SshConnection c, String command, String stdin, int timeoutSeconds) {
        return withSession(c, s -> {
            ChannelExec ch = (ChannelExec) s.openChannel("exec");
            Capture out = new Capture(maxBytes);
            Capture err = new Capture(maxBytes);
            long start = System.nanoTime();
            boolean timedOut = false;
            boolean stopped = true;
            try {
                ch.setCommand(command.getBytes(StandardCharsets.UTF_8));
                // Immer ein Eingabestrom, damit der Befehl EOF sieht und nicht auf stdin wartet
                ch.setInputStream(new ByteArrayInputStream(stdin == null ? new byte[0] : stdin.getBytes(StandardCharsets.UTF_8)));
                ch.setOutputStream(out, true);
                ch.setErrStream(err, true);
                ch.connect((int) connectTimeout.toMillis());
                long deadline = start + Duration.ofSeconds(timeoutSeconds).toNanos();
                while (!ch.isClosed()) {
                    if (System.nanoTime() > deadline) {
                        timedOut = true;
                        stopped = stop(ch);
                        break;
                    }
                    if (ToolProgress.due()) {
                        String line = SshShells.lastLine(out.text());
                        if (!line.isEmpty()) {
                            ToolProgress.report(c.name() + ": " + line);
                        }
                    }
                    Thread.sleep(25);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Abgebrochen.", e);
            } finally {
                ch.disconnect();
            }
            long millis = (System.nanoTime() - start) / 1_000_000;
            return new ExecResult(timedOut ? -1 : ch.getExitStatus(), out.text(), err.text(), timedOut, stopped,
                    out.truncated() || err.truncated(), millis);
        });
    }

    /**
     * Beendet den Prozess eines Kanals stufenweise: INT, TERM, KILL, nach jeder Stufe kurz warten, ob sich der Kanal
     * schließt. Nicht jeder Server nimmt Signale an (OpenSSH erst ab 7.9 zuverlässig, Dropbear gar nicht, Forced
     * Commands nie) – deshalb wird das Ergebnis geprüft und nicht angenommen.
     *
     * @return {@code true}, wenn der Kanal danach geschlossen ist, der Prozess also beendet
     */
    static boolean stop(com.jcraft.jsch.Channel ch) throws InterruptedException {
        for (String signal : List.of("INT", "TERM", "KILL")) {
            if (ch.isClosed()) {
                return true;
            }
            try {
                ch.sendSignal(signal);
            } catch (Exception e) {
                return ch.isClosed(); // Signal nicht zustellbar – weitere Stufen ebenso wenig
            }
            if (awaitClosed(ch, 700)) {
                return true;
            }
        }
        return ch.isClosed();
    }

    /** Wartet bis zu {@code millis}, dass sich der Kanal schließt. */
    static boolean awaitClosed(com.jcraft.jsch.Channel ch, long millis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + millis;
        while (!ch.isClosed() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        return ch.isClosed();
    }

    // ------------------------------------------------------------------ SFTP

    <T> T sftp(SshConnection c, SftpAction<T> action) {
        return withSession(c, s -> {
            ChannelSftp sftp = (ChannelSftp) s.openChannel("sftp");
            try {
                sftp.connect((int) connectTimeout.toMillis());
                return action.run(sftp);
            } catch (SftpException e) {
                throw new IllegalStateException(describeSftp(e), e);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } finally {
                sftp.disconnect();
            }
        });
    }

    // ------------------------------------------------------------------ Meldungen

    /** Verständliche Meldung für das LLM; nennt nie Zugangsdaten. */
    String describe(SshConnection c, JSchException e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String where = "SSH '" + c.name() + "' (" + c.target() + "): ";
        if (msg.startsWith("Auth fail") || msg.contains("Auth cancel")) {
            return where + "Anmeldung fehlgeschlagen – Benutzer, Passwort bzw. Schlüssel prüfen (in der DevTools-App).";
        }
        if (msg.startsWith("UnknownHostKey") || msg.startsWith("reject HostKey")) {
            return where + "Host-Key unbekannt und Host-Key-Prüfung ist 'strict'. Der Nutzer kann in der "
                    + "DevTools-App 'Verbindung testen' mit Prüfung 'accept-new' ausführen oder den Schlüssel in "
                    + knownHosts + " eintragen.";
        }
        if (msg.startsWith("HostKey has been changed") || msg.contains("REMOTE HOST IDENTIFICATION HAS CHANGED")) {
            return where + "Der Host-Key hat sich geändert – möglicher Man-in-the-Middle-Angriff, Verbindung abgelehnt. "
                    + "Ist die Änderung erwartet (Server neu aufgesetzt), muss der Nutzer den alten Eintrag aus "
                    + knownHosts + " entfernen.";
        }
        if (msg.contains("timeout") || msg.contains("timed out")) {
            return where + "Zeitüberschreitung beim Verbinden (" + connectTimeout.toSeconds() + " s).";
        }
        if (e.getCause() instanceof java.net.UnknownHostException) {
            return where + "Host nicht gefunden.";
        }
        if (e.getCause() instanceof java.net.ConnectException) {
            return where + "Verbindung abgelehnt – läuft dort ein SSH-Server auf Port " + c.port() + "?";
        }
        return where + msg;
    }

    private static String describeSftp(SftpException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        return switch (e.id) {
            case ChannelSftp.SSH_FX_NO_SUCH_FILE -> "Datei oder Verzeichnis nicht gefunden" + suffix(msg);
            case ChannelSftp.SSH_FX_PERMISSION_DENIED -> "Keine Berechtigung" + suffix(msg);
            default -> "SFTP-Fehler " + e.id + suffix(msg);
        };
    }

    private static String suffix(String msg) {
        return msg.isBlank() ? "." : ": " + msg;
    }

    // ------------------------------------------------------------------ intern

    /** Antwortet auf Passwort- und keyboard-interactive-Abfragen mit den hinterlegten Zugangsdaten. */
    private record Credentials(SshConnection c) implements UserInfo, UIKeyboardInteractive {
        @Override
        public String getPassphrase() {
            return c.passphrase();
        }

        @Override
        public String getPassword() {
            return c.password();
        }

        @Override
        public boolean promptPassword(String message) {
            return c.password() != null;
        }

        @Override
        public boolean promptPassphrase(String message) {
            return c.passphrase() != null;
        }

        @Override
        public boolean promptYesNo(String message) {
            return false; // Host-Keys entscheidet TofuHostKeys, sonst nichts bestätigen
        }

        @Override
        public void showMessage(String message) {
        }

        @Override
        public String[] promptKeyboardInteractive(String destination, String name, String instruction, String[] prompt,
                                                  boolean[] echo) {
            if (c.password() == null || prompt.length == 0) {
                return prompt.length == 0 ? new String[0] : null;
            }
            String[] answers = new String[prompt.length];
            java.util.Arrays.fill(answers, c.password());
            return answers;
        }
    }

    /** Sammelt Ausgaben bis {@code limit} Bytes, verwirft den Rest und merkt sich das. */
    private static final class Capture extends OutputStream {
        private final java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        private final int limit;
        private boolean truncated;

        Capture(int limit) {
            this.limit = limit;
        }

        @Override
        public synchronized void write(int b) {
            if (buf.size() < limit) {
                buf.write(b);
            } else {
                truncated = true;
            }
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            int room = Math.max(0, limit - buf.size());
            buf.write(b, off, Math.min(room, len));
            truncated |= len > room;
        }

        synchronized String text() {
            return buf.toString(StandardCharsets.UTF_8);
        }

        synchronized boolean truncated() {
            return truncated;
        }
    }
}
