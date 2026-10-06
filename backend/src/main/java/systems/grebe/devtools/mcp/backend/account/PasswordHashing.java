package systems.grebe.devtools.mcp.backend.account;

/**
 * Passwort-Hash wie in der Datenbank ({@link Sha3Pbkdf2PasswordEncoder}) für Aufrufer ohne Spring Security, z.B. die
 * Desktop-App: Sie merkt sich damit die letzte Anmeldung am Team-Server für den Start ohne erreichbaren Server.
 */
public final class PasswordHashing {

    private static final Sha3Pbkdf2PasswordEncoder ENCODER = new Sha3Pbkdf2PasswordEncoder();

    private PasswordHashing() {
    }

    public static String hash(CharSequence password) {
        return ENCODER.encode(password);
    }

    public static boolean matches(CharSequence password, String hash) {
        return hash != null && ENCODER.matches(password, hash);
    }
}
