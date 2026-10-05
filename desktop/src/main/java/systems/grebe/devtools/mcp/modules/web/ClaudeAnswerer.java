package systems.grebe.devtools.mcp.modules.web;

import java.time.Duration;
import java.util.List;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.CredentialResolutionException;
import com.anthropic.errors.NoCredentialsException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;

/**
 * Beantwortet Fragen zu einer Seite über die Claude API, standardmäßig mit Claude Haiku 4.5. Anders als das lokale
 * Modell bekommt Claude den ganzen Seitentext (bis {@code contextChars}). Der Seitentext steht als eigener Block mit
 * Cache-Markierung vor der Frage – weitere Fragen zur selben Seite innerhalb von fünf Minuten lesen ihn aus dem
 * Prompt-Cache (bei Haiku ab 4096 Tokens).
 */
final class ClaudeAnswerer implements PageSummarizer.Remote {

    static final String DEFAULT_MODEL = "claude-haiku-4-5";
    private static final long MAX_TOKENS = 1024;
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    /**
     * @param apiKey  leer = aus der Umgebung ({@code ANTHROPIC_API_KEY}, {@code ant auth login})
     * @param baseUrl leer = https://api.anthropic.com
     */
    record Settings(String apiKey, String baseUrl, String model) {
        Settings {
            model = model == null || model.isBlank() ? DEFAULT_MODEL : model.strip();
        }
    }

    private final Settings settings;

    ClaudeAnswerer(Settings settings) {
        this.settings = settings;
    }

    @Override
    public String model() {
        return settings.model();
    }

    @Override
    public PageSummarizer.Answer answer(String system, String document, String question) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(settings.model())
                .maxTokens(MAX_TOKENS)
                .system(system)
                .addUserMessageOfBlockParams(List.of(
                        ContentBlockParam.ofText(TextBlockParam.builder().text(document)
                                .cacheControl(CacheControlEphemeral.builder().build()).build()),
                        ContentBlockParam.ofText(question)))
                .build();
        long start = System.currentTimeMillis();
        AnthropicClient client = client();
        try {
            Message m = client.messages().create(params);
            long millis = System.currentTimeMillis() - start;
            if (StopReason.REFUSAL.equals(m.stopReason().orElse(null))) {
                throw new IllegalStateException("Claude hat die Antwort abgelehnt.");
            }
            String text = String.join("", m.content().stream().flatMap(b -> b.text().stream()).map(t -> t.text())
                    .toList()).strip();
            if (StopReason.MAX_TOKENS.equals(m.stopReason().orElse(null))) {
                text += "\n… [Antwort am Längenlimit abgeschnitten]";
            }
            var u = m.usage();
            long in = u.inputTokens() + u.cacheCreationInputTokens().orElse(0L) + u.cacheReadInputTokens().orElse(0L);
            return new PageSummarizer.Answer(text, in, u.outputTokens(), millis);
        } catch (RuntimeException e) {
            throw translate(e);
        } finally {
            client.close();
        }
    }

    private AnthropicClient client() {
        AnthropicOkHttpClient.Builder b = AnthropicOkHttpClient.builder().timeout(TIMEOUT);
        try {
            // ANTHROPIC_API_KEY, ANTHROPIC_BASE_URL bzw. Profil aus `ant auth login`; Einstellungen gehen vor
            b.fromEnv();
        } catch (NoCredentialsException e) {
            if (blank(settings.apiKey())) {
                throw missingKey();
            }
        }
        if (!blank(settings.apiKey())) {
            b.apiKey(settings.apiKey().strip());
        }
        if (!blank(settings.baseUrl())) {
            b.baseUrl(settings.baseUrl().strip());
        }
        try {
            return b.build();
        } catch (NoCredentialsException | IllegalStateException e) {
            throw missingKey();
        }
    }

    private RuntimeException translate(RuntimeException e) {
        return switch (e) {
            case UnauthorizedException u -> new IllegalStateException("Claude API: Zugriff verweigert (401) – API-Key "
                    + "im Modul Web-Abruf prüfen.", e);
            case PermissionDeniedException p -> new IllegalStateException("Claude API: keine Berechtigung (403) für "
                    + settings.model() + ".", e);
            case RateLimitException r -> new IllegalStateException("Claude API: Rate-Limit erreicht.", e);
            case AnthropicServiceException s -> new IllegalStateException("Claude API: Fehler " + s.statusCode() + " – "
                    + s.getMessage(), e);
            case AnthropicIoException io -> new IllegalStateException("Claude API nicht erreichbar: " + io.getMessage(),
                    e);
            case NoCredentialsException n -> missingKey();
            case CredentialResolutionException c -> missingKey();
            default -> e;
        };
    }

    private static IllegalStateException missingKey() {
        return new IllegalStateException("Kein Claude-API-Key – in der DevTools-App unter Module → Web-Abruf → "
                + "„Claude API-Key“ eintragen oder ANTHROPIC_API_KEY setzen.");
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
