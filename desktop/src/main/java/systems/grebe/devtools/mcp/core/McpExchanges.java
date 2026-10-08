package systems.grebe.devtools.mcp.core;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.McpToolUtils;

/** Zugriff auf den MCP-Exchange (Client, Session) im Tool-Kontext, den Spring AI beim Aufruf über MCP mitgibt. */
public final class McpExchanges {

    private McpExchanges() {
    }

    /** MCP-Exchange des laufenden Tool-Aufrufs ({@code null} außerhalb von MCP, z.B. in Tests ohne Client). */
    public static McpSyncServerExchange of(ToolContext toolContext) {
        Object e = toolContext == null ? null : toolContext.getContext().get(McpToolUtils.TOOL_CONTEXT_MCP_EXCHANGE_KEY);
        return e instanceof McpSyncServerExchange x ? x : null;
    }

    /** MCP-Session-ID des Aufrufs, {@code null} wenn unbekannt. */
    public static String sessionId(ToolContext toolContext) {
        McpSyncServerExchange exchange = of(toolContext);
        return exchange == null ? null : exchange.sessionId();
    }
}
