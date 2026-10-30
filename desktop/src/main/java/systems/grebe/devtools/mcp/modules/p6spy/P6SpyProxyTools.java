package systems.grebe.devtools.mcp.modules.p6spy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.LocalFiles;
import systems.grebe.devtools.mcp.core.ToolHints;

/** SQL-Proxys vor einer Datenbank starten und stoppen; ihr Protokoll lesen {@link P6SpyTools}. */
@ToolHints(destructive = false)
public class P6SpyProxyTools {

    /** Laufende Proxys je Benutzer/Profil (lokaler Port → Proxy); schließt mit dem Scope. */
    static final class Proxies implements AutoCloseable {
        final Map<Integer, SqlProxy> byPort = new ConcurrentSkipListMap<>();

        @Override
        public void close() {
            byPort.values().forEach(SqlProxy::close);
            byPort.clear();
        }
    }

    private final LocalFiles files;
    private final Proxies proxies;

    P6SpyProxyTools(LocalFiles files, Proxies proxies) {
        this.files = files;
        this.proxies = proxies;
    }

    @Tool(name = "proxy_start", description = "Startet einen SQL-Proxy vor einer beliebigen Datenbank, ohne p6spy in "
            + "die Anwendung einzubauen: die Anwendung verbindet sich statt zur Datenbank zu localhost:<port>, der "
            + "Proxy reicht alles unverändert weiter und schreibt erkanntes SQL im p6spy-Format in ein Protokoll – "
            + "auswerten mit p6spy_statements/p6spy_analyze. Nur ohne TLS (z.B. sslmode=disable, useSSL=false, "
            + "encrypt=false); Werte von Prepared Statements sind nicht zu sehen, die Dauer reicht bis zur ersten "
            + "Antwort der Datenbank.")
    public String proxyStart(
            @ToolParam(description = "Datenbank als host:port, z.B. localhost:5432 oder db.intern:1521") String target,
            @ToolParam(description = "Protokolldatei oder Verzeichnis dafür (in den freigegebenen Verzeichnissen); "
                    + "ein Verzeichnis bekommt spy-proxy-<host>-<port>.log") String logFile,
            @ToolParam(required = false, description = "Lokaler Port (Standard: frei gewählt)") Integer port) {
        InetSocketAddress address = address(target);
        Path log = files.writable(logFile, "spy-proxy-" + address.getHostString().replaceAll("[^\\w.-]", "_") + "-"
                + address.getPort() + ".log");
        SqlProxy proxy;
        try {
            proxy = new SqlProxy(port == null ? 0 : port, address, log);
        } catch (IOException e) {
            throw new UncheckedIOException("SQL-Proxy nicht gestartet: " + e.getMessage(), e);
        }
        proxies.byPort.put(proxy.port(), proxy);
        return "SQL-Proxy läuft auf localhost:" + proxy.port() + " → " + target.strip() + ", Protokoll " + log
                + "\nIn der Anwendung Host/Port der Datenbank durch localhost:" + proxy.port() + " ersetzen (TLS aus)."
                + "\n\n" + list();
    }

    @Tool(name = "proxy_stop", description = "Stoppt einen mit p6spy_proxy_start gestarteten SQL-Proxy (offene "
            + "Verbindungen der Anwendung brechen ab) oder ohne port alle. Das Protokoll bleibt erhalten.")
    public String proxyStop(
            @ToolParam(required = false, description = "Lokaler Port des Proxys (Standard: alle)") Integer port) {
        if (port == null) {
            int n = proxies.byPort.size();
            proxies.close();
            return n + " SQL-Proxy(s) gestoppt.";
        }
        SqlProxy proxy = proxies.byPort.remove(port);
        if (proxy == null) {
            throw new IllegalArgumentException("Kein SQL-Proxy auf Port " + port + ".\n" + list());
        }
        proxy.close();
        return "SQL-Proxy auf Port " + port + " gestoppt.\n" + list();
    }

    private String list() {
        if (proxies.byPort.isEmpty()) {
            return "Keine SQL-Proxys aktiv.";
        }
        StringBuilder sb = new StringBuilder("Aktive SQL-Proxys:");
        proxies.byPort.values().forEach(p -> sb.append("\nlocalhost:").append(p.port()).append(" → ")
                .append(p.target().getHostString()).append(':').append(p.target().getPort()).append("  ")
                .append(p.connections()).append(" Verbindungen, ").append(p.statements()).append(" Statements, ")
                .append(p.log()));
        return sb.toString();
    }

    private static InetSocketAddress address(String target) {
        String t = target == null ? "" : target.strip();
        int colon = t.lastIndexOf(':');
        if (colon <= 0) {
            throw new IllegalArgumentException("target als host:port angeben, z.B. localhost:5432 – nicht " + target);
        }
        String host = t.substring(0, colon).replaceAll("^\\[|]$", "");
        int port;
        try {
            port = Integer.parseInt(t.substring(colon + 1));
        } catch (NumberFormatException e) {
            port = -1;
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Ungültiger Port in " + target);
        }
        return InetSocketAddress.createUnresolved(host, port);
    }
}
