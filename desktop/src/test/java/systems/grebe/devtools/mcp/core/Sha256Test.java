package systems.grebe.devtools.mcp.core;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import systems.grebe.devtools.mcp.api.Sha256;

import static org.assertj.core.api.Assertions.assertThat;

class Sha256Test {

    private static final String EMPTY = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final String ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @Test
    void knownVectors() {
        assertThat(Sha256.hex(new byte[0])).isEqualTo(EMPTY);
        assertThat(Sha256.hex("abc")).isEqualTo(ABC);
        assertThat(Sha256.hex("abc".getBytes(StandardCharsets.UTF_8))).isEqualTo(ABC);
    }

    @Test
    void incrementalDigest() {
        var md = Sha256.newDigest();
        md.update((byte) 'a');
        md.update("bc".getBytes(StandardCharsets.UTF_8));
        assertThat(Sha256.hex(md)).isEqualTo(ABC);
    }
}
