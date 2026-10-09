package systems.grebe.devtools.mcp.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;
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

    // ------------------------------------------------------------------ Bilder und KI-Session über die Anmeldung beim Server

    /** Tools, die ein Bild anhängen und die aufrufende KI melden – wie window_screenshot und die Fenster-Tools. */
    public static class SessionTools {
        private final McpRuntime runtime;

        public SessionTools(McpRuntime runtime) {
            this.runtime = runtime;
        }

        @Tool(name = "shot", description = "Bild." + ShellHints.WINDOW)
        public String shot() {
            ToolImages.attach("image/png", new byte[] {1, 2, 3});
            ToolSession s = ToolSession.current();
            return s.id() + "/" + s.client();
        }

        /** Ruft ein anderes Tool auf wie ein Skript: ohne Tool-Kontext (RegistryToolCaller → activeTool). */
        @Tool(name = "script", description = "Ruft shot wie ein Skript auf.")
        public String script() {
            return "skript:" + runtime.activeTool("window_shot").orElseThrow().call("{}");
        }

        /** Ruft ein anderes Tool auf wie context_call: mit dem Tool-Kontext des äußeren Aufrufs. */
        @Tool(name = "outer", description = "Ruft shot auf.")
        public String outer(ToolContext toolContext) {
            return "außen:" + runtime.enabledTool("window_shot").orElseThrow().call("{}", toolContext);
        }
    }

    private ToolModule sessionModule() {
        return new ToolModule() {
            @Override
            public String id() {
                return "window";
            }

            @Override
            public String displayName() {
                return "Fenster";
            }

            @Override
            public String description() {
                return "";
            }

            @Override
            public List<ToolCallback> createTools(ModuleConfig config) {
                return ToolBeans.callbacks(new SessionTools(runtime));
            }
        };
    }

    private McpServerFeatures.SyncToolSpecification added(String name) {
        ArgumentCaptor<McpServerFeatures.SyncToolSpecification> added =
                ArgumentCaptor.forClass(McpServerFeatures.SyncToolSpecification.class);
        verify(server, org.mockito.Mockito.atLeastOnce()).addTool(added.capture());
        return added.getAllValues().stream().filter(s -> s.tool().name().equals(name)).findFirst().orElseThrow();
    }

    @SuppressWarnings("deprecation")
    private static McpSchema.CallToolResult call(McpServerFeatures.SyncToolSpecification spec) {
        McpSyncServerExchange exchange = org.mockito.Mockito.mock(McpSyncServerExchange.class);
        when(exchange.sessionId()).thenReturn("ki-1");
        when(exchange.getClientInfo()).thenReturn(new McpSchema.Implementation("Claude Code", "1"));
        return spec.callHandler().apply(exchange, new McpSchema.CallToolRequest(spec.tool().name(), Map.of()));
    }

    private static String text(McpSchema.CallToolResult result) {
        return result.content().stream().filter(McpSchema.TextContent.class::isInstance)
                .map(c -> ((McpSchema.TextContent) c).text()).findFirst().orElse("");
    }

    private static List<String> images(McpSchema.CallToolResult result) {
        return result.content().stream().filter(McpSchema.ImageContent.class::isInstance)
                .map(c -> ((McpSchema.ImageContent) c).mimeType()).toList();
    }

    @Test
    void imageAndSessionReachTheClientAlsoAfterARebuild() {
        runtime.rebuild(sessionModule(), ON, List.of());
        McpServerFeatures.SyncToolSpecification registered = added("window_shot");

        McpSchema.CallToolResult first = call(registered);
        assertThat(text(first)).contains("ki-1/Claude Code");
        assertThat(images(first)).containsExactly("image/png");

        // unveränderte Definition: nur der Aufruf im Slot wird getauscht – Bilder und Session bleiben
        runtime.rebuild(sessionModule(), ON, List.of());
        verify(server, times(3)).addTool(any()); // drei Tools, jedes nur beim ersten Aufbau angemeldet
        McpSchema.CallToolResult second = call(registered);
        assertThat(text(second)).contains("ki-1/Claude Code");
        assertThat(images(second)).containsExactly("image/png");
    }

    @Test
    void nestedCallLikeContextCallKeepsSessionAndCollectsImages() {
        runtime.rebuild(sessionModule(), ON, List.of());

        McpSchema.CallToolResult result = call(added("window_outer"));

        assertThat(text(result)).contains("außen:").contains("ki-1/Claude Code");
        assertThat(images(result)).containsExactly("image/png");
        assertThat(ToolSession.current()).isEqualTo(ToolSession.LOCAL); // nach dem Aufruf zurückgesetzt
    }

    @Test
    void scriptCallWithoutToolContextActsForTheCallingAi() {
        runtime.rebuild(sessionModule(), ON, List.of());

        McpSchema.CallToolResult result = call(added("window_script"));

        assertThat(text(result)).contains("skript:").contains("ki-1/Claude Code");
        assertThat(images(result)).containsExactly("image/png");
    }

    @Test
    void shellHintsOnceStripsTheWindowHint() {
        assertThat(ShellHints.forModule("window")).isEqualTo(ShellHints.WINDOW.strip());
        runtime.setContext(ContextSettings.of(true, ModuleConfig.of(List.of(), Map.of(ContextSettings.SHELL_HINTS_ONCE, "true"))));
        runtime.rebuild(sessionModule(), ON, List.of());

        assertThat(added("window_shot").tool().description()).isEqualTo("Bild.");
    }
}
