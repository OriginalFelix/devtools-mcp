package systems.grebe.devtools.mcp.intellij;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChangesTest {

    @Test
    void findsChangedRangeInNewText() {
        assertNull(Changes.range("abc", "abc"));
        assertArrayEquals(new int[]{2, 4}, Changes.range("a\nb\nc", "a\nXY\nc"));   // ersetzt
        assertArrayEquals(new int[]{3, 6}, Changes.range("abc", "abcdef"));         // angehängt
        assertArrayEquals(new int[]{2, 2}, Changes.range("abbc", "abc"));           // gelöscht: nur die Position
        assertArrayEquals(new int[]{0, 3}, Changes.range("", "xyz"));
        assertArrayEquals(new int[]{1, 4}, Changes.range("a..b", "aX.Yb"));        // erste bis letzte Änderung
    }
}
