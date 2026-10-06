package systems.grebe.devtools.mcp.core;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ToolProgressTest {

    private static final McpSchema.Tool TOOL = McpSchema.Tool.builder().name("t").inputSchema(
            new McpSchema.JsonSchema("object", Map.of(), List.of(), null, null, null)).build();

    private static McpServerFeatures.SyncToolSpecification spec() {
        return McpProgress.wrap(new McpServerFeatures.SyncToolSpecification(TOOL, (exchange, request) -> {
            ToolProgress.report("erste Zeile");
            ToolProgress.report("verworfen – zu kurz nach der ersten");
            return McpSchema.CallToolResult.builder().addTextContent(String.valueOf(ToolProgress.active())).build();
        }));
    }

    @Test
    void reportsToTheClientWhenATokenIsSent() {
        McpSyncServerExchange exchange = mock(McpSyncServerExchange.class);
        var request = new McpSchema.CallToolRequest("t", Map.of(), Map.of("progressToken", "tok-1"));
        var result = spec().callHandler().apply(exchange, request);

        ArgumentCaptor<McpSchema.ProgressNotification> sent = ArgumentCaptor.forClass(McpSchema.ProgressNotification.class);
        verify(exchange, times(1)).progressNotification(sent.capture());
        assertThat(sent.getValue().progressToken()).isEqualTo("tok-1");
        assertThat(sent.getValue().progress()).isEqualTo(1.0);
        assertThat(sent.getValue().message()).isEqualTo("erste Zeile");
        assertThat(((McpSchema.TextContent) result.content().getFirst()).text()).isEqualTo("true");
        assertThat(ToolProgress.active()).isFalse();
    }

    @Test
    void staysSilentWithoutToken() {
        McpSyncServerExchange exchange = mock(McpSyncServerExchange.class);
        var result = spec().callHandler().apply(exchange, new McpSchema.CallToolRequest("t", Map.of()));
        verify(exchange, never()).progressNotification(any());
        assertThat(((McpSchema.TextContent) result.content().getFirst()).text()).isEqualTo("false");
    }
}
