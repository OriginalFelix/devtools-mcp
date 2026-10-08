package systems.grebe.devtools.mcp.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 als Hex-Text (Inhaltsadressen, Fingerabdrücke, Prüfsummen). */
public final class Sha256 {

    private Sha256() {
    }

    /** Neue Berechnung; SHA-256 gibt es auf jeder Java-Plattform, daher keine geprüfte Ausnahme. */
    public static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String hex(byte[] data) {
        return HexFormat.of().formatHex(newDigest().digest(data));
    }

    public static String hex(String text) {
        return hex(text.getBytes(StandardCharsets.UTF_8));
    }

    /** Hex-Text des bisherigen Stands der Berechnung (schließt sie ab). */
    public static String hex(MessageDigest digest) {
        return HexFormat.of().formatHex(digest.digest());
    }
}
