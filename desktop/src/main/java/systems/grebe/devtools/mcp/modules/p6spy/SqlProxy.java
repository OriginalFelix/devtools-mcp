package systems.grebe.devtools.mcp.modules.p6spy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lokaler TCP-Proxy vor einer beliebigen Datenbank: reicht alle Bytes unverändert weiter und schreibt SQL-Text, den er
 * in den Anfragen des Clients erkennt, im p6spy-{@code SingleLineFormat} in ein Protokoll – lesbar mit
 * {@link P6SpyTools}. Das Wire-Protokoll wird nicht verstanden: SQL wird als Text (UTF-8/Latin-1 oder UTF-16LE wie bei
 * SQL Server) gesucht, die Dauer reicht bis zum ersten Antwort-Byte der Datenbank. Mit TLS ist nichts zu sehen.
 */
final class SqlProxy implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(SqlProxy.class);
    private static final int MAX_REQUEST = 1 << 20;
    private static final int MIN_LENGTH = 6;
    // Bis zu 4 Zeichen davor: Längen-/Typ-Bytes des Protokolls, die zufällig druckbar sind (z.B. 'Q' bei PostgreSQL)
    // shortcut: Oracle zerlegt lange SQL-Texte in 64-Byte-Stücke mit Längenbyte ('@') – das steht dann mitten im SQL;
    // bei Bedarf TNS-Stücke erkennen.
    private static final Pattern START = Pattern.compile("^.{0,4}?((?i:select|insert|update|delete|merge|with|call"
            + "|exec|execute|create|alter|drop|truncate|grant|revoke|commit|rollback|set|show|begin|declare|lock"
            + "|explain|values)(?=[\\s(;]|$))", Pattern.DOTALL);
    private static final Pattern SPACE = Pattern.compile("\\s+");

    private final ServerSocket server;
    private final InetSocketAddress target;
    private final Path log;
    private final AtomicInteger connections = new AtomicInteger();
    private final AtomicLong statements = new AtomicLong();
    private final Set<Socket> open = ConcurrentHashMap.newKeySet();

    /** @param port lokaler Port, 0 = frei wählen; gebunden wird nur an localhost */
    SqlProxy(int port, InetSocketAddress target, Path log) throws IOException {
        this.target = target;
        this.log = log;
        Files.createDirectories(log.toAbsolutePath().getParent());
        if (!Files.exists(log)) {
            Files.createFile(log);
        }
        server = new ServerSocket(port, 50, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().name("sql-proxy-" + port()).start(this::accept);
    }

    int port() {
        return server.getLocalPort();
    }

    InetSocketAddress target() {
        return target;
    }

    Path log() {
        return log;
    }

    int connections() {
        return connections.get();
    }

    long statements() {
        return statements.get();
    }

    @Override
    public void close() {
        closeQuietly(server);
        open.forEach(SqlProxy::closeQuietly);
    }

    private void accept() {
        while (!server.isClosed()) {
            try {
                Socket client = server.accept();
                Thread.ofVirtual().start(() -> forward(client));
            } catch (IOException e) {
                // geschlossen
            }
        }
    }

    private void forward(Socket client) {
        int id = connections.incrementAndGet();
        Socket db = new Socket();
        open.add(client);
        open.add(db);
        try {
            // je Verbindung neu auflösen: target kann unaufgelöst sein, die IP sich ändern
            db.connect(new InetSocketAddress(target.getHostString(), target.getPort()), 10_000);
            Request request = new Request();
            Thread up = Thread.ofVirtual().start(() -> pump(client, db, request::add));
            pump(db, client, (b, n) -> request.answered(id));
            up.join();
        } catch (IOException e) {
            LOG.debug("SQL-Proxy {}: Verbindung zu {} fehlgeschlagen", port(), target, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closeQuietly(client);
            closeQuietly(db);
            open.remove(client);
            open.remove(db);
        }
    }

    private interface Seen {
        void accept(byte[] buf, int n);
    }

    /** Kopiert bis eine Seite schließt, dann beide zu – die Gegenrichtung endet damit auch. */
    private static void pump(Socket from, Socket to, Seen seen) {
        byte[] buf = new byte[64 * 1024];
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            for (int n; (n = in.read(buf)) >= 0; ) {
                seen.accept(buf, n);
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException e) {
            // Verbindung beendet
        } finally {
            closeQuietly(from);
            closeQuietly(to);
        }
    }

    /** Was der Client seit der letzten Antwort geschickt hat; Anfrage und Antwort wechseln sich ab. */
    private final class Request {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private long start;

        synchronized void add(byte[] buf, int n) {
            if (bytes.size() == 0) {
                start = System.currentTimeMillis();
            }
            bytes.write(buf, 0, Math.min(n, MAX_REQUEST - bytes.size()));
        }

        void answered(int connection) {
            long begin;
            byte[] sent;
            synchronized (this) {
                if (bytes.size() == 0) {
                    return;
                }
                begin = start;
                sent = bytes.toByteArray();
                bytes.reset();
            }
            write(connection, begin, System.currentTimeMillis() - begin, find(sent));
        }
    }

    private void write(int connection, long start, long millis, List<String> sql) {
        if (sql.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String s : sql) {
            String word = s.split("[\\s(;]", 2)[0].toLowerCase();
            String category = word.equals("commit") || word.equals("rollback") ? word : "statement";
            // prepared und sql gleich: der Parser trennt dann auch bei || im SQL richtig
            sb.append(start).append('|').append(millis).append('|').append(category).append("|connection ")
                    .append(connection).append("|url ").append(target.getHostString()).append(':')
                    .append(target.getPort()).append('|').append(s).append('|').append(s).append('\n');
        }
        statements.addAndGet(sql.size());
        try {
            synchronized (this) {
                Files.writeString(log, sb, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            LOG.warn("SQL-Proxy {}: Protokoll {} nicht schreibbar", port(), log, e);
        }
    }

    /** SQL-Texte in den Bytes einer Anfrage, einzeilig. */
    static List<String> find(byte[] b) {
        List<String> found = new ArrayList<>();
        // 8-Bit-Text (UTF-8, Latin-1 …): druckbare Bytes und alles ab 0x80
        int from = -1;
        for (int i = 0; i <= b.length; i++) {
            boolean text = i < b.length && (b[i] >= 0x20 && b[i] != 0x7f || b[i] < 0 || b[i] == '\t'
                    || b[i] == '\n' || b[i] == '\r');
            if (text && from < 0) {
                from = i;
            } else if (!text && from >= 0) {
                add(found, new String(b, from, i - from, StandardCharsets.UTF_8));
                from = -1;
            }
        }
        // UTF-16LE (SQL Server): Zeichen bis U+00FF, jedes gefolgt von 0
        StringBuilder wide = new StringBuilder();
        for (int i = 0; i < b.length; ) {
            int c = b[i] & 0xff;
            if (i + 1 < b.length && b[i + 1] == 0 && (c >= 0x20 && c != 0x7f || c == '\t' || c == '\n' || c == '\r')) {
                wide.append((char) c);
                i += 2;
            } else {
                add(found, wide.toString());
                wide.setLength(0);
                i++;
            }
        }
        add(found, wide.toString());
        return found;
    }

    private static void add(List<String> found, String run) {
        if (run.length() < MIN_LENGTH) {
            return;
        }
        var m = START.matcher(run);
        if (m.find()) {
            String sql = SPACE.matcher(run.substring(m.start(1))).replaceAll(" ").strip();
            if (sql.length() >= MIN_LENGTH) {
                found.add(sql);
            }
        }
    }

    private static void closeQuietly(AutoCloseable c) {
        try {
            c.close();
        } catch (Exception e) {
            // egal
        }
    }
}
