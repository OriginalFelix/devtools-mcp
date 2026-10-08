package systems.grebe.devtools.mcp.channel;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import systems.grebe.devtools.mcp.DevToolsMcpApplication;
import systems.grebe.devtools.mcp.config.SettingsStore;
import systems.grebe.devtools.mcp.core.ChannelEvents;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Der stdio-Proxy ({@code ChannelBridge stdio}) gegen die echte App, über stdin/stdout wie Claude Code: Channel-Ereignisse
 * im Proxy-Betrieb, eine neue Sitzung nach deren Ablauf und ein klarer Fehler, wenn die App nicht läuft. Tools,
 * Rückfragen und {@code tools/list_changed} prüft {@code McpServerIntegrationTest} mit einem echten MCP-Client.
 */
// Kontext nach der Klasse schließen: die Graph-Datenbank des Backends hält sonst Dateien im temporären Ordner offen
@DirtiesContext
@SpringBootTest(classes = {DevToolsMcpApplication.class, ChannelBridgeAppIntegrationTest.Config.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"devtools.local-user.email=stdio@example.com", "devtools.login.username=tester",
                "devtools.login.password=tester-passwort"})
class ChannelBridgeAppIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    static Path home;

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        SettingsStore settingsStore() {
            return new SettingsStore(home);
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    ChannelEvents channelEvents;

    private PipedOutputStream stdin;
    private final BlockingQueue<JsonNode> stdout = new LinkedBlockingQueue<>();
    /** Alles, was {@link #await} vom Proxy gelesen hat, in Reihenfolge. */
    private final List<JsonNode> seen = new ArrayList<>();
    private ChannelBridge proxy;
    private Thread thread;

    @BeforeEach
    void start() throws Exception {
        stdin = new PipedOutputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(new PipedInputStream(stdin, 1 << 16),
                StandardCharsets.UTF_8));
        PrintStream out = new PrintStream(new LineSink(stdout), true, StandardCharsets.UTF_8);
        proxy = new ChannelBridge(reader, out, new PrintStream(OutputStream.nullOutputStream()),
                URI.create("http://127.0.0.1:" + port), "", ChannelBridge.Mode.PROXY);
        thread = Thread.ofVirtual().start(proxy::run);
    }

    @AfterEach
    void stop() throws Exception {
        stdin.close();
        thread.join(5000);
    }

    @Test
    void channelEventsAndSessionRenewalAgainstTheApp() throws Exception {
        // initialize: Fähigkeiten der App plus Channel
        write("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\","
                + "\"capabilities\":{\"elicitation\":{}},\"clientInfo\":{\"name\":\"stdio-test\",\"version\":\"1\"}}}");
        JsonNode init = await(m -> m.path("id").asInt() == 1).path("result");
        assertThat(init.path("protocolVersion").asString()).isEqualTo("2025-06-18");
        assertThat(init.path("capabilities").has("tools")).isTrue();
        assertThat(init.path("capabilities").path("experimental").has("claude/channel")).isTrue();
        assertThat(init.path("instructions").asString()).contains("DevTools MCP", "event_source=\"mail\"");
        write("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");

        write("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
        assertThat(names(await(m -> m.path("id").asInt() == 2).path("result").path("tools")))
                .contains("permissions_overview");

        // Channel-Ereignis
        long deadline = System.currentTimeMillis() + 10_000;
        while (channelEvents.subscribers() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        channelEvents.publish("mail", "Neue E-Mail in arbeit/INBOX", Map.of("uid", "7"));
        JsonNode event = await(m -> m.path("method").asString("").equals(ChannelBridge.METHOD));
        assertThat(event.path("params").path("content").asString()).isEqualTo("Neue E-Mail in arbeit/INBOX");
        assertThat(event.path("params").path("meta").path("uid").asString()).isEqualTo("7");
        assertThat(event.path("params").path("meta").path("event_source").asString()).isEqualTo("mail");

        // Sitzung verfällt (wie nach einem Neustart der App): der Proxy legt still eine neue an
        String old = proxy.sessionId();
        HttpResponse<Void> deleted = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/mcp")).DELETE().header("Mcp-Session-Id", old).build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(deleted.statusCode()).isLessThan(300);
        int mark = seen.size();
        write("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/list\"}");
        JsonNode again = await(m -> m.path("id").asInt() == 5);
        assertThat(names(again.path("result").path("tools"))).contains("permissions_overview");
        assertThat(proxy.sessionId()).isNotNull().isNotEqualTo(old);
        // den Client auffordern, die Tools neu zu laden (neue Sitzung, ggf. andere Tools)
        Predicate<JsonNode> changed = m -> m.path("method").asString("").equals("notifications/tools/list_changed");
        if (seen.subList(mark, seen.size()).stream().noneMatch(changed)) {
            await(changed);
        }
    }

    @Test
    void unreachableAppIsAnErrorNotAHang() throws Exception {
        PipedOutputStream in = new PipedOutputStream();
        BlockingQueue<JsonNode> out = new LinkedBlockingQueue<>();
        ChannelBridge dead = new ChannelBridge(new BufferedReader(new InputStreamReader(new PipedInputStream(in),
                StandardCharsets.UTF_8)), new PrintStream(new LineSink(out), true, StandardCharsets.UTF_8),
                new PrintStream(OutputStream.nullOutputStream()), URI.create("http://127.0.0.1:1"), "",
                ChannelBridge.Mode.PROXY);
        Thread t = Thread.ofVirtual().start(dead::run);
        // nach initialize (wartet auf eine startende App) liefert eine Anfrage ohne Sitzung sofort einen Fehler
        in.write("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/list\"}\n".getBytes(StandardCharsets.UTF_8));
        in.flush();
        JsonNode r = out.poll(15, TimeUnit.SECONDS);
        assertThat(r).isNotNull();
        assertThat(r.path("id").asInt()).isEqualTo(9);
        assertThat(r.path("error").path("message").asString()).contains("DevTools-App nicht erreichbar");
        in.close();
        t.join(5000);
    }

    // ------------------------------------------------------------------ Hilfen

    private void write(String line) throws Exception {
        stdin.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        stdin.flush();
    }

    private static List<String> names(JsonNode tools) {
        List<String> out = new ArrayList<>();
        tools.forEach(t -> out.add(t.path("name").asString()));
        return out;
    }

    /** Nächste passende Nachricht des Proxys (andere werden übersprungen). */
    private JsonNode await(Predicate<JsonNode> match) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode m = stdout.poll(200, TimeUnit.MILLISECONDS);
            if (m != null) {
                seen.add(m);
                if (match.test(m)) {
                    return m;
                }
            }
        }
        throw new AssertionError("Keine passende Nachricht vom Proxy");
    }

    /** stdout des Proxys: jede Zeile eine JSON-RPC-Nachricht. */
    private static final class LineSink extends OutputStream {
        private final BlockingQueue<JsonNode> queue;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();

        LineSink(BlockingQueue<JsonNode> queue) {
            this.queue = queue;
        }

        @Override
        public synchronized void write(int b) {
            if (b == '\n') {
                String text = line.toString(StandardCharsets.UTF_8);
                line.reset();
                if (!text.isBlank()) {
                    queue.add(JSON.readTree(text));
                }
            } else {
                line.write(b);
            }
        }
    }
}
