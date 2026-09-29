package systems.grebe.devtools.mcp.core;

import java.util.Map;
import java.util.function.BiFunction;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fortschrittsmeldungen ({@code notifications/progress}) für den laufenden Tool-Aufruf. Der Client zeigt sie während
 * des Wartens an (z.B. die letzte Zeile eines laufenden Befehls); das LLM sieht sie nicht – was es wissen muss, gehört
 * ins Ergebnis des Tools.
 *
 * <p>Gemeldet wird nur, wenn der Client im Aufruf ein {@code _meta.progressToken} mitschickt. Spring AI reicht das Token
 * nicht an die Tools durch; deshalb hüllt {@link #wrap} jede Tool-Spezifikation ein und legt Exchange und Token für die
 * Dauer des Aufrufs in einen {@link ThreadLocal} (das Tool läuft im selben Thread wie der Handler).
 */
public final class ToolProgress {

    private static final Logger LOG = LoggerFactory.getLogger(ToolProgress.class);
    private static final long MIN_INTERVAL_MILLIS = 500;
    private static final int MAX_MESSAGE = 300;

    private static final class Target {
        final McpSyncServerExchange exchange;
        final Object token;
        int count;
        long last;

        Target(McpSyncServerExchange exchange, Object token) {
            this.exchange = exchange;
            this.token = token;
        }
    }

    private static final ThreadLocal<Target> CURRENT = new ThreadLocal<>();

    private ToolProgress() {
    }

    /** Hüllt den Handler so ein, dass Tools während des Aufrufs {@link #report} verwenden können. */
    public static McpServerFeatures.SyncToolSpecification wrap(McpServerFeatures.SyncToolSpecification spec) {
        BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> handler = spec.callHandler();
        return new McpServerFeatures.SyncToolSpecification(spec.tool(), (exchange, request) -> {
            Object token = progressToken(request.meta());
            if (token == null || exchange == null) {
                return handler.apply(exchange, request);
            }
            Target previous = CURRENT.get();
            CURRENT.set(new Target(exchange, token));
            try {
                return handler.apply(exchange, request);
            } finally {
                if (previous == null) {
                    CURRENT.remove();
                } else {
                    CURRENT.set(previous);
                }
            }
        });
    }

    private static Object progressToken(Map<String, Object> meta) {
        return meta == null ? null : meta.get("progressToken");
    }

    /** Ob der laufende Aufruf Fortschritt empfangen kann. */
    public static boolean active() {
        return CURRENT.get() != null;
    }

    /** Ob jetzt eine Meldung gesendet würde – um teure Meldungstexte nur dann zu bauen. */
    public static boolean due() {
        Target t = CURRENT.get();
        return t != null && System.currentTimeMillis() - t.last >= MIN_INTERVAL_MILLIS;
    }

    /**
     * Meldet einen Zwischenstand. Höchstens alle {@value #MIN_INTERVAL_MILLIS} ms, sonst verworfen; Fehler beim Senden
     * werden ignoriert. Ohne Token oder außerhalb eines Tool-Aufrufs wirkungslos.
     */
    public static void report(String message) {
        Target t = CURRENT.get();
        if (t == null || message == null || message.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - t.last < MIN_INTERVAL_MILLIS) {
            return;
        }
        t.last = now;
        String text = message.strip();
        if (text.length() > MAX_MESSAGE) {
            text = text.substring(0, MAX_MESSAGE) + " …";
        }
        try {
            t.exchange.progressNotification(new McpSchema.ProgressNotification(t.token, ++t.count, null, text));
        } catch (RuntimeException e) {
            LOG.debug("Fortschritt nicht gesendet", e);
        }
    }
}
