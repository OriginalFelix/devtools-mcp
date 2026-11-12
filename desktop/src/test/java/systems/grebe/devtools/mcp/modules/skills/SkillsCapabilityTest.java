package systems.grebe.devtools.mcp.modules.skills;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SkillsCapabilityTest {

    @Test
    void capabilitiesCarryTheSkillsExtension() {
        JsonMapper mapper = (JsonMapper) new SkillsCapability().postProcessAfterInitialization(
                JsonMapper.builder().build(), "jsonMapper");
        String json = mapper.writeValueAsString(McpSchema.ServerCapabilities.builder().tools(true).build());
        assertThat(json).contains("\"extensions\":{\"io.modelcontextprotocol/skills\":{}}").contains("\"tools\"");
    }
}
