package systems.grebe.devtools.mcp.modules.skills;

import java.nio.file.Path;
import java.util.Map;

import org.springframework.context.ConfigurableApplicationContext;

/** Öffentlicher Zugang zum schlanken Skill-Kontext aus {@link SkillServiceTest} – für Tests anderer Pakete. */
public final class SkillTestContext {

    private SkillTestContext() {
    }

    public static ConfigurableApplicationContext start(Path home) {
        return SkillServiceTest.start(home, Map.of());
    }

    /** Benutzer mit fester Git-E-Mail statt ~/.gitconfig. */
    public static SkillUser user(systems.grebe.devtools.mcp.config.SettingsStore store, String gitEmail) {
        return new SkillUser(store, () -> java.util.Optional.ofNullable(gitEmail));
    }
}
