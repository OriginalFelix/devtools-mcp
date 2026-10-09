package systems.grebe.devtools.mcp.core;

import java.util.function.Supplier;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.McpToolUtils;

/**
 * Die MCP-Session des laufenden Tool-Aufrufs – eine verbundene KI (Unterhaltung/Agent). Während eines Aufrufs über
 * {@link #current()} erreichbar (gleicher Thread wie der Handler, gesetzt von {@link ManagedToolCallback}); ohne MCP
 * (Tests, interne Aufrufe) gilt {@link #LOCAL}.
 *
 * @param id     MCP-Session-ID
 * @param client Name des Clients aus {@code clientInfo} (z.B. „Claude Code“), {@code null} wenn unbekannt
 */
public record ToolSession(String id, String client) {

    public static final ToolSession LOCAL = new ToolSession("local", null);

    private static final ThreadLocal<ToolSession> CURRENT = new ThreadLocal<>();

    public static ToolSession current() {
        ToolSession s = CURRENT.get();
        return s == null ? LOCAL : s;
    }

    /** Führt {@code body} mit {@code session} als {@link #current()} aus. */
    public static <T> T callIn(ToolSession session, Supplier<T> body) {
        ToolSession previous = CURRENT.get();
        CURRENT.set(session);
        try {
            return body.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /**
     * Session aus dem Tool-Kontext, den Spring AI beim Aufruf über MCP mitgibt; ohne MCP-Kontext die laufende
     * ({@link #current()}) – ruft z.B. ein Skript Tools auf, handeln sie für die KI, die das Skript aufgerufen hat.
     * Außerhalb jedes Aufrufs {@link #LOCAL}.
     */
    public static ToolSession of(ToolContext toolContext) {
        if (toolContext == null) {
            return current();
        }
        Object exchange = toolContext.getContext().get(McpToolUtils.TOOL_CONTEXT_MCP_EXCHANGE_KEY);
        if (!(exchange instanceof McpSyncServerExchange e) || e.sessionId() == null) {
            return current();
        }
        String client = e.getClientInfo() == null ? null : e.getClientInfo().name();
        return new ToolSession(e.sessionId(), client == null || client.isBlank() ? null : client);
    }
}
