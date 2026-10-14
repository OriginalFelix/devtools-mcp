package systems.grebe.devtools.mcp.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;
import java.util.Set;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import systems.grebe.devtools.mcp.config.ModuleSettings;

class McpRuntimeTest {

    private final McpSyncServer server = mock(McpSyncServer.class);
    private final McpRuntime runtime = new McpRuntime(server, ToolScope.LOCAL, new ToolInvocationLog());

    public static class DemoTools {
        @Tool(name = "status", description = "Zeigt den Status." + ShellHints.GIT)
        public String status() {
            return "ok";
        }

        @Tool(name = "log", description = "Historie.")
        public String log() {
            return "log";
        }
    }

    private static final ToolModule DEMO = new ToolModule() {
        @Override
        public String id() {
            return "git";
        }

        @Override
        public String displayName() {
            return "Git";
        }

        @Override
        public String description() {
            return "";
        }

        @Override
        public List<ToolCallback> createTools(ModuleConfig config) {
            return ToolBeans.callbacks(new DemoTools());
        }
    };

    private static final ModuleSettings ON = new ModuleSettings(true, Set.of(), Map.of());

    @Test
    void rebuildWithUnchangedDefinitionsKeepsRegistrationsAndSendsNoListChange() {
        runtime.rebuild(DEMO, ON, List.of());
        runtime.rebuild(DEMO, ON, List.of());
        runtime.rebuild(DEMO, ON, List.of());

        verify(server, times(2)).addTool(any());
        verify(server, never()).removeTool(anyString());
        assertThat(runtime.activeToolNames()).containsExactly("git_log", "git_status");
    }

    @Test
    void disabledToolIsRemovedAndOthersStay() {
        runtime.rebuild(DEMO, ON, List.of());
        runtime.rebuild(DEMO, new ModuleSettings(true, Set.of("git_log"), Map.of()), List.of());

        verify(server, times(1)).removeTool("git_log");
        verify(server, times(2)).addTool(any());
        assertThat(runtime.activeToolNames()).containsExactly("git_status");
    }

    @Test
    void lazyModeKeepsToolsActiveButDoesNotOfferThem() {
        runtime.setContext(ContextSettings.of(true, ModuleConfig.of(List.of(), Map.of(
                ContextSettings.LAZY_TOOLS, "true", ContextSettings.LAZY_KEEP, "git_status"))));
        runtime.rebuild(DEMO, ON, List.of());

        ArgumentCaptor<McpServerFeatures.SyncToolSpecification> added =
                ArgumentCaptor.forClass(McpServerFeatures.SyncToolSpecification.class);
        verify(server, times(1)).addTool(added.capture());
        assertThat(added.getValue().tool().name()).isEqualTo("git_status");
        assertThat(runtime.activeToolNames()).containsExactly("git_log", "git_status");
        assertThat(runtime.enabledTool("git_log")).isPresent();
        assertThat(runtime.exposed().tools()).isEqualTo(1);
        assertThat(runtime.exposed().activeTools()).isEqualTo(2);
    }

    @Test
    void shellHintsOnceStripsHintFromOfferedDescription() {
        runtime.setContext(ContextSettings.of(true, ModuleConfig.of(List.of(), Map.of(ContextSettings.SHELL_HINTS_ONCE, "true"))));
        runtime.rebuild(DEMO, ON, List.of());

        ArgumentCaptor<McpServerFeatures.SyncToolSpecification> added =
                ArgumentCaptor.forClass(McpServerFeatures.SyncToolSpecification.class);
        verify(server, times(2)).addTool(added.capture());
        assertThat(added.getAllValues()).extracting(s -> s.tool().description())
                .contains("Zeigt den Status.", "Historie.");
    }

    @Test
    void changedSettingsReplaceOnlyChangedTools() {
        runtime.rebuild(DEMO, ON, List.of());
        runtime.setContext(ContextSettings.of(true, ModuleConfig.of(List.of(), Map.of(ContextSettings.SHELL_HINTS_ONCE, "true"))));
        runtime.rebuild(DEMO, ON, List.of());

        // git_status hat jetzt eine kürzere Beschreibung → neu angemeldet; git_log bleibt
        verify(server, times(1)).removeTool("git_status");
        verify(server, never()).removeTool("git_log");
        verify(server, times(3)).addTool(any());
    }

    @Test
    void imagesAttachedByToolBecomeImageContent() {
        McpSchema.Tool tool = McpSchema.Tool.builder().name("browser_screenshot").inputSchema(
                new McpSchema.JsonSchema("object", Map.of(), List.of(), null, null, null)).build();
        McpServerFeatures.SyncToolSpecification spec = new McpServerFeatures.SyncToolSpecification(tool,
                (exchange, request) -> {
                    ToolImages.add(new byte[]{1, 2, 3}, "image/png");
                    return McpSchema.CallToolResult.builder()
                            .content(List.of(McpSchema.TextContent.builder("Screenshot").build())).isError(false).build();
                });

        McpSchema.CallToolResult r = McpRuntime.withImages(spec).callHandler().apply(null,
                new McpSchema.CallToolRequest("browser_screenshot", Map.of()));

        assertThat(r.content()).hasSize(2);
        assertThat(r.content().get(1)).isInstanceOfSatisfying(McpSchema.ImageContent.class, i -> {
            assertThat(i.mimeType()).isEqualTo("image/png");
            assertThat(i.data()).isEqualTo("AQID");
        });
        assertThat(ToolImages.active()).isFalse();
        assertThat(ToolImages.add(new byte[]{1}, "image/png")).isFalse();
    }
}
