package systems.grebe.devtools.mcp.account;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Sha3Pbkdf2PasswordEncoderTest {

    /** Wenige Iterationen, damit die Tests schnell bleiben – der Algorithmus ist derselbe. */
    private final Sha3Pbkdf2PasswordEncoder encoder = new Sha3Pbkdf2PasswordEncoder(1_000);

    @Test
    void roundTrip() {
        String hash = encoder.encode("geheim");
        assertThat(hash).startsWith("pbkdf2-sha3-512$1000$");
        assertThat(encoder.matches("geheim", hash)).isTrue();
        assertThat(encoder.matches("Geheim", hash)).isFalse();
        assertThat(encoder.matches("", hash)).isFalse();
    }

    @Test
    void saltMakesHashesDiffer() {
        assertThat(encoder.encode("geheim")).isNotEqualTo(encoder.encode("geheim"));
    }

    @Test
    void emptyPasswordIsHashable() {
        String hash = encoder.encode("");
        assertThat(encoder.matches("", hash)).isTrue();
        assertThat(encoder.matches("x", hash)).isFalse();
    }

    @Test
    void rejectsForeignOrBrokenHashes() {
        assertThat(encoder.matches("geheim", null)).isFalse();
        assertThat(encoder.matches("geheim", "{bcrypt}$2a$10$abc")).isFalse();
        assertThat(encoder.matches("geheim", "pbkdf2-sha3-512$x$y$z")).isFalse();
        assertThat(encoder.matches("geheim", "pbkdf2-sha3-512$0$AAAA$AAAA")).isFalse();
    }

    @Test
    void upgradeWhenIterationsRaised() {
        String weak = encoder.encode("geheim");
        Sha3Pbkdf2PasswordEncoder stronger = new Sha3Pbkdf2PasswordEncoder(2_000);
        assertThat(stronger.upgradeEncoding(weak)).isTrue();
        assertThat(stronger.matches("geheim", weak)).isTrue();
        assertThat(encoder.upgradeEncoding(weak)).isFalse();
    }

    /** Die eigene PBKDF2-Umsetzung liefert mit HMAC-SHA512 exakt das Ergebnis der JDK-Implementierung. */
    @Test
    void pbkdf2MatchesJdkForSha512() throws Exception {
        byte[] salt = "salz-1234567890".getBytes(StandardCharsets.UTF_8);
        for (int length : new int[] {32, 64, 100}) {
            byte[] own = Sha3Pbkdf2PasswordEncoder.pbkdf2("HmacSHA512",
                    "passwort".getBytes(StandardCharsets.UTF_8), salt, 1_234, length);
            byte[] jdk = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
                    .generateSecret(new PBEKeySpec("passwort".toCharArray(), salt, 1_234, length * 8)).getEncoded();
            assertThat(HexFormat.of().formatHex(own)).isEqualTo(HexFormat.of().formatHex(jdk));
        }
    }
}
