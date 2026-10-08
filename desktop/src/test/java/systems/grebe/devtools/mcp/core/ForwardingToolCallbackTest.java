package systems.grebe.devtools.mcp.core;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;

import static org.assertj.core.api.Assertions.assertThat;

class ForwardingToolCallbackTest {

    @ToolHints(readOnly = true)
    public static class Sample {
        @Tool(name = "echo", description = "Echo")
        public String echo() {
            return "echo";
        }
    }

    private record Wrapper(ToolCallback delegate) implements ForwardingToolCallback {
    }

    @Test
    void forwardsEverythingAndKeepsTheHintsVisible() {
        ToolCallback inner = ToolBeans.callbacks(new Sample()).getFirst();
        Wrapper wrapper = new Wrapper(inner);

        assertThat(wrapper.getToolDefinition()).isSameAs(inner.getToolDefinition());
        assertThat(wrapper.getToolMetadata()).isEqualTo(inner.getToolMetadata());
        assertThat(wrapper.call("{}")).isEqualTo(inner.call("{}"));
        assertThat(ToolBeans.hints(new Wrapper(wrapper)).readOnly()).isTrue();
        assertThat(List.of(wrapper)).hasSize(1);
    }
}
