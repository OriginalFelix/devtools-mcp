package systems.grebe.devtools.mcp.modules.classify;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.ConnectionTestResult;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** classify_task und die Verbindungsprüfung gegen einen Stub der Claude API. */
class ClassifyModuleTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    ClassifyModule module = new ClassifyModule();
    HttpServer server;
    /** Antwort je Pfad: {status, body}; zuletzt empfangener Body je Pfad. */
    final Map<String, Object[]> replies = new ConcurrentHashMap<>();
    final Map<String, String> bodies = new ConcurrentHashMap<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            bodies.put(path, new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            Object[] r = replies.getOrDefault(path, new Object[]{404, "{\"type\":\"error\",\"error\":{\"type\":\"not_found_error\",\"message\":\"nf\"}}"});
            byte[] b = ((String) r[1]).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders((Integer) r[0], b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private ModuleConfig config(Map<String, String> extra) {
        Map<String, String> v = new HashMap<>(Map.of("apiKey", "sk-ant-test", "baseUrl", url()));
        v.putAll(extra);
        return ModuleConfig.of(module.configSchema(), v);
    }

    private ClassifyTools tools(Map<String, String> extra) {
        return new ClassifyTools(new TaskClassifier(ClassifyModule.settings(config(extra))));
    }

    private static String message(String json) {
        return JSON.writeValueAsString(Map.of("id", "msg_1", "type", "message", "role", "assistant",
                "model", TaskClassifier.MODEL, "content", List.of(Map.of("type", "text", "text", json)),
                "stop_reason", "end_turn", "usage", Map.of("input_tokens", 500, "output_tokens", 120)));
    }

    @Test
    void schemaHasSecretKeyDefaultsAndOffersOneTool() {
        assertThat(module.configSchema()).extracting(ConfigField::key)
                .containsExactly("apiKey", "baseUrl", "effort", "modelSimple", "modelNormal", "modelComplex", "rules");
        assertThat(module.configSchema().getFirst().secret()).isTrue();
        TaskClassifier.Settings defaults = ClassifyModule.settings(ModuleConfig.of(module.configSchema(), Map.of()));
        assertThat(defaults.effort()).isEqualTo("high");
        assertThat(defaults.mapping()).isEqualTo("einfach → claude-haiku-4-5, normal → claude-sonnet-4-5, komplex → claude-opus-5-5");
        assertThat(module.createTools(ModuleConfig.of(module.configSchema(), Map.of())))
                .extracting(t -> t.getToolDefinition().name()).containsExactly("task");
        assertThat(module.instructions()).contains("`classify_task`", "`ticket_classify`");
    }

    @Test
    void classifiesGeneralTaskWithOpusAndMapsStageToModel() {
        replies.put("/v1/messages", new Object[]{200, message("""
                {"complexity":"simple","confidence":"high","summary":"Kleine, klar umrissene Textänderung.",
                 "factors":["eine Datei"],"risks":[],"openQuestions":[]}""")});

        String out = tools(Map.of("modelSimple", "claude-haiku-4-5", "rules", "Reine Übersetzungen sind einfach"))
                .task("Die Fehlermeldung beim Login ins Englische übersetzen.", "Login-Meldung übersetzen", "Text",
                        "1 Datei", "Spring-Boot-App, messages_en.properties");

        assertThat(out).startsWith("Aufgabe: Login-Meldung übersetzen")
                .contains("Komplexität: einfach (Sicherheit: hoch)", "Empfohlenes Modell: claude-haiku-4-5",
                        "Faktoren:\n- eine Datei", "Eingeschätzt mit claude-opus-5-5 (effort high, 500 Token ein / 120 aus)")
                .doesNotContain("Risiken:");
        JsonNode body = JSON.readTree(bodies.get("/v1/messages"));
        assertThat(body.path("model").asString()).isEqualTo("claude-opus-5-5");
        assertThat(body.path("output_config").path("format").path("schema").path("additionalProperties").asBoolean(true))
                .isFalse();
        assertThat(body.path("system").asString()).contains("Pre-Classifier für Aufgaben", "- Reine Übersetzungen sind einfach");
        assertThat(body.path("messages").path(0).path("content").asString())
                .contains("Titel: Login-Meldung übersetzen", "Art: Text", "Umfang: 1 Datei",
                        "<beschreibung>\nDie Fehlermeldung", "<kontext>\nSpring-Boot-App");

        assertThatThrownBy(() -> tools(Map.of()).task(" ", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'task'");
    }

    @Test
    void connectionTestReadsModelInfoWithoutSpendingTokens() {
        replies.put("/v1/models/claude-opus-5-5", new Object[]{200, """
                {"type":"model","id":"claude-opus-5-5","display_name":"Claude Opus 5.5","created_at":"2026-09-01T00:00:00Z"}"""});
        ConnectionTestResult ok = module.testConnection(config(Map.of()));
        assertThat(ok.success()).isTrue();
        assertThat(ok.message()).contains("Claude Opus 5.5 (claude-opus-5-5) verfügbar");
        assertThat(bodies).doesNotContainKey("/v1/messages");

        replies.put("/v1/models/claude-opus-5-5", new Object[]{401,
                "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}"});
        assertThat(module.testConnection(config(Map.of())).message()).contains("Zugriff verweigert (401)");
    }
}
