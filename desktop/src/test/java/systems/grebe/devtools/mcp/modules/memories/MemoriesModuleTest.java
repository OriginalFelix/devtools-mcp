package systems.grebe.devtools.mcp.modules.memories;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import systems.grebe.devtools.mcp.core.ModuleConfig;

import static org.assertj.core.api.Assertions.assertThat;

/** Modulverhalten ohne Spring-Kontext: Tool-Zuschnitt nach den Schaltern. */
class MemoriesModuleTest {

    private final MemoriesModule module = new MemoriesModule(null);

    private ModuleConfig config(Map<String, String> values) {
        return ModuleConfig.of(module.configSchema(), values);
    }

    @Test
    void toolSelectionFollowsSwitches() {
        assertThat(module.createTools(config(Map.of()))).extracting(t -> t.getToolDefinition().name())
                .containsExactlyInAnyOrder("search", "view", "save", "update");
        assertThat(module.createTools(config(Map.of(MemoriesModule.ALLOW_WRITE, "false")))).hasSize(2);
        assertThat(module.createTools(config(Map.of(MemoriesModule.ALLOW_DELETE, "true"))))
                .extracting(ToolCallback::getToolDefinition).extracting(d -> d.name()).contains("delete");
    }

    @Test
    void instructionsSeparateMemoriesFromSkills() {
        assertThat(module.instructions()).contains("`memories_search`", "`memories_save`", "skill=<name>",
                "`ticket-review`", "append");
    }
}
