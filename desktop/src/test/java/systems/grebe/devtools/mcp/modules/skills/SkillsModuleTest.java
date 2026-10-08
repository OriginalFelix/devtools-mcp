package systems.grebe.devtools.mcp.modules.skills;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import systems.grebe.devtools.mcp.core.ModuleConfig;

import static org.assertj.core.api.Assertions.assertThat;

/** Modulverhalten ohne Spring-Kontext: Tool-Zuschnitt nach den Schaltern. */
class SkillsModuleTest {

    private final SkillsModule module = new SkillsModule(null, new SkillReview(), new SkillReviewTracker(null, null),
            () -> Path.of("data"));

    private ModuleConfig config(Map<String, String> values) {
        return ModuleConfig.of(module.configSchema(), values);
    }

    @Test
    void toolSelectionFollowsSwitches() {
        assertThat(module.createTools(config(Map.of()))).hasSize(9);
        assertThat(module.createTools(config(Map.of(SkillsModule.ALLOW_WRITE, "false")))).hasSize(3);
        assertThat(module.createTools(config(Map.of(SkillsModule.ALLOW_DELETE, "true")))).hasSize(10);
    }

    @Test
    void fileDirectoriesGetGlobalShares() {
        assertThat(module.sharedDirectoryFields()).containsExactly(SkillsModule.FILE_DIRS);
        assertThat(SkillsModule.attachments(() -> Path.of("data"))).isEqualTo(Path.of("data", "attachments"));
    }

    @Test
    void noDatabaseSettingsAnyMore() {
        assertThat(module.configSchema()).extracting(f -> f.key())
                .doesNotContain(SkillsModule.LEGACY_JDBC_URL, SkillsModule.LEGACY_USER_EMAIL);
    }
}
