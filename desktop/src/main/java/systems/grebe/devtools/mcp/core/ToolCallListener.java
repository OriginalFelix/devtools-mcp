package systems.grebe.devtools.mcp.core;

import org.springframework.ai.chat.model.ToolContext;

/**
 * Wird nach jedem erfolgreichen Tool-Aufruf eines beliebigen Moduls benachrichtigt und darf das Ergebnis ergänzen –
 * z.B. um einen Hinweis an das LLM. Implementierungen sind Spring-Beans; Fehler darin werden geloggt und brechen den
 * Tool-Aufruf nicht ab.
 */
public interface ToolCallListener {

    /** Ein abgeschlossener Aufruf. {@code sessionId} ist die MCP-Session des Clients, {@code null} wenn unbekannt. */
    record ToolCall(String moduleId, String toolName, String input, String sessionId) {
    }

    /** @return das (ggf. ergänzte) Ergebnis, das an das LLM geht */
    String afterSuccess(ToolCall call, String result);

    /** MCP-Session-ID aus dem Tool-Kontext, den Spring AI beim Aufruf über MCP mitgibt. */
    static String sessionId(ToolContext toolContext) {
        return McpExchanges.sessionId(toolContext);
    }
}
