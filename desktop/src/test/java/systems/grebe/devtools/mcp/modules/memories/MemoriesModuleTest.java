package systems.grebe.devtools.mcp.modules.memories;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import systems.grebe.devtools.mcp.core.ModuleConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Modulverhalten ohne Spring-Kontext: Tool-Zuschnitt nach den Schaltern. */
class MemoriesModuleTest {

    private final MemoriesModule module = new MemoriesModule(null);

    private ModuleConfig config(Map<String, String> values) {
        return ModuleConfig.of(module.configSchema(), values);
    }

    @Test
    void toolsStayForTemporaryMemoriesWithoutSwitches() {
        // ohne Schalter bleiben save/update/delete – dann nur für temporäre Memories
        assertThat(module.createTools(config(Map.of()))).extracting(t -> t.getToolDefinition().name())
                .containsExactlyInAnyOrder("search", "view", "save", "update", "delete");
        assertThat(description(Map.of(), "delete")).contains("NUR temporäre", "setting='allowDelete'");
        assertThat(description(Map.of(), "save")).doesNotContain("NUR temporäre");
        assertThat(description(Map.of(MemoriesModule.ALLOW_WRITE, "false"), "save"))
                .contains("NUR temporäre", "setting='allowWrite'");
        assertThat(description(Map.of(MemoriesModule.ALLOW_DELETE, "true"), "delete")).doesNotContain("NUR temporäre");
    }

    private String description(Map<String, String> values, String tool) {
        return module.createTools(config(values)).stream().map(ToolCallback::getToolDefinition)
                .filter(d -> d.name().equals(tool)).findFirst().orElseThrow().description();
    }

    @Test
    void temporaryOnlySaveNeedsTemporaryType() {
        MemoryWriteTools tools = new MemoryWriteTools(null, 5_000, true);
        assertThatThrownBy(() -> tools.save("t", "c", null, null, null, null, null))
                .hasMessageContainingAll("type=TEMPORARY", "allowWrite");
        assertThat(MemoryWriteTools.type(" temporary ")).isEqualTo(MemoryViews.Type.TEMPORARY);
        assertThat(MemoryWriteTools.type("Dauerhaft")).isEqualTo(MemoryViews.Type.PERMANENT);
        assertThat(MemoryWriteTools.type("")).isNull();
        assertThatThrownBy(() -> MemoryWriteTools.type("ewig")).hasMessageContaining("PERMANENT oder TEMPORARY");
    }

    @Test
    void instructionsSeparateMemoriesFromSkills() {
        assertThat(module.instructions()).contains("`memories_search`", "`memories_save`", "skill=<name>",
                "`ticket-review`", "`append`", "[DevTools] Frühere Aktionen", "`type=TEMPORARY`");
    }
}
