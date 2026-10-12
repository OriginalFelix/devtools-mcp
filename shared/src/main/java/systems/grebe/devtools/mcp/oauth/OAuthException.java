package systems.grebe.devtools.mcp.oauth;

/**
 * Fehlerantwort eines Autorisierungsservers (RFC 6749 §5.2, RFC 8628 §3.5) – {@link #code()} ist der Wert von
 * {@code error}, z.B. {@code authorization_pending} oder {@code invalid_grant}.
 */
public class OAuthException extends RuntimeException {

    private final String code;

    public OAuthException(String code, String message) {
        super(message == null || message.isBlank() ? code : message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
