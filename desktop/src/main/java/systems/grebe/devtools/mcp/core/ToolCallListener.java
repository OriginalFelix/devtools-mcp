package systems.grebe.devtools.mcp.core;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.McpToolUtils;

/**
 * Wird nach jedem erfolgreichen Tool-Aufruf eines beliebigen Moduls benachrichtigt und darf das Ergebnis ergänzen –
 * z.B. um einen Hinweis an das LLM. Implementierungen sind Spring-Beans; Fehler darin werden geloggt und brechen den
 * Tool-Aufruf nicht ab.
 */
public interface ToolCallListener {

    /**
     * Ein abgeschlossener Aufruf. {@code sessionId} ist die MCP-Session des Clients, {@code null} wenn unbekannt;
     * {@code readOnly}, wenn das Tool als nur lesend markiert ist ({@link ToolHints}).
     */
    record ToolCall(String moduleId, String toolName, String input, String sessionId, boolean readOnly) {

        public ToolCall(String moduleId, String toolName, String input, String sessionId) {
            this(moduleId, toolName, input, sessionId, false);
        }
    }

    /** @return das (ggf. ergänzte) Ergebnis, das an das LLM geht */
    String afterSuccess(ToolCall call, String result);

    /** MCP-Session-ID aus dem Tool-Kontext, den Spring AI beim Aufruf über MCP mitgibt. */
    static String sessionId(ToolContext toolContext) {
        if (toolContext == null) {
            return null;
        }
        Object exchange = toolContext.getContext().get(McpToolUtils.TOOL_CONTEXT_MCP_EXCHANGE_KEY);
        return exchange instanceof McpSyncServerExchange e ? e.sessionId() : null;
    }
}
