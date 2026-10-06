package systems.grebe.devtools.mcp.core;

import java.util.Map;
import java.util.function.BiFunction;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Verbindet {@link ToolProgress} mit dem MCP-Client: Spring AI reicht das {@code _meta.progressToken} nicht an die
 * Tools durch, deshalb hüllt {@link #wrap} jede Tool-Spezifikation ein und meldet für die Dauer des Aufrufs an den
 * Exchange des Clients.
 */
public final class McpProgress {

    private McpProgress() {
    }

    /** Hüllt den Handler so ein, dass Tools während des Aufrufs {@link ToolProgress#report} verwenden können. */
    public static McpServerFeatures.SyncToolSpecification wrap(McpServerFeatures.SyncToolSpecification spec) {
        BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> handler = spec.callHandler();
        return new McpServerFeatures.SyncToolSpecification(spec.tool(), (exchange, request) -> {
            Object token = progressToken(request.meta());
            if (token == null || exchange == null) {
                return handler.apply(exchange, request);
            }
            return ToolProgress.callWith((message, count) -> exchange.progressNotification(
                    new McpSchema.ProgressNotification(token, count, null, message)),
                    () -> handler.apply(exchange, request));
        });
    }

    private static Object progressToken(Map<String, Object> meta) {
        return meta == null ? null : meta.get("progressToken");
    }
}
