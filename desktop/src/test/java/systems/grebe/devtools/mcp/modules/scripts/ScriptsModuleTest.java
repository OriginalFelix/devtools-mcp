package systems.grebe.devtools.mcp.modules.scripts;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.McpToolHints;

import static org.assertj.core.api.Assertions.assertThat;

/** Modulverhalten ohne Spring-Kontext: Tool-Zuschnitt nach den Schaltern, Schreiben standardmäßig aus. */
class ScriptsModuleTest {

    private final ScriptsModule module = new ScriptsModule(null);

    private ModuleConfig config(Map<String, String> values) {
        return ModuleConfig.of(module.configSchema(), values);
    }

    @Test
    void writingIsOffByDefault() {
        assertThat(module.createTools(config(Map.of()))).extracting(t -> t.getToolDefinition().name())
                .containsExactlyInAnyOrder("list", "view");
        assertThat(module.createTools(config(Map.of(ScriptsModule.ALLOW_WRITE, "true"))))
                .extracting(t -> t.getToolDefinition().name()).contains("save").doesNotContain("delete");
        assertThat(module.createTools(config(Map.of(ScriptsModule.ALLOW_DELETE, "true"))))
                .extracting(t -> t.getToolDefinition().name()).contains("delete").doesNotContain("save");
    }

    @Test
    void readToolsAreMarkedReadOnly() {
        for (ToolCallback t : module.createTools(config(Map.of()))) {
            assertThat(McpToolHints.annotations(t).readOnlyHint()).as(t.getToolDefinition().name()).isTrue();
        }
    }
}
