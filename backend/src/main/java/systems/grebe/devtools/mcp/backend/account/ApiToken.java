package systems.grebe.devtools.mcp.backend.account;

import java.time.Instant;

/**
 * Verwaltungsdaten eines Zugriffstokens. Das JWT selbst wird nie gespeichert, nur seine ID ({@code jti}).
 *
 * @param expiresAt  Ablauf, {@code null} = unbegrenzt
 * @param lastUsedAt letzte Verwendung (auf die Minute genau), {@code null} = nie
 * @param revokedAt  Widerruf bzw. Abmeldung, {@code null} = gültig
 */
public record ApiToken(String id, long userId, String name, Instant createdAt, Instant expiresAt, Instant lastUsedAt,
                       Instant revokedAt, Kind kind) {

    /** Art des Tokens. */
    public enum Kind {
        /** Persönliches Token aus „Mein Konto“ (headless, Automatisierung). */
        TOKEN,
        /** Anmeldung einer Desktop-App mit Benutzername und Passwort; endet mit der Abmeldung. */
        SESSION
    }

    public ApiToken {
        kind = kind == null ? Kind.TOKEN : kind;
    }

    public boolean activeAt(Instant now) {
        return revokedAt == null && (expiresAt == null || now.isBefore(expiresAt));
    }
}
