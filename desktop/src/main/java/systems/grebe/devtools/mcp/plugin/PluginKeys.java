package systems.grebe.devtools.mcp.plugin;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Schlüssel für Plugin-Signaturen im PEM-Format, wie OpenSSL sie schreibt – RSA oder EC:
 *
 * <pre>{@code
 * openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out plugin-signing.pem   # privat (PKCS#8)
 * openssl pkey -in plugin-signing.pem -pubout -out plugin-signing.pub.pem                  # öffentlich
 * }</pre>
 */
public final class PluginKeys {

    private static final Pattern PEM = Pattern.compile(
            "-----BEGIN ([A-Z ]+)-----([A-Za-z0-9+/=\\s]+)-----END \\1-----");

    private PluginKeys() {
    }

    /**
     * Alle öffentlichen Schlüssel ({@code BEGIN PUBLIC KEY}) eines Textes – auch mehrere hintereinander.
     *
     * @throws IllegalArgumentException wenn kein Schlüssel darin steht oder einer nicht lesbar ist
     */
    public static List<PublicKey> publicKeys(String pem) {
        List<PublicKey> keys = new ArrayList<>();
        for (Block b : parse(pem)) {
            if (!b.type.equals("PUBLIC KEY")) {
                throw new IllegalArgumentException("Erwartet „BEGIN PUBLIC KEY“, gefunden „BEGIN " + b.type
                        + "“ – öffentlichen Schlüssel mit „openssl pkey -in <privat.pem> -pubout“ erzeugen.");
            }
            keys.add(publicKey(b.der));
        }
        return keys;
    }

    /**
     * Privater Schlüssel ({@code BEGIN PRIVATE KEY}, PKCS#8, unverschlüsselt).
     *
     * @throws IllegalArgumentException wenn der Text keinen solchen Schlüssel enthält
     */
    public static PrivateKey privateKey(String pem) {
        Block b = parse(pem).getFirst();
        if (!b.type.equals("PRIVATE KEY")) {
            throw new IllegalArgumentException("Erwartet „BEGIN PRIVATE KEY“ (PKCS#8), gefunden „BEGIN " + b.type
                    + "“ – umwandeln mit „openssl pkcs8 -topk8 -nocrypt -in <alt.pem> -out <neu.pem>“.");
        }
        for (String algorithm : List.of("EC", "RSA")) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(new PKCS8EncodedKeySpec(b.der));
            } catch (GeneralSecurityException e) {
                // nächster Algorithmus
            }
        }
        throw new IllegalArgumentException("Privater Schlüssel ist weder RSA noch EC.");
    }

    /** Die einzelnen PEM-Blöcke eines Textes (Text dazwischen entfällt); leer, wenn keiner darin steht. */
    public static List<String> blocks(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = PEM.matcher(text == null ? "" : text);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    private static PublicKey publicKey(byte[] der) {
        for (String algorithm : List.of("EC", "RSA")) {
            try {
                return KeyFactory.getInstance(algorithm).generatePublic(new X509EncodedKeySpec(der));
            } catch (GeneralSecurityException e) {
                // nächster Algorithmus
            }
        }
        throw new IllegalArgumentException("Öffentlicher Schlüssel ist weder RSA noch EC.");
    }

    private record Block(String type, byte[] der) {
    }

    private static List<Block> parse(String pem) {
        List<Block> blocks = new ArrayList<>();
        Matcher m = PEM.matcher(pem == null ? "" : pem);
        while (m.find()) {
            try {
                blocks.add(new Block(m.group(1), Base64.getMimeDecoder().decode(m.group(2))));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("PEM-Block „" + m.group(1) + "“ ist kein gültiges Base64.", e);
            }
        }
        if (blocks.isEmpty()) {
            throw new IllegalArgumentException("Kein Schlüssel im PEM-Format („-----BEGIN …-----“) gefunden.");
        }
        return blocks;
    }
}
