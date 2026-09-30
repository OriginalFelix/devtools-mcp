package systems.grebe.devtools.mcp.account;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Passwort-Hash PBKDF2 (RFC 8018) mit HMAC-SHA3-512, zufälligem Salt je Passwort und einstellbarer Iterationszahl.
 *
 * <p>Das JDK bietet {@code HmacSHA3-512}, aber keine {@code SecretKeyFactory} „PBKDF2WithHmacSHA3-512“ – deshalb ist
 * PBKDF2 hier selbst umgesetzt ({@link #pbkdf2}). Ein einfaches {@code SHA3-512(benutzer + passwort)} wäre zu schnell
 * (Brute Force auf GPUs) und der Benutzername ein vorhersagbarer Salt.
 *
 * <p>Format: {@code pbkdf2-sha3-512$<iterationen>$<salt base64>$<hash base64>}. Die Iterationszahl steht im Hash;
 * wird sie angehoben, meldet {@link #upgradeEncoding} ältere Hashes zum Neuberechnen beim nächsten Login.
 */
public final class Sha3Pbkdf2PasswordEncoder implements PasswordEncoder {

    static final String PREFIX = "pbkdf2-sha3-512";
    static final String MAC = "HmacSHA3-512";
    /** OWASP-Empfehlung für PBKDF2-HMAC-SHA512 (2023); SHA3-512 ist je Iteration nicht billiger. */
    public static final int DEFAULT_ITERATIONS = 210_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 64;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final int iterations;

    public Sha3Pbkdf2PasswordEncoder() {
        this(DEFAULT_ITERATIONS);
    }

    public Sha3Pbkdf2PasswordEncoder(int iterations) {
        if (iterations < 1) {
            throw new IllegalArgumentException("Iterationen müssen positiv sein");
        }
        this.iterations = iterations;
    }

    @Override
    public String encode(CharSequence rawPassword) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] hash = pbkdf2(MAC, bytes(rawPassword), salt, iterations, HASH_BYTES);
        Base64.Encoder b64 = Base64.getEncoder().withoutPadding();
        return PREFIX + "$" + iterations + "$" + b64.encodeToString(salt) + "$" + b64.encodeToString(hash);
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        Parsed p = parse(encodedPassword);
        if (p == null || rawPassword == null) {
            return false;
        }
        byte[] actual = pbkdf2(MAC, bytes(rawPassword), p.salt, p.iterations, p.hash.length);
        return MessageDigest.isEqual(actual, p.hash);
    }

    @Override
    public boolean upgradeEncoding(String encodedPassword) {
        Parsed p = parse(encodedPassword);
        return p == null || p.iterations < iterations;
    }

    /**
     * PBKDF2 nach RFC 8018, Abschnitt 5.2: {@code T_i = U_1 ^ … ^ U_c} mit {@code U_1 = PRF(P, S || INT(i))} und
     * {@code U_j = PRF(P, U_{j-1})}; das Ergebnis sind die ersten {@code length} Bytes von {@code T_1 || T_2 || …}.
     *
     * @param mac JCA-Name der PRF, z.B. {@code HmacSHA3-512} (Tests prüfen den Algorithmus mit {@code HmacSHA512}
     *            gegen die {@code PBKDF2WithHmacSHA512} des JDK)
     */
    static byte[] pbkdf2(String mac, byte[] password, byte[] salt, int iterations, int length) {
        try {
            Mac prf = Mac.getInstance(mac);
            // leeres Passwort: SecretKeySpec lehnt leere Schlüssel ab, HMAC mit leerem Schlüssel ist aber definiert
            prf.init(password.length == 0 ? new EmptyKey(mac) : new SecretKeySpec(password, mac));
            int hLen = prf.getMacLength();
            int blocks = (length + hLen - 1) / hLen;
            byte[] out = new byte[blocks * hLen];
            for (int i = 1; i <= blocks; i++) {
                prf.update(salt);
                prf.update(new byte[] {(byte) (i >>> 24), (byte) (i >>> 16), (byte) (i >>> 8), (byte) i});
                byte[] u = prf.doFinal();
                byte[] t = u.clone();
                for (int j = 1; j < iterations; j++) {
                    u = prf.doFinal(u);
                    for (int k = 0; k < t.length; k++) {
                        t[k] ^= u[k];
                    }
                }
                System.arraycopy(t, 0, out, (i - 1) * hLen, hLen);
            }
            return Arrays.copyOf(out, length);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 mit " + mac + " nicht verfügbar", e);
        }
    }

    private static byte[] bytes(CharSequence s) {
        return s.toString().getBytes(StandardCharsets.UTF_8);
    }

    private record Parsed(int iterations, byte[] salt, byte[] hash) {
    }

    private static Parsed parse(String encoded) {
        if (encoded == null) {
            return null;
        }
        String[] parts = encoded.split("\\$");
        if (parts.length != 4 || !PREFIX.equals(parts[0])) {
            return null;
        }
        try {
            int it = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] hash = Base64.getDecoder().decode(parts[3]);
            return it > 0 && salt.length > 0 && hash.length > 0 ? new Parsed(it, salt, hash) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** HMAC-Schlüssel der Länge 0 (für leere Passwörter; {@link SecretKeySpec} erlaubt das nicht). */
    private record EmptyKey(String getAlgorithm) implements javax.crypto.SecretKey {
        @Override
        public String getFormat() {
            return "RAW";
        }

        @Override
        public byte[] getEncoded() {
            return new byte[0];
        }
    }
}
