package systems.grebe.devtools.mcp.modules.a2a;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import systems.grebe.devtools.mcp.core.ChannelEvents;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.modules.ticket.spi.HttpJson;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Client-Tools gegen den eigenen A2A-Server hinter einem HTTP-Stub – in beiden Protokollfassungen. */
class A2aRoundTripTest {

    HttpServer http;
    final ChannelEvents channel = new ChannelEvents();
    final A2aServer server = new A2aServer(channel);
    final List<String> versions = new CopyOnWriteArrayList<>();
    final List<String> methods = new CopyOnWriteArrayList<>();
    final ModuleConfig config = ModuleConfig.of(new A2aModule(server).configSchema(),
            Map.of(A2aModule.SERVER, "true", A2aModule.SERVER_TOKEN, "geheim"));
    boolean legacyCard;
    String base;

    @BeforeEach
    void start() throws IOException {
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/", this::handle);
        http.start();
        base = "http://127.0.0.1:" + http.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        http.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        String body;
        if (ex.getRequestURI().getPath().equals(A2aClient.CARD_PATH)) {
            body = legacyCard
                    ? "{\"name\":\"Alt\",\"description\":\"v0.3\",\"url\":\"" + base + "/a2a\",\"skills\":[]}"
                    : HttpJson.JSON.writeValueAsString(A2aController.card(config, base + "/a2a", true));
        } else {
            String version = ex.getRequestHeaders().getFirst("A2A-Version");
            versions.add(version);
            JsonNode req = HttpJson.JSON.readTree(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            methods.add(req.path("method").asString());
            body = HttpJson.JSON.writeValueAsString(server.handle(req, version, "test", Duration.ofSeconds(5)));
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private A2aTools tools() {
        return new A2aTools(List.of(Map.of(A2aModule.NAME, "self", A2aModule.URL, base)), Duration.ofSeconds(10));
    }

    /** Antwortet als „Claude“, sobald eine Anfrage im Eingang liegt. */
    private CompletableFuture<Void> answer(String text, String state) {
        return CompletableFuture.runAsync(() -> {
            long end = System.currentTimeMillis() + 5000;
            while (submitted() == null && System.currentTimeMillis() < end) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            }
            String task = submitted().split(" ")[1];
            server.reply(task, text, state);
        });
    }

    /** Wartende Anfrage (frisch eingegangen bzw. nach einer Rückfrage fortgesetzt). */
    private String submitted() {
        return server.inbox().stream().filter(s -> s.contains("(submitted,")).findFirst().orElse(null);
    }

    @Test
    void v1SendWaitsForTheReply() {
        CompletableFuture<Void> reply = answer("42", null);
        String result = tools().send("self", "Was ist die Antwort?", null, null, null);
        reply.join();
        assertThat(result).contains(": completed", "Agent: 42", "Artefakt answer:\n42");
        assertThat(methods).containsExactly("SendMessage");
        assertThat(versions).containsExactly("1.0");
        assertThat(tools().agents(null)).contains("DevTools Claude", "A2A 1.0", "Entwicklungsassistent");
    }

    @Test
    void v03FollowUpAndCancel() {
        legacyCard = true;
        String first = tools().send("self", "Baue das Projekt", null, null, false);
        assertThat(first).contains(": submitted", "läuft noch");
        String task = first.split(" ")[1];

        server.reply(task, "Welches Projekt?", "input-required");
        assertThat(tools().task("self", task)).contains(": input-required", "Agent: Welches Projekt?");

        CompletableFuture<Void> reply = answer("Fertig", "completed");
        assertThat(tools().send("self", "devtools", task, null, true)).contains(": completed", "Fertig");
        reply.join();
        assertThat(versions).containsOnly("0.3");
        assertThat(methods).containsExactly("message/send", "tasks/get", "message/send");

        assertThatThrownBy(() -> tools().cancel("self", task)).hasMessageContaining("-32002");
        assertThatThrownBy(() -> tools().send("self", "noch was", task, null, false))
                .hasMessageContaining("-32004");
    }

    @Test
    void cancelEndsAnOpenTask() {
        String task = tools().send("self", "lang", null, null, false).split(" ")[1];
        assertThat(tools().cancel("self", task)).contains(": canceled");
        assertThat(server.inbox()).isEmpty();
        assertThatThrownBy(() -> server.reply(task, "zu spät", null)).hasMessageContaining("canceled");
    }

    @Test
    void rpcErrors() {
        JsonNode unknown = server.handle(HttpJson.JSON.readTree(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"Foo\",\"params\":{}}"), "1.0", "t", Duration.ZERO);
        assertThat(unknown.path("error").path("code").asInt()).isEqualTo(-32601);
        JsonNode missing = server.handle(HttpJson.JSON.readTree(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"GetTask\",\"params\":{\"id\":\"x\"}}"), null, "t", Duration.ZERO);
        assertThat(missing.path("error").path("code").asInt()).isEqualTo(-32001);
        JsonNode version = server.handle(HttpJson.JSON.readTree(
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"GetTask\",\"params\":{}}"), "2.0", "t", Duration.ZERO);
        assertThat(version.path("error").path("code").asInt()).isEqualTo(-32009);
    }

    @Test
    void cardAnnouncesBearerOnlyWithToken() {
        assertThat(A2aController.card(config, "http://x/a2a", true).path("securitySchemes").path("bearer")
                .path("httpAuthSecurityScheme").path("scheme").asString()).isEqualTo("Bearer");
        JsonNode legacy = A2aController.card(config, "http://x/a2a", false);
        assertThat(legacy.path("url").asString()).isEqualTo("http://x/a2a");
        assertThat(legacy.path("securitySchemes").path("bearer").path("type").asString()).isEqualTo("http");
        ModuleConfig open = ModuleConfig.of(new A2aModule(server).configSchema(), Map.of(A2aModule.SERVER, "true"));
        assertThat(A2aController.card(open, "http://x/a2a", true).has("securitySchemes")).isFalse();
    }
}
