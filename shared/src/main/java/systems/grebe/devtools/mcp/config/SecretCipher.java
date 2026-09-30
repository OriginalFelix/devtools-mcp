package systems.grebe.devtools.mcp.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Verschlüsselt Geheimnisse (Tokens) mit AES-GCM, damit sie nicht im Klartext in der Settings-Datei stehen.
 * Der Schlüssel liegt in einer separaten Datei im Benutzerverzeichnis. Das schützt vor versehentlichem
 * Teilen der Settings-Datei, nicht vor einem Angreifer mit Zugriff auf das Benutzerkonto.
 */
public final class SecretCipher {

    private static final String PREFIX = "enc:v1:";
    private static final int IV_LEN = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path keyFile;
    private SecretKey key;

    public SecretCipher(Path keyFile) {
        this.keyFile = keyFile;
    }

    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) {
            return plain;
        }
        try {
            byte[] iv = new byte[IV_LEN];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            byte[] enc = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[IV_LEN + enc.length];
            System.arraycopy(iv, 0, out, 0, IV_LEN);
            System.arraycopy(enc, 0, out, IV_LEN, enc.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Verschlüsselung fehlgeschlagen", e);
        }
    }

    /** Entschlüsselt; nicht verschlüsselte Werte werden unverändert zurückgegeben. */
    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            return stored;
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, all, 0, IV_LEN));
            return new String(c.doFinal(all, IV_LEN, all.length - IV_LEN), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // Schlüssel verloren/geändert: Geheimnis muss neu eingegeben werden
            return "";
        }
    }

    private synchronized SecretKey key() throws GeneralSecurityException {
        if (key != null) {
            return key;
        }
        try {
            if (Files.isRegularFile(keyFile)) {
                key = new SecretKeySpec(Base64.getDecoder().decode(Files.readString(keyFile).trim()), "AES");
            } else {
                KeyGenerator gen = KeyGenerator.getInstance("AES");
                gen.init(256);
                key = gen.generateKey();
                Files.createDirectories(keyFile.getParent());
                Files.writeString(keyFile, Base64.getEncoder().encodeToString(key.getEncoded()));
                restrictToOwner(keyFile);
            }
            return key;
        } catch (IOException e) {
            throw new UncheckedIOException("Schlüsseldatei nicht lesbar: " + keyFile, e);
        }
    }

    /** Datei nur für den Eigentümer les- und schreibbar (auch für andere Schlüsseldateien, z.B. JWT). */
    public static void restrictToOwner(Path file) {
        var f = file.toFile();
        // funktioniert plattformübergreifend (unter Windows best effort)
        f.setReadable(false, false);
        f.setReadable(true, true);
        f.setWritable(false, false);
        f.setWritable(true, true);
    }
}
