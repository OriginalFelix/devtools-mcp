package systems.grebe.devtools.mcp.modules.window;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessPatternsTest {

    @Test
    void startsAnEmptyPattern() {
        assertThat(ProcessPatterns.append("", "winword")).isEqualTo("winword");
        assertThat(ProcessPatterns.append(null, "winword")).isEqualTo("winword");
    }

    @Test
    void addsAnAlternativeAndKeepsOwnExpressions() {
        assertThat(ProcessPatterns.append("charmap", "winword")).isEqualTo("charmap|winword");
        assertThat(ProcessPatterns.append("^calc.*", "winword")).isEqualTo("^calc.*|winword");
    }

    @Test
    void escapesSpecialCharacters() {
        assertThat(ProcessPatterns.append("", "notepad++")).isEqualTo("notepad\\+\\+");
    }

    @Test
    void leavesThePatternWhenItAlreadyMatches() {
        assertThat(ProcessPatterns.append("charmap|WINWORD", "winword")).isEqualTo("charmap|WINWORD");
        assertThat(ProcessPatterns.append("word", "winword")).isEqualTo("word");
    }

    @Test
    void appendsToInvalidPatterns() {
        assertThat(ProcessPatterns.append("(", "winword")).isEqualTo("(|winword");
    }
}
