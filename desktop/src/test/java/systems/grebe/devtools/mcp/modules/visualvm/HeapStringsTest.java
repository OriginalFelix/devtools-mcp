package systems.grebe.devtools.mcp.modules.visualvm;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Strings aus dem Heap-Dump: Umlaute (LATIN1) und UTF-16-Zeichen kommen richtig an. */
class HeapStringsTest {

    private static List<String> values(byte[] bytes) {
        List<String> out = new ArrayList<>();
        for (byte b : bytes) {
            out.add(Byte.toString(b)); // wie die Heap-Engine: vorzeichenbehaftete Werte als Text
        }
        return out;
    }

    @Test
    void latin1BytesAreUnsigned() {
        byte[] bytes = "Grüße aus Köln".getBytes(StandardCharsets.ISO_8859_1);
        assertThat(HeapAnalyzer.decodeString(0, values(bytes), true)).isEqualTo(" \"Grüße aus Köln\"");
    }

    @Test
    void utf16BytesFollowTheByteOrderOfTheDump() {
        assertThat(HeapAnalyzer.decodeString(1, values("Preis 5 €".getBytes(StandardCharsets.UTF_16LE)), true))
                .isEqualTo(" \"Preis 5 €\"");
        assertThat(HeapAnalyzer.decodeString(1, values("Preis 5 €".getBytes(StandardCharsets.UTF_16BE)), false))
                .isEqualTo(" \"Preis 5 €\"");
    }

    @Test
    void longStringsAreCutAfter80Characters() {
        String text = "ä".repeat(100);
        String latin = HeapAnalyzer.decodeString(0, values(text.getBytes(StandardCharsets.ISO_8859_1)), true);
        assertThat(latin).isEqualTo(" \"" + "ä".repeat(80) + "…\"");
        // UTF16: 80 Zeichen, nicht 80 Bytes
        String utf16 = HeapAnalyzer.decodeString(1, values(("€" + text).getBytes(StandardCharsets.UTF_16LE)), true);
        assertThat(utf16).isEqualTo(" \"€" + "ä".repeat(79) + "…\"");
    }
}
