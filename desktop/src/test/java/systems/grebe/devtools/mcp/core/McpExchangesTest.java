package systems.grebe.devtools.mcp.core;

import java.util.Map;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.McpToolUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpExchangesTest {

    @Test
    void returnsTheExchangeAndItsSessionFromTheToolContext() {
        McpSyncServerExchange exchange = mock(McpSyncServerExchange.class);
        when(exchange.sessionId()).thenReturn("s-1");
        ToolContext context = new ToolContext(Map.of(McpToolUtils.TOOL_CONTEXT_MCP_EXCHANGE_KEY, exchange));

        assertThat(McpExchanges.of(context)).isSameAs(exchange);
        assertThat(McpExchanges.sessionId(context)).isEqualTo("s-1");
        assertThat(ToolCallListener.sessionId(context)).isEqualTo("s-1");
        assertThat(UserConfirmation.exchange(context)).isSameAs(exchange);
    }

    @Test
    void isNullWithoutAnExchange() {
        assertThat(McpExchanges.of(null)).isNull();
        assertThat(McpExchanges.sessionId(null)).isNull();
        assertThat(McpExchanges.of(new ToolContext(Map.of("x", "y")))).isNull();
        assertThat(McpExchanges.of(new ToolContext(Map.of(McpToolUtils.TOOL_CONTEXT_MCP_EXCHANGE_KEY, "kein Exchange")))).isNull();
    }
}
