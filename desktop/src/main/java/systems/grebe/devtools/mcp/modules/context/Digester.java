package systems.grebe.devtools.mcp.modules.context;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.NoCredentialsException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Fasst ein langes Ergebnis mit einem kleinen, günstigen Modell zusammen, damit nur die Zusammenfassung in den
 * Kontext des aufrufenden LLM kommt. Bevorzugt über das LLM des Clients (MCP-Sampling, kein eigener Key), sonst direkt
 * über die Claude API.
 */
final class Digester {

    static final String DEFAULT_MODEL = "claude-haiku-5-5";
    /** Mehr bekommt das kleine Modell nicht (≈ 100k Tokens); der Rest wird mit Hinweis abgeschnitten. */
    static final int MAX_INPUT_CHARS = 400_000;
    static final int MAX_TOKENS = 1_500;
    private static final Duration TIMEOUT = Duration.ofSeconds(90);

    static final String SYSTEM = """
            Du verdichtest die Ausgabe eines Entwickler-Werkzeugs für einen anderen KI-Agenten, der damit \
            weiterarbeitet. Er sieht nur deine Zusammenfassung, nicht das Original.
            Regeln:
            - Höchstens 250 Wörter, knappe Stichpunkte, kein Vorwort.
            - Fehlermeldungen, Exception-Typen, Dateipfade, Zeilennummern, IDs, Versionen, Befehle und Zahlen \
            wörtlich übernehmen.
            - Zuerst, was schiefging oder auffällt; dann Überblick (Anzahlen, Muster, Wiederholungen).
            - Nichts erfinden; Unklares als unklar kennzeichnen.
            - Gibt der Agent einen Fokus vor, nur dazu berichten.""";

    /** Woher Modell und Key kommen. */
    record Settings(String model, String apiKey, String baseUrl) {
    }

    private final Settings settings;

    Digester(Settings settings) {
        this.settings = settings;
    }

    /** Zusammenfassung von {@code text}; Weg: Sampling, wenn der Client es kann, sonst Claude API. */
    String digest(String tool, String text, String focus, McpSyncServerExchange exchange) {
        String input = text.length() <= MAX_INPUT_CHARS ? text
                : text.substring(0, MAX_INPUT_CHARS) + "\n… [abgeschnitten, " + text.length() + " Zeichen gesamt]";
        String user = "Werkzeug: " + tool + "\n"
                + (focus == null || focus.isBlank() ? "" : "Fokus: " + focus.strip() + "\n")
                + "<ausgabe>\n" + input + "\n</ausgabe>";
        if (canSample(exchange)) {
            return viaClient(user, exchange);
        }
        return viaApi(user);
    }

    static boolean canSample(McpSyncServerExchange exchange) {
        return exchange != null && exchange.getClientCapabilities() != null
                && exchange.getClientCapabilities().sampling() != null;
    }

    private String viaClient(String user, McpSyncServerExchange exchange) {
        McpSchema.CreateMessageRequest request = McpSchema.CreateMessageRequest.builder()
                .systemPrompt(SYSTEM)
                .messages(List.of(new McpSchema.SamplingMessage(McpSchema.Role.USER, new McpSchema.TextContent(user))))
                .modelPreferences(McpSchema.ModelPreferences.builder()
                        .addHint(settings.model()).addHint("haiku")
                        .costPriority(1.0).speedPriority(0.8).intelligencePriority(0.2)
                        .build())
                .includeContext(McpSchema.CreateMessageRequest.ContextInclusionStrategy.NONE)
                .maxTokens(MAX_TOKENS)
                .build();
        McpSchema.CreateMessageResult r;
        try {
            r = exchange.createMessage(request);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Zusammenfassen über den MCP-Client fehlgeschlagen: " + e.getMessage(), e);
        }
        if (!(r.content() instanceof McpSchema.TextContent t)) {
            throw new IllegalStateException("Der MCP-Client hat keine Textantwort geliefert.");
        }
        return t.text().strip() + "\n(zusammengefasst vom Client" + (r.model() == null ? "" : ", " + r.model()) + ")";
    }

    private String viaApi(String user) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(settings.model())
                .maxTokens(MAX_TOKENS)
                .system(SYSTEM)
                .addUserMessage(user)
                .build();
        AnthropicClient client = client();
        try {
            Message m = client.messages().create(params);
            String text = m.content().stream().flatMap(b -> b.text().stream()).map(TextBlock::text)
                    .collect(Collectors.joining()).strip();
            return text + "\n(zusammengefasst mit " + settings.model() + ", " + m.usage().inputTokens() + " → "
                    + m.usage().outputTokens() + " Tokens)";
        } catch (AnthropicServiceException e) {
            throw new IllegalStateException("Claude API: Fehler " + e.statusCode() + " – " + e.getMessage(), e);
        } catch (AnthropicIoException e) {
            throw new IllegalStateException("Claude API nicht erreichbar: " + e.getMessage(), e);
        } finally {
            client.close();
        }
    }

    private AnthropicClient client() {
        AnthropicOkHttpClient.Builder b = AnthropicOkHttpClient.builder().timeout(TIMEOUT);
        boolean key = !blank(settings.apiKey());
        try {
            b.fromEnv();
        } catch (NoCredentialsException e) {
            if (!key) {
                throw missingKey();
            }
        }
        if (key) {
            b.apiKey(settings.apiKey().strip());
        }
        if (!blank(settings.baseUrl())) {
            b.baseUrl(settings.baseUrl().strip());
        }
        try {
            return b.build();
        } catch (RuntimeException e) {
            throw missingKey();
        }
    }

    static IllegalStateException missingKey() {
        return new IllegalStateException("Zusammenfassen geht nicht: der Client bietet kein Sampling an und es gibt "
                + "keinen Claude-API-Key (Modul „Kontext sparen“ oder „Modellwahl“ in der DevTools-App, oder "
                + "ANTHROPIC_API_KEY). Stattdessen context_slice mit grep verwenden.");
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
