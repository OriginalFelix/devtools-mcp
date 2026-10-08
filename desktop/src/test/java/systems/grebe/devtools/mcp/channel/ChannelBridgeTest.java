package systems.grebe.devtools.mcp.channel;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import systems.grebe.devtools.mcp.core.ChannelEvents;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ChannelBridgeTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void answersInitializeWithChannelCapability() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ChannelBridge bridge = bridge(out, URI.create("http://127.0.0.1:1"), "");
        bridge.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":"
                + "\"2026-07-28\",\"capabilities\":{},\"clientInfo\":{\"name\":\"claude-code\",\"version\":\"2\"}}}");
        bridge.handle("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        bridge.handle("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
        bridge.handle("{\"jsonrpc\":\"2.0\",\"id\":\"p\",\"method\":\"ping\"}");
        bridge.handle("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"resources/list\"}");

        List<JsonNode> msgs = lines(out);
        assertThat(msgs).hasSize(4);
        JsonNode init = msgs.get(0).path("result");
        // 2026-07-28 stellt laut Doku keine Channel-Nachrichten zu – ältere Version antworten
        assertThat(init.path("protocolVersion").asString()).isEqualTo("2025-11-25");
        assertThat(init.path("capabilities").path("experimental").has("claude/channel")).isTrue();
        assertThat(init.path("capabilities").has("tools")).isFalse();
        assertThat(init.path("instructions").asString()).contains("event_source=\"mail\"", "mail_read");
        assertThat(msgs.get(1).path("result").path("tools").isEmpty()).isTrue();
        assertThat(msgs.get(2).path("id").asString()).isEqualTo("p");
        assertThat(msgs.get(3).path("error").path("code").asInt()).isEqualTo(-32601);
        assertThat(bridge.initialized()).isTrue();
    }

    @Test
    void keepsSupportedProtocolVersion() {
        assertThat(ChannelBridge.initializeResult("2025-06-18").path("protocolVersion").asString()).isEqualTo("2025-06-18");
    }

    @Test
    void turnsServerSentEventsIntoChannelNotifications() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ChannelBridge bridge = bridge(out, URI.create("http://127.0.0.1:1"), "");
        bridge.read(List.of(
                ": ping",
                "",
                "id: 7",
                "event: channel",
                "data: {\"source\":\"mail\",\"content\":\"Neue E-Mail\\nVon: a\",\"meta\":{\"uid\":\"3\",\"event_source\":\"mail\"}}",
                "",
                "event: other",
                "data: {}",
                "").iterator());
        List<JsonNode> msgs = lines(out);
        assertThat(msgs).singleElement().satisfies(n -> {
            assertThat(n.path("method").asString()).isEqualTo("notifications/claude/channel");
            assertThat(n.path("params").path("content").asString()).isEqualTo("Neue E-Mail\nVon: a");
            assertThat(n.path("params").path("meta").path("uid").asString()).isEqualTo("3");
        });
    }

    @Test
    void forwardsEventsFromTheApp() throws Exception {
        ChannelEvents events = new ChannelEvents();
        CountDownLatch connected = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // Stellvertreter für ChannelEventsController: gleiches Format, prüft das Token
        server.createContext("/mcp/channel/events", ex -> {
            if (!"Bearer t0k".equals(ex.getRequestHeaders().getFirst("Authorization"))) {
                ex.sendResponseHeaders(401, -1);
                ex.close();
                return;
            }
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 0);
            OutputStream body = ex.getResponseBody();
            CountDownLatch done = new CountDownLatch(1);
            events.subscribe(e -> {
                try {
                    String data = JSON.writeValueAsString(Map.of("source", e.source(), "content", e.content(),
                            "meta", e.meta()));
                    body.write(("id: " + e.id() + "\nevent: channel\ndata: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
                    body.flush();
                } catch (Exception ex2) {
                    done.countDown();
                }
            }, -1);
            body.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));
            body.flush();
            connected.countDown();
            try {
                done.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                // Ende
            }
        });
        server.start();
        PipedOutputStream stdinWriter = new PipedOutputStream();
        BufferedReader stdin = new BufferedReader(new InputStreamReader(new PipedInputStream(stdinWriter), StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        ChannelBridge bridge = new ChannelBridge(stdin, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(OutputStream.nullOutputStream()), uri, "t0k", ChannelBridge.Mode.CHANNEL);
        Thread t = Thread.ofVirtual().start(bridge::run);
        try {
            // Ereignisse erst nach der Initialisierung durch den Client
            assertThat(connected.await(500, TimeUnit.MILLISECONDS)).isFalse();
            write(stdinWriter, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
            write(stdinWriter, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
            assertThat(connected.await(10, TimeUnit.SECONDS)).isTrue();
            events.publish("mail", "Neue E-Mail in arbeit/INBOX", Map.of("uid", "5", "bad-key", "x"));
            long deadline = System.currentTimeMillis() + 10_000;
            while (lines(out).stream().noneMatch(n -> n.has("method")) && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertThat(lines(out).stream().filter(n -> n.has("method")).toList()).singleElement().satisfies(n -> {
                assertThat(n.path("params").path("content").asString()).isEqualTo("Neue E-Mail in arbeit/INBOX");
                assertThat(n.path("params").path("meta").path("uid").asString()).isEqualTo("5");
                assertThat(n.path("params").path("meta").path("event_source").asString()).isEqualTo("mail");
                assertThat(n.path("params").path("meta").has("bad-key")).isFalse();
            });
        } finally {
            stdinWriter.close();
            t.join(5000);
            server.stop(0);
        }
    }

    /**
     * Proxy gegen einen nachgebauten Streamable-HTTP-Server: initialize bekommt die Channel-Fähigkeit, eine zu neue
     * Protokollversion wird heruntergesetzt, und verfällt die Sitzung (Neustart der App), meldet sich der Proxy neu an,
     * wiederholt die Anfrage und sagt dem Client, dass er die Tool-Liste neu laden soll.
     */
    @Test
    void proxyForwardsAndRenewsSessionAfterRestart() throws Exception {
        List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicInteger sessions = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean restarted = new java.util.concurrent.atomic.AtomicBoolean();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String sid = ex.getRequestHeaders().getFirst("Mcp-Session-Id");
            if (!"GET".equals(ex.getRequestMethod()) && !"DELETE".equals(ex.getRequestMethod())) {
                JsonNode msg = JSON.readTree(body);
                String method = msg.path("method").asString("");
                seen.add(method + "@" + sid + (method.equals("initialize")
                        ? "/" + msg.path("params").path("protocolVersion").asString() : ""));
                if (method.equals("initialize")) {
                    String newSid = "s" + sessions.incrementAndGet();
                    ex.getResponseHeaders().add("Mcp-Session-Id", newSid);
                    json(ex, "{\"jsonrpc\":\"2.0\",\"id\":" + JSON.writeValueAsString(msg.get("id"))
                            + ",\"result\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{\"tools\":"
                            + "{\"listChanged\":true}},\"serverInfo\":{\"name\":\"devtools-mcp\",\"version\":\"1\"},"
                            + "\"instructions\":\"DevTools-Hinweise\"}}");
                } else if (!msg.has("id")) {
                    ex.sendResponseHeaders(202, -1);
                    ex.close();
                } else if ("s1".equals(sid) && restarted.compareAndSet(false, true)) {
                    ex.sendResponseHeaders(404, -1); // App neu gestartet: Sitzung unbekannt
                    ex.close();
                } else {
                    // Antwort als SSE wie beim echten Server, mit einer Rückfrage davor
                    ex.getResponseHeaders().add("Content-Type", "text/event-stream");
                    ex.sendResponseHeaders(200, 0);
                    try (OutputStream o = ex.getResponseBody()) {
                        o.write(("event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\","
                                + "\"params\":{\"progressToken\":1,\"progress\":1}}\n\n").getBytes(StandardCharsets.UTF_8));
                        o.write(("event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":" + JSON.writeValueAsString(msg.get("id"))
                                + ",\"result\":{\"tools\":[{\"name\":\"git_status\"}]}}\n\n")
                                .getBytes(StandardCharsets.UTF_8));
                    }
                }
            } else {
                ex.sendResponseHeaders(405, -1); // kein eigener Meldungsstrom
                ex.close();
            }
        });
        server.start();
        PipedOutputStream stdinWriter = new PipedOutputStream();
        BufferedReader stdin = new BufferedReader(new InputStreamReader(new PipedInputStream(stdinWriter),
                StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ChannelBridge bridge = new ChannelBridge(stdin, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(OutputStream.nullOutputStream()),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "", ChannelBridge.Mode.PROXY);
        Thread t = Thread.ofVirtual().start(bridge::run);
        try {
            write(stdinWriter, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":"
                    + "\"2026-07-28\",\"capabilities\":{\"elicitation\":{}},\"clientInfo\":{\"name\":\"cc\",\"version\":\"2\"}}}");
            JsonNode init = await(out, n -> n.path("id").asInt() == 1);
            assertThat(init.path("result").path("capabilities").path("experimental").has("claude/channel")).isTrue();
            assertThat(init.path("result").path("capabilities").path("tools").path("listChanged").asBoolean()).isTrue();
            assertThat(init.path("result").path("instructions").asString()).startsWith("DevTools-Hinweise")
                    .contains("## Ereignisse (Channel)", "event_source=\"mail\"");
            write(stdinWriter, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
            write(stdinWriter, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
            JsonNode tools = await(out, n -> n.path("id").asInt() == 2);
            assertThat(tools.path("result").path("tools").get(0).path("name").asString()).isEqualTo("git_status");
            List<JsonNode> all = lines(out);
            assertThat(all).anySatisfy(n -> assertThat(n.path("method").asString()).isEqualTo("notifications/progress"));
            assertThat(all).anySatisfy(n -> assertThat(n.path("method").asString())
                    .isEqualTo("notifications/tools/list_changed"));
            // zu neue Version heruntergesetzt; nach dem 404 neu angemeldet, initialized gesendet, Anfrage wiederholt
            assertThat(seen).containsSubsequence("initialize@null/2025-11-25", "notifications/initialized@s1",
                    "tools/list@s1", "initialize@null/2025-11-25", "notifications/initialized@s2", "tools/list@s2");
        } finally {
            stdinWriter.close();
            t.join(5000);
            server.stop(0);
        }
    }

    /**
     * Scheitert die Erneuerung der Sitzung nach einem Neustart der App einmal, bleibt der Proxy nicht dauerhaft kaputt:
     * Die nächste Anfrage löst mit der veralteten Sitzung wieder ein 404 aus und meldet sich neu an.
     */
    @Test
    void proxyRecoversWhenTheSessionRenewalFailedOnce() throws Exception {
        List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.Set<String> valid = java.util.concurrent.ConcurrentHashMap.newKeySet();
        java.util.concurrent.atomic.AtomicInteger sessions = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean failNextInit = new java.util.concurrent.atomic.AtomicBoolean();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String sid = ex.getRequestHeaders().getFirst("Mcp-Session-Id");
            if ("GET".equals(ex.getRequestMethod()) || "DELETE".equals(ex.getRequestMethod())) {
                ex.sendResponseHeaders(405, -1); // kein eigener Meldungsstrom
                ex.close();
                return;
            }
            JsonNode msg = JSON.readTree(body);
            String method = msg.path("method").asString("");
            seen.add(method + "@" + sid);
            if (method.equals("initialize")) {
                if (failNextInit.compareAndSet(true, false)) {
                    ex.sendResponseHeaders(500, -1); // App startet noch
                    ex.close();
                    return;
                }
                String newSid = "s" + sessions.incrementAndGet();
                valid.add(newSid);
                ex.getResponseHeaders().add("Mcp-Session-Id", newSid);
                json(ex, "{\"jsonrpc\":\"2.0\",\"id\":" + JSON.writeValueAsString(msg.get("id"))
                        + ",\"result\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                        + "\"serverInfo\":{\"name\":\"devtools-mcp\",\"version\":\"1\"}}}");
            } else if (sid == null || !valid.contains(sid)) {
                ex.sendResponseHeaders(sid == null ? 400 : 404, -1);
                ex.close();
            } else if (!msg.has("id")) {
                ex.sendResponseHeaders(202, -1);
                ex.close();
            } else {
                json(ex, "{\"jsonrpc\":\"2.0\",\"id\":" + JSON.writeValueAsString(msg.get("id"))
                        + ",\"result\":{\"tools\":[{\"name\":\"git_status\"}]}}");
            }
        });
        server.start();
        PipedOutputStream stdinWriter = new PipedOutputStream();
        BufferedReader stdin = new BufferedReader(new InputStreamReader(new PipedInputStream(stdinWriter),
                StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ChannelBridge bridge = new ChannelBridge(stdin, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(OutputStream.nullOutputStream()),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "", ChannelBridge.Mode.PROXY);
        Thread t = Thread.ofVirtual().start(bridge::run);
        try {
            write(stdinWriter, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":"
                    + "\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"cc\",\"version\":\"2\"}}}");
            await(out, n -> n.path("id").asInt() == 1 && n.has("result"));
            write(stdinWriter, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
            write(stdinWriter, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
            await(out, n -> n.path("id").asInt() == 2 && n.has("result"));

            valid.clear(); // App neu gestartet, und die erste Anmeldung danach scheitert
            failNextInit.set(true);
            write(stdinWriter, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"}");
            JsonNode failed = await(out, n -> n.path("id").asInt() == 3);
            assertThat(failed.path("error").path("message").asString()).contains("HTTP 500");

            // früher blieb der Proxy hier ohne Sitzung kaputt (HTTP 400 für jede weitere Anfrage)
            write(stdinWriter, "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/list\"}");
            JsonNode ok = await(out, n -> n.path("id").asInt() == 4);
            assertThat(ok.path("result").path("tools").get(0).path("name").asString()).isEqualTo("git_status");
            assertThat(seen).containsSubsequence("tools/list@s1", "initialize@null", "tools/list@s1", "initialize@null",
                    "notifications/initialized@s2", "tools/list@s2");
        } finally {
            stdinWriter.close();
            t.join(5000);
            server.stop(0);
        }
    }

    @Test
    void initializeAnswersWithAnErrorForWrongTokenAndForForeignServices() throws Exception {
        for (int status : new int[]{401, 200}) {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/mcp", ex -> {
                ex.getRequestBody().readAllBytes();
                if (status == 401) {
                    byte[] b = "{\"error\":\"Unauthorized\"}".getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().add("Content-Type", "application/json");
                    ex.sendResponseHeaders(401, b.length);
                    try (OutputStream o = ex.getResponseBody()) {
                        o.write(b);
                    }
                } else {
                    byte[] b = "<html>Fehlerseite eines Proxys</html>".getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().add("Content-Type", "application/json");
                    ex.sendResponseHeaders(200, b.length);
                    try (OutputStream o = ex.getResponseBody()) {
                        o.write(b);
                    }
                }
            });
            server.start();
            PipedOutputStream stdinWriter = new PipedOutputStream();
            BufferedReader stdin = new BufferedReader(new InputStreamReader(new PipedInputStream(stdinWriter),
                    StandardCharsets.UTF_8));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ChannelBridge bridge = new ChannelBridge(stdin, new PrintStream(out, true, StandardCharsets.UTF_8),
                    new PrintStream(OutputStream.nullOutputStream()),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "falsch", ChannelBridge.Mode.PROXY);
            Thread t = Thread.ofVirtual().start(bridge::run);
            try {
                write(stdinWriter, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
                JsonNode reply = await(out, n -> n.path("id").asInt() == 1);
                String message = reply.path("error").path("message").asString();
                if (status == 401) {
                    assertThat(message).contains("HTTP 401", "Zugriffstoken falsch");
                } else {
                    assertThat(message).contains("unerwartete Antwort auf initialize");
                }
                assertThat(t.isAlive()).isTrue(); // der Prozess läuft weiter, statt an der Ausnahme zu enden
            } finally {
                stdinWriter.close();
                t.join(5000);
                server.stop(0);
            }
        }
    }

    private static void json(com.sun.net.httpserver.HttpExchange ex, String body) throws java.io.IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream o = ex.getResponseBody()) {
            o.write(b);
        }
    }

    private static void write(PipedOutputStream stdin, String line) throws java.io.IOException {
        stdin.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        stdin.flush();
    }

    private static JsonNode await(ByteArrayOutputStream out, java.util.function.Predicate<JsonNode> match)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            for (JsonNode n : lines(out)) {
                if (match.test(n)) {
                    return n;
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Keine passende Nachricht: " + lines(out));
    }

    private static ChannelBridge bridge(ByteArrayOutputStream out, URI uri, String token) {
        return new ChannelBridge(new BufferedReader(new java.io.StringReader("")),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(OutputStream.nullOutputStream()),
                uri, token, ChannelBridge.Mode.CHANNEL);
    }

    private static List<JsonNode> lines(ByteArrayOutputStream out) {
        String text;
        synchronized (out) {
            text = out.toString(StandardCharsets.UTF_8);
        }
        return text.lines().filter(l -> !l.isBlank()).map(JSON::readTree).toList();
    }
}
