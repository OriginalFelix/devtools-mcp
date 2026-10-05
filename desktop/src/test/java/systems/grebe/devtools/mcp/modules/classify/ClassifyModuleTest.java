package systems.grebe.devtools.mcp.modules.classify;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.McpToolUtils;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
        Map<String, String> v = new HashMap<>(Map.of("mode", "api", "apiKey", "sk-ant-test", "baseUrl", url()));
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
                .containsExactly("mode", "apiKey", "baseUrl", "effort", "modelSimple", "modelNormal", "modelComplex", "rules");
        assertThat(module.configSchema().get(1).secret()).isTrue();
        TaskClassifier.Settings defaults = ClassifyModule.settings(ModuleConfig.of(module.configSchema(), Map.of()));
        assertThat(defaults.mode()).isEqualTo(TaskClassifier.Mode.CLIENT);
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
                        "1 Datei", "Spring-Boot-App, messages_en.properties", null);

        assertThat(out).startsWith("Aufgabe: Login-Meldung übersetzen")
                .contains("Komplexität: einfach (Sicherheit: hoch)", "Empfohlenes Modell: claude-haiku-4-5",
                        "Faktoren:\n- eine Datei",
                        "Eingeschätzt von claude-opus-5-5 über Claude API (effort high, 500 Token ein / 120 aus)")
                .doesNotContain("Risiken:", "Hinweis:");
        JsonNode body = JSON.readTree(bodies.get("/v1/messages"));
        assertThat(body.path("model").asString()).isEqualTo("claude-opus-5-5");
        assertThat(body.path("output_config").path("format").path("schema").path("additionalProperties").asBoolean(true))
                .isFalse();
        assertThat(body.path("system").asString()).contains("Pre-Classifier für Aufgaben", "- Reine Übersetzungen sind einfach");
        assertThat(body.path("messages").path(0).path("content").asString())
                .contains("Titel: Login-Meldung übersetzen", "Art: Text", "Umfang: 1 Datei",
                        "<beschreibung>\nDie Fehlermeldung", "<kontext>\nSpring-Boot-App");

        assertThatThrownBy(() -> tools(Map.of()).task(" ", null, null, null, null, null))
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

        // Ausführung über den Client braucht keinen Key und ruft die API nicht auf
        bodies.clear();
        ConnectionTestResult client = module.testConnection(ModuleConfig.of(module.configSchema(), Map.of()));
        assertThat(client.success()).isTrue();
        assertThat(client.message()).contains("kein API-Key nötig");
        assertThat(bodies).isEmpty();
    }

    // ------------------------------------------------------------------ Ausführung über den Client

    private static McpSyncServerExchange exchange(boolean sampling, McpSchema.CreateMessageResult reply) {
        McpSyncServerExchange ex = mock(McpSyncServerExchange.class);
        when(ex.getClientCapabilities()).thenReturn(sampling
                ? McpSchema.ClientCapabilities.builder().sampling().build() : McpSchema.ClientCapabilities.builder().build());
        when(ex.getClientInfo()).thenReturn(new McpSchema.Implementation("vscode", "1.110"));
        if (reply != null) {
            when(ex.createMessage(any())).thenReturn(reply);
        }
        return ex;
    }

    private static ToolContext context(McpSyncServerExchange ex) {
        return new ToolContext(Map.of(McpToolUtils.TOOL_CONTEXT_MCP_EXCHANGE_KEY, ex));
    }

    private ClassifyTools clientTools(Map<String, String> values) {
        return new ClassifyTools(new TaskClassifier(ClassifyModule.settings(ModuleConfig.of(module.configSchema(), values))));
    }

    @Test
    void samplesViaClientLlmWithOpusAsModelHintAndWithoutApiKey() {
        McpSyncServerExchange ex = exchange(true, McpSchema.CreateMessageResult.builder().role(McpSchema.Role.ASSISTANT)
                .model("claude-opus-5-5").content(new McpSchema.TextContent("""
                        ```json
                        {"complexity":"complex","confidence":"medium","summary":"Mehrere Module.",
                         "factors":["Schnittstelle"],"risks":[],"openQuestions":["Migration?"]}
                        ```""")).build());

        String out = clientTools(Map.of()).task("Zahlungsanbieter wechseln", "Payment", "Feature", null, "3 Module",
                context(ex));

        assertThat(out).contains("Komplexität: komplex (Sicherheit: mittel)", "Empfohlenes Modell: claude-opus-5-5",
                        "Offene Fragen:\n- Migration?", "Eingeschätzt von claude-opus-5-5 über Client vscode.")
                .doesNotContain("Token ein", "Hinweis:");
        ArgumentCaptor<McpSchema.CreateMessageRequest> req = ArgumentCaptor.forClass(McpSchema.CreateMessageRequest.class);
        verify(ex).createMessage(req.capture());
        McpSchema.CreateMessageRequest r = req.getValue();
        assertThat(r.modelPreferences().hints()).extracting(McpSchema.ModelHint::name).first().isEqualTo("claude-opus-5-5");
        assertThat(r.modelPreferences().intelligencePriority()).isEqualTo(1.0);
        assertThat(r.includeContext()).isEqualTo(McpSchema.CreateMessageRequest.ContextInclusionStrategy.NONE);
        assertThat(r.systemPrompt()).contains("Pre-Classifier für Aufgaben", "Antwortformat:", "\"openQuestions\"");
        assertThat(((McpSchema.TextContent) r.messages().getFirst().content()).text())
                .contains("Titel: Payment", "Zahlungsanbieter wechseln", "<kontext>\n3 Module");
        assertThat(bodies).isEmpty();
    }

    @Test
    void warnsWhenClientChoseAnotherModel() {
        McpSyncServerExchange ex = exchange(true, McpSchema.CreateMessageResult.builder().role(McpSchema.Role.ASSISTANT)
                .model("gpt-5").content(new McpSchema.TextContent("""
                        {"complexity":"simple","confidence":"high","summary":"Klein.","factors":[],"risks":[],
                         "openQuestions":[]}""")).build());
        assertThat(clientTools(Map.of()).task("Tippfehler korrigieren", null, null, null, null, context(ex)))
                .contains("Empfohlenes Modell: claude-haiku-4-5", "Eingeschätzt von gpt-5 über Client vscode",
                        "Hinweis: Gewünscht war claude-opus-5-5", "Modus „api“");
    }

    @Test
    void returnsClassifierPromptWhenClientCannotSample() {
        McpSyncServerExchange ex = exchange(false, null);
        String out = clientTools(Map.of("rules", "Lohn ist immer komplex"))
                .task("Lohnabrechnung um neue Zulage erweitern", "Zulage", null, null, null, context(ex));

        assertThat(out).startsWith("Aufgabe: Zulage")
                .contains("bietet kein Sampling an", "Agent mit model \"opus\"",
                        "einfach → claude-haiku-4-5, normal → claude-sonnet-4-5, komplex → claude-opus-5-5",
                        "<system-prompt>", "- Lohn ist immer komplex", "Antwortformat:", "<anfrage>",
                        "Lohnabrechnung um neue Zulage erweitern");
        verify(ex, never()).createMessage(any());
        assertThat(bodies).isEmpty();
        // ohne MCP-Aufruf (kein Exchange) ebenso
        assertThat(clientTools(Map.of()).task("x", null, null, null, null, null)).contains("<system-prompt>");

        // auto: ohne Sampling direkt über die API, api: auch wenn der Client Sampling könnte
        replies.put("/v1/messages", new Object[]{200, message("""
                {"complexity":"normal","confidence":"high","summary":"s","factors":[],"risks":[],"openQuestions":[]}""")});
        assertThat(clientTools(Map.of("mode", "auto", "apiKey", "k", "baseUrl", url()))
                .task("x", null, null, null, null, context(ex))).contains("über Claude API");
        McpSyncServerExchange sampling = exchange(true, null);
        assertThat(clientTools(Map.of("mode", "api", "apiKey", "k", "baseUrl", url()))
                .task("x", null, null, null, null, context(sampling))).contains("über Claude API");
        verify(sampling, never()).createMessage(any());
    }
}
