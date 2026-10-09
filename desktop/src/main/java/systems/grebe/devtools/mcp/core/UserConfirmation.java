package systems.grebe.devtools.mcp.core;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

/**
 * Fragt den Nutzer während eines Tool-Aufrufs um Zustimmung – etwa bevor eine Berechtigung erteilt wird. Die Antwort
 * kommt vom Nutzer selbst, nie vom LLM: entweder über den MCP-Client ({@code elicitation/create}, der Client zeigt ein
 * Formular) oder über einen Dialog der Desktop-App ({@link #setDesktopHandler}).
 */
@Component
public class UserConfirmation {

    private static final Logger LOG = LoggerFactory.getLogger(UserConfirmation.class);

    /** Wie lange auf eine Antwort im Dialog der App gewartet wird. */
    static final Duration DESKTOP_TIMEOUT = Duration.ofMinutes(5);

    /** Feld des Formulars, das der Client zeigt. */
    static final String GRANT = "grant";

    /** Wo gefragt wird. */
    public enum Channel {
        /** Über den MCP-Client, sonst die Desktop-App. */
        AUTO,
        /** Nur über den MCP-Client (Elicitation). */
        CLIENT,
        /** Nur über einen Dialog der Desktop-App. */
        APP;

        /** Aus einer Einstellung ({@code auto}, {@code client}, {@code app}, ohne Groß-/Kleinschreibung); sonst {@link #AUTO}. */
        public static Channel parse(String value) {
            if (value != null) {
                for (Channel c : values()) {
                    if (c.name().equalsIgnoreCase(value.strip())) {
                        return c;
                    }
                }
            }
            return AUTO;
        }
    }

    /** Ergebnis einer Rückfrage. */
    public enum Answer { GRANTED, DECLINED, UNAVAILABLE }

    /** @param via wo gefragt wurde bzw. warum nicht gefragt werden konnte */
    public record Result(Answer answer, String via) {
    }

    /** Dialog der Desktop-App: erfüllt das Future mit {@code true} (zugestimmt) oder {@code false}. */
    @FunctionalInterface
    public interface DesktopHandler {
        CompletableFuture<Boolean> ask(String title, String message);
    }

    private volatile DesktopHandler desktop;

    /** Setzt den Dialog der Oberfläche ({@code null} = keine Oberfläche, z.B. in Tests). */
    public void setDesktopHandler(DesktopHandler handler) {
        this.desktop = handler;
    }

    /** MCP-Exchange des laufenden Tool-Aufrufs ({@code null} außerhalb von MCP). */
    public static McpSyncServerExchange exchange(ToolContext toolContext) {
        return McpExchanges.of(toolContext);
    }

    /** Ob der Client Formulare per Elicitation anbietet (ein leeres {@code elicitation} heißt nach der Spezifikation Formular). */
    public static boolean canElicit(McpSyncServerExchange exchange) {
        McpSchema.ClientCapabilities caps = exchange == null ? null : exchange.getClientCapabilities();
        McpSchema.ClientCapabilities.Elicitation e = caps == null ? null : caps.elicitation();
        return e != null && (e.form() != null || e.url() == null);
    }

    /**
     * Stellt die Frage und wartet auf die Antwort (blockierend).
     *
     * @param exchange MCP-Exchange des Aufrufs, {@code null} = kein Client erreichbar
     * @param question was zugestimmt werden soll (Klartext, mehrzeilig)
     */
    public Result ask(McpSyncServerExchange exchange, Channel channel, String title, String question) {
        if (channel != Channel.APP && canElicit(exchange)) {
            try {
                return viaClient(exchange, title, question);
            } catch (RuntimeException e) {
                LOG.warn("Rückfrage über den MCP-Client fehlgeschlagen", e);
                if (channel == Channel.CLIENT) {
                    return new Result(Answer.UNAVAILABLE, "Rückfrage über den Client fehlgeschlagen: "
                            + ManagedToolCallback.describe(e));
                }
            }
        }
        if (channel == Channel.CLIENT) {
            return new Result(Answer.UNAVAILABLE, "der MCP-Client bietet keine Rückfragen an (elicitation)");
        }
        DesktopHandler handler = desktop;
        if (handler == null) {
            return new Result(Answer.UNAVAILABLE, channel == Channel.APP
                    ? "die DevTools-App hat keine Oberfläche für Rückfragen"
                    : "weder der MCP-Client (elicitation) noch die DevTools-App können nachfragen");
        }
        return viaDesktop(handler, title, question);
    }

    /**
     * Wie {@link #ask}, wirft aber, wenn der Nutzer nicht zustimmt oder niemand gefragt werden kann.
     *
     * @param confirmation {@code null} = keine Rückfrage möglich
     * @param refused      Anfang der Meldung, was nicht geschah (z.B. {@code "Nicht gesendet"})
     * @param unavailable  Satz, was der Nutzer tun kann, wenn keine Rückfrage möglich ist
     */
    public static void require(UserConfirmation confirmation, McpSyncServerExchange exchange, Channel channel,
                               String title, String question, String refused, String unavailable) {
        if (confirmation == null) {
            throw new IllegalStateException(refused + ": keine Rückfrage beim Nutzer möglich.");
        }
        Result r = confirmation.ask(exchange, channel, title, question);
        switch (r.answer()) {
            case GRANTED -> { }
            case DECLINED -> throw new IllegalStateException(refused + ": vom Nutzer abgelehnt (" + r.via() + "). "
                    + "Nicht erneut versuchen, ohne dass der Nutzer es ausdrücklich will.");
            default -> throw new IllegalStateException(refused + ": keine Rückfrage möglich (" + r.via() + "). "
                    + unavailable);
        }
    }

    private static Result viaClient(McpSyncServerExchange exchange, String title, String question) {
        Map<String, Object> grant = new LinkedHashMap<>();
        grant.put("type", "boolean");
        grant.put("title", "Erteilen");
        grant.put("description", "Ja = erteilen, Nein = ablehnen");
        grant.put("default", true);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(GRANT, grant));
        schema.put("required", List.of(GRANT));
        McpSchema.ElicitResult r = exchange.createElicitation(McpSchema.ElicitFormRequest
                .builder(title + "\n\n" + question, schema).build());
        boolean granted = r != null && r.action() == McpSchema.ElicitResult.Action.ACCEPT
                && r.content() != null && Boolean.parseBoolean(String.valueOf(r.content().get(GRANT)));
        String client = exchange.getClientInfo() == null ? "MCP-Client" : exchange.getClientInfo().name();
        return new Result(granted ? Answer.GRANTED : Answer.DECLINED, client);
    }

    private static Result viaDesktop(DesktopHandler handler, String title, String question) {
        CompletableFuture<Boolean> answer = handler.ask(title, question);
        try {
            return new Result(Boolean.TRUE.equals(answer.get(DESKTOP_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                    ? Answer.GRANTED : Answer.DECLINED, "DevTools-App");
        } catch (TimeoutException e) {
            answer.cancel(true); // schließt den Dialog
            return new Result(Answer.DECLINED, "DevTools-App (keine Antwort in "
                    + DESKTOP_TIMEOUT.toMinutes() + " Minuten)");
        } catch (InterruptedException e) {
            answer.cancel(true);
            Thread.currentThread().interrupt();
            return new Result(Answer.DECLINED, "DevTools-App (abgebrochen)");
        } catch (CancellationException e) {
            return new Result(Answer.DECLINED, "DevTools-App (Dialog geschlossen)");
        } catch (ExecutionException e) {
            return new Result(Answer.UNAVAILABLE, "Dialog der DevTools-App fehlgeschlagen: "
                    + ManagedToolCallback.describe(e.getCause()));
        }
    }
}
