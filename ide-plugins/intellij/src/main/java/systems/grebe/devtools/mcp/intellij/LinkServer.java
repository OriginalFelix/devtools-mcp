package systems.grebe.devtools.mcp.intellij;

import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import com.google.gson.JsonObject;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Lokaler HTTP-Server des Plugins, nur auf 127.0.0.1 und nur mit Zugriffstoken:
 * <ul>
 *   <li>{@code GET /editor}, {@code POST /cursor} (Formular) – für die DevTools-App; Port und Token stehen in
 *   {@code ~/.devtools-mcp/ide/<pid>.json} (nur für den Nutzer lesbar).</li>
 *   <li>{@code POST /mcp} – MCP über Streamable HTTP ({@link McpEndpoint}) für Clients wie Claude Code.</li>
 * </ul>
 * Port und Token bleiben über Neustarts gleich, damit eine eingetragene MCP-Verbindung gültig bleibt; ist der Port
 * belegt (zweite IDE), nimmt der Server einen freien.
 */
@Service
public final class LinkServer implements Disposable {

    private static final Logger LOG = Logger.getInstance(LinkServer.class);
    private static final String PORT_KEY = "devtools.mcp.port";
    private static final String TOKEN_KEY = "devtools.mcp.token";

    private final HttpServer server;
    private final String token;
    private final Path lock;

    public LinkServer() {
        PropertiesComponent props = PropertiesComponent.getInstance();
        String t = props.getValue(TOKEN_KEY);
        if (t == null || t.length() < 32) {
            byte[] b = new byte[32];
            new SecureRandom().nextBytes(b);
            t = HexFormat.of().formatHex(b);
            props.setValue(TOKEN_KEY, t);
        }
        token = t;
        try {
            server = bind(props.getInt(PORT_KEY, 0));
            props.setValue(PORT_KEY, server.getAddress().getPort(), 0);
            server.createContext("/editor", ex -> handle(ex, "GET", q -> EditorState.json().toString()));
            server.createContext("/cursor", ex -> handle(ex, "POST", q -> AgentCursor.getInstance().apply(form(q))));
            server.createContext("/mcp", ex -> handle(ex, "POST", McpEndpoint::handle));
            server.setExecutor(AppExecutorUtil.getAppExecutorService());
            server.start();
            lock = writeLock();
        } catch (IOException e) {
            throw new IllegalStateException("DevTools MCP: lokaler Server startet nicht", e);
        }
        LOG.info("DevTools MCP: http://127.0.0.1:" + port() + "/mcp");
    }

    public static LinkServer getInstance() {
        return ApplicationManager.getApplication().getService(LinkServer.class);
    }

    int port() {
        return server.getAddress().getPort();
    }

    String token() {
        return token;
    }

    private static HttpServer bind(int port) throws IOException {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        try {
            return HttpServer.create(new InetSocketAddress(loopback, port), 0);
        } catch (BindException e) {
            return HttpServer.create(new InetSocketAddress(loopback, 0), 0);
        }
    }

    private void handle(HttpExchange ex, String method, Function<String, String> action) throws IOException {
        try (ex) {
            int status;
            String body;
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            // Origin: Anfragen aus dem Browser (DNS-Rebinding, CSRF) – die App und MCP-Clients senden keinen
            if (ex.getRequestHeaders().containsKey("Origin") || auth == null || !MessageDigest.isEqual(
                    auth.getBytes(StandardCharsets.UTF_8), ("Bearer " + token).getBytes(StandardCharsets.UTF_8))) {
                status = 401;
                body = "kein Zugriff";
            } else if (!method.equals(ex.getRequestMethod())) {
                status = 405;
                body = "nur " + method;
            } else {
                try {
                    body = action.apply(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    status = body == null ? 202 : 200;
                } catch (IllegalArgumentException e) {
                    status = 400;
                    body = e.getMessage();
                } catch (RuntimeException e) {
                    LOG.warn(e);
                    status = 500;
                    body = String.valueOf(e.getMessage());
                }
            }
            if (body == null) {
                ex.sendResponseHeaders(status, -1);
                return;
            }
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", (body.startsWith("{") ? "application/json" : "text/plain")
                    + "; charset=utf-8");
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
        }
    }

    static Map<String, String> form(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private Path writeLock() throws IOException {
        Path dir = Path.of(System.getProperty("user.home"), ".devtools-mcp", "ide");
        Files.createDirectories(dir);
        Path file = dir.resolve(ProcessHandle.current().pid() + ".json");
        Files.deleteIfExists(file);
        try {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException e) {
            Files.createFile(file); // Windows: das Benutzerprofil schützt die Datei
        }
        JsonObject o = new JsonObject();
        o.addProperty("pid", ProcessHandle.current().pid());
        o.addProperty("port", port());
        o.addProperty("token", token);
        o.addProperty("ide", ApplicationInfo.getInstance().getFullApplicationName());
        Files.writeString(file, o.toString());
        return file;
    }

    @Override
    public void dispose() {
        server.stop(0);
        try {
            Files.deleteIfExists(lock);
        } catch (IOException ignored) {
            // bleibt liegen; die App erkennt den beendeten Prozess
        }
    }
}
