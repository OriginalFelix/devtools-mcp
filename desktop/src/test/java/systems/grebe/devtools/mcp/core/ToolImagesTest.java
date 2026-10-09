package systems.grebe.devtools.mcp.core;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolImagesTest {

    private static final McpSchema.Tool TOOL = McpSchema.Tool.builder().name("t").inputSchema(
            new McpSchema.JsonSchema("object", Map.of(), List.of(), null, null, null)).build();

    @Test
    void attachedImagesFollowTheText() {
        var spec = ToolImages.wrap(new McpServerFeatures.SyncToolSpecification(TOOL, (exchange, request) -> {
            ToolImages.attach("image/png", new byte[] {1, 2, 3});
            return McpSchema.CallToolResult.builder().addTextContent("Bild 1×1").build();
        }));
        var result = spec.callHandler().apply(null, new McpSchema.CallToolRequest("t", Map.of()));
        assertThat(result.content()).hasSize(2);
        assertThat(((McpSchema.TextContent) result.content().get(0)).text()).isEqualTo("Bild 1×1");
        McpSchema.ImageContent image = (McpSchema.ImageContent) result.content().get(1);
        assertThat(image.mimeType()).isEqualTo("image/png");
        assertThat(image.data()).isEqualTo("AQID");
    }

    @Test
    void outsideACallAttachIsIgnored() {
        ToolImages.attach("image/png", new byte[] {1});
        assertThat(ToolImages.capture(() -> "x").images()).isEmpty();
    }
}
