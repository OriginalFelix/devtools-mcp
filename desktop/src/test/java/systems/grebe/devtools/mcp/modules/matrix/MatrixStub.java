package systems.grebe.devtools.mcp.modules.matrix;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
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

/**
 * Lokaler Matrix-Homeserver für Tests: Antworten je Pfad-Präfix (längster gewinnt), {@code /sync} aus einer Folge von
 * Antworten je {@code since}. Protokolliert alle Anfragen.
 */
final class MatrixStub implements AutoCloseable {

    static final String API = "/_matrix/client/v3";
    static final String ROOM = "!room:example.org";
    static final String BOT = "@bot:example.org";

    record Request(String method, String path, Map<String, String> query, String body, Map<String, String> headers) {
    }

    record Reply(int status, String body) {
    }

    private final HttpServer server;
    private final Map<String, Function<Request, Reply>> routes = new ConcurrentHashMap<>();
    /** {@code since} (leer = erster Abruf) → Antwort; ohne Eintrag: leere Antwort mit demselben Stand. */
    private final Map<String, String> syncs = new ConcurrentHashMap<>();
    final List<Request> requests = new CopyOnWriteArrayList<>();

    MatrixStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        on(API + "/account/whoami", "{\"user_id\":\"" + BOT + "\",\"device_id\":\"DEV1\"}");
        on("/_matrix/client/versions", "{\"versions\":[\"v1.11\",\"v1.12\"]}");
        on(API + "/joined_rooms", "{\"joined_rooms\":[\"" + ROOM + "\"]}");
        on(API + "/rooms/" + enc(ROOM) + "/send/m.room.message/", "{\"event_id\":\"$sent\"}");
        on(API + "/rooms/" + enc(ROOM) + "/send/m.reaction/", "{\"event_id\":\"$reaction\"}");
        on(API + "/rooms/" + enc(ROOM) + "/receipt/", "{}");
        on(API + "/rooms/" + enc(ROOM) + "/state/", r -> new Reply(404, "{\"errcode\":\"M_NOT_FOUND\",\"error\":\"none\"}"));
        on(API + "/rooms/" + enc(ROOM) + "/state/m.room.name", "{\"name\":\"DevTools\"}");
        on(API + "/rooms/" + enc(ROOM) + "/joined_members", "{\"joined\":{\"" + BOT + "\":{},\"@felix:example.org\":{}}}");
        on(API + "/sync", this::sync);
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    MatrixStub on(String prefix, String json) {
        routes.put(prefix, r -> new Reply(200, json));
        return this;
    }

    MatrixStub on(String prefix, Function<Request, Reply> handler) {
        routes.put(prefix, handler);
        return this;
    }

    /** Antwort auf {@code /sync?since=<since>} (leer = erster Abruf). */
    MatrixStub sync(String since, String json) {
        syncs.put(since, json);
        return this;
    }

    List<Request> all(String prefix) {
        return requests.stream().filter(r -> r.path().startsWith(prefix)).toList();
    }

    Request last(String prefix) {
        List<Request> found = all(prefix);
        if (found.isEmpty()) {
            throw new AssertionError("keine Anfrage an " + prefix + " – angefragt: "
                    + requests.stream().map(Request::path).toList());
        }
        return found.getLast();
    }

    private Reply sync(Request r) {
        String since = r.query().getOrDefault("since", "");
        String json = syncs.get(since);
        if (json != null) {
            return new Reply(200, json);
        }
        // Long-Polling andeuten, ohne die Tests aufzuhalten
        try {
            Thread.sleep(Math.min(100, Long.parseLong(r.query().getOrDefault("timeout", "0"))));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return new Reply(200, "{\"next_batch\":\"" + (since.isEmpty() ? "s0" : since) + "\",\"rooms\":{}}");
    }

    private void handle(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> headers = new ConcurrentHashMap<>();
        ex.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), String.join(",", v)));
        Map<String, String> query = new LinkedHashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw != null) {
            for (String pair : raw.split("&")) {
                int eq = pair.indexOf('=');
                query.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        Request req = new Request(ex.getRequestMethod(), ex.getRequestURI().getRawPath(), query, body, headers);
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

    @Override
    public void close() {
        server.stop(0);
    }

    // ------------------------------------------------------------------ JSON-Bausteine

    static String enc(String s) {
        return MatrixClient.enc(s);
    }

    static String text(String eventId, String sender, String body) {
        return "{\"type\":\"m.room.message\",\"event_id\":\"" + eventId + "\",\"sender\":\"" + sender
                + "\",\"origin_server_ts\":1790000000000,\"content\":{\"msgtype\":\"m.text\",\"body\":\"" + body + "\"}}";
    }

    static String reply(String eventId, String sender, String body, String to) {
        return "{\"type\":\"m.room.message\",\"event_id\":\"" + eventId + "\",\"sender\":\"" + sender
                + "\",\"origin_server_ts\":1790000000000,\"content\":{\"msgtype\":\"m.text\",\"body\":\"" + body
                + "\",\"m.relates_to\":{\"m.in_reply_to\":{\"event_id\":\"" + to + "\"}}}}";
    }

    /** Sync-Antwort mit Timeline-Ereignissen eines Raums. */
    static String batch(String next, String roomId, int unread, String... events) {
        return "{\"next_batch\":\"" + next + "\",\"rooms\":{\"join\":{\"" + roomId + "\":{\"timeline\":{\"events\":["
                + String.join(",", events) + "],\"limited\":false},\"unread_notifications\":{\"notification_count\":"
                + unread + "}}}}}";
    }

    static String invite(String next, String roomId, String inviter) {
        return "{\"next_batch\":\"" + next + "\",\"rooms\":{\"invite\":{\"" + roomId + "\":{\"invite_state\":{\"events\":["
                + "{\"type\":\"m.room.member\",\"state_key\":\"" + BOT + "\",\"sender\":\"" + inviter
                + "\",\"content\":{\"membership\":\"invite\"}},{\"type\":\"m.room.name\",\"state_key\":\"\",\"sender\":\""
                + inviter + "\",\"content\":{\"name\":\"Neu\"}}]}}}}}";
    }
}
