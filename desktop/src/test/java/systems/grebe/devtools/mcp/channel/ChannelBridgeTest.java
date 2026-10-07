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
        ChannelBridge bridge = bridge(out, URI.create("http://127.0.0.1:1/x"), "");
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
        ChannelBridge bridge = bridge(out, URI.create("http://127.0.0.1:1/x"), "");
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
        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp/channel/events");
        ChannelBridge bridge = new ChannelBridge(stdin, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(OutputStream.nullOutputStream()), uri, "t0k");
        Thread t = Thread.ofVirtual().start(bridge::run);
        try {
            assertThat(connected.await(10, TimeUnit.SECONDS)).isTrue();
            events.publish("mail", "Neue E-Mail in arbeit/INBOX", Map.of("uid", "5", "bad-key", "x"));
            long deadline = System.currentTimeMillis() + 10_000;
            while (lines(out).isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertThat(lines(out)).singleElement().satisfies(n -> {
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

    private static ChannelBridge bridge(ByteArrayOutputStream out, URI uri, String token) {
        return new ChannelBridge(new BufferedReader(new java.io.StringReader("")),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(OutputStream.nullOutputStream()),
                uri, token);
    }

    private static List<JsonNode> lines(ByteArrayOutputStream out) {
        String text;
        synchronized (out) {
            text = out.toString(StandardCharsets.UTF_8);
        }
        return text.lines().filter(l -> !l.isBlank()).map(JSON::readTree).toList();
    }
}
