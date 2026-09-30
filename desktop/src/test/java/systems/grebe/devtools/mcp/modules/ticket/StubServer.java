package systems.grebe.devtools.mcp.modules.ticket;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** Lokaler HTTP-Stub: Antworten je Pfad (ohne Query), protokolliert Anfragen samt Body und Kopfzeilen. */
final class StubServer implements AutoCloseable {

    record Request(String method, String path, String query, String body, Map<String, String> headers) {
        /** Dekodierter Query-String (für lesbare Assertions). */
        String decodedQuery() {
            return query == null ? "" : URLDecoder.decode(query, StandardCharsets.UTF_8);
        }
    }

    record Reply(int status, String body, Map<String, String> headers) {
        static Reply json(String body) {
            return new Reply(200, body, Map.of());
        }
    }

    private final HttpServer server;
    private final Map<String, Function<Request, Reply>> routes = new ConcurrentHashMap<>();
    final List<Request> requests = new CopyOnWriteArrayList<>();

    StubServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    StubServer on(String path, String json) {
        routes.put(path, r -> Reply.json(json));
        return this;
    }

    StubServer on(String path, Function<Request, Reply> handler) {
        routes.put(path, handler);
        return this;
    }

    Request last(String path) {
        return requests.reversed().stream().filter(r -> r.path().equals(path)).findFirst()
                .orElseThrow(() -> new AssertionError("keine Anfrage an " + path + " – angefragt: "
                        + requests.stream().map(Request::path).toList()));
    }

    private void handle(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> headers = new ConcurrentHashMap<>();
        ex.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), String.join(",", v)));
        // getRawPath: kodierte Pfadteile (gruppe%2Fprojekt) bleiben wie gesendet
        Request req = new Request(ex.getRequestMethod(), ex.getRequestURI().getRawPath(), ex.getRequestURI().getRawQuery(),
                body, headers);
        requests.add(req);
        Function<Request, Reply> h = routes.get(req.path());
        Reply reply = h == null ? new Reply(404, "{\"message\":\"404 Not Found\"}", Map.of()) : h.apply(req);
        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
        reply.headers().forEach((k, v) -> ex.getResponseHeaders().add(k, v));
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
        ex.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
