package systems.grebe.devtools.mcp.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModuleIdTest {

    @Test
    void acceptsLowercaseLettersAndDigitsStartingWithALetter() {
        assertThatCode(() -> ToolRegistry.requireValidModuleId("jira")).doesNotThrowAnyException();
        assertThatCode(() -> ToolRegistry.requireValidModuleId("a1")).doesNotThrowAnyException();
    }

    @Test
    void rejectsEverythingElseWithTheToolPrefixHint() {
        for (String bad : new String[] {null, "", "a", "1abc", "Jira", "ji-ra", "ji_ra", "a".repeat(33)}) {
            assertThatThrownBy(() -> ToolRegistry.requireValidModuleId(bad)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ungültig").hasMessageContaining("Tool-Präfix");
        }
    }
}
