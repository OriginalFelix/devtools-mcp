package systems.grebe.devtools.mcp.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextHelpersTest {

    @Test
    void oneLineCollapsesControlCharactersAndCuts() {
        assertThat(Text.oneLine("  a\nb\t\tc\r\nd  ", 50)).isEqualTo("a b c d");
        assertThat(Text.oneLine("x".repeat(30), 10)).isEqualTo("x".repeat(9) + "…");
        assertThat(Text.oneLine("x".repeat(10), 10)).isEqualTo("x".repeat(10));
    }

    @Test
    void fileSizeUsesGermanDecimalComma() {
        assertThat(Text.fileSize(512)).isEqualTo("512 B");
        assertThat(Text.fileSize(1536)).isEqualTo("1,5 KB");
        assertThat(Text.fileSize(3L * 1024 * 1024)).isEqualTo("3,0 MB");
    }

    @Test
    void limitLinesHandlesAllLineBreaksAndNull() {
        assertThat(Text.limitLines(null, 3)).isEmpty();
        assertThat(Text.limitLines("a\r\nb\rc\nd", 10)).isEqualTo("a\nb\nc\nd");
        assertThat(Text.limitLines("a\nb\nc\nd", 2)).startsWith("a\nb\n… [gekürzt: 2 weitere Zeilen");
    }
}
