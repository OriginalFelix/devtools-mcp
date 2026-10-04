package systems.grebe.devtools.mcp.modules.chat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Function;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** Lokaler HTTP-Stub für Chat-Provider: Antworten je Pfad-Präfix (längster gewinnt), protokolliert alle Anfragen. */
final class HttpStub implements AutoCloseable {

    record Request(String method, String path, Map<String, String> query, String body, Map<String, String> headers) {
        /** Formularfelder eines {@code application/x-www-form-urlencoded}-Bodys. */
        Map<String, String> form() {
            return decode(body);
        }
    }

    record Reply(int status, String body) {
        static Reply json(String body) {
            return new Reply(200, body);
        }
    }

    private final HttpServer server;
    private final Map<String, Function<Request, Reply>> routes = new ConcurrentHashMap<>();
    final List<Request> requests = new CopyOnWriteArrayList<>();

    HttpStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    HttpStub on(String prefix, String json) {
        routes.put(prefix, r -> Reply.json(json));
        return this;
    }

    HttpStub on(String prefix, Function<Request, Reply> handler) {
        routes.put(prefix, handler);
        return this;
    }

    List<Request> all(String prefix) {
        return requests.stream().filter(r -> r.path().startsWith(prefix)).toList();
    }

    Request last(String prefix) {
        List<Request> found = all(prefix);
        if (found.isEmpty()) {
            throw new AssertionError("keine Anfrage an " + prefix + " – angefragt: "
                    + requests.stream().map(r -> r.method() + " " + r.path()).toList());
        }
        return found.getLast();
    }

    private void handle(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> headers = new ConcurrentHashMap<>();
        ex.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), String.join(",", v)));
        Request req = new Request(ex.getRequestMethod(), ex.getRequestURI().getRawPath(),
                decode(ex.getRequestURI().getRawQuery()), body, headers);
        requests.add(req);
        Function<Request, Reply> h = routes.entrySet().stream()
                .filter(e -> req.path().startsWith(e.getKey()))
                .max(Comparator.comparingInt(e -> e.getKey().length()))
                .map(Map.Entry::getValue).orElse(null);
        Reply reply = h == null ? new Reply(404, "{\"errcode\":\"M_UNRECOGNIZED\",\"error\":\"unbekannt\"}") : h.apply(req);
        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
        ex.close();
    }

    private static Map<String, String> decode(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
