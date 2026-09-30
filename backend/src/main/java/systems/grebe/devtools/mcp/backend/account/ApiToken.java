package systems.grebe.devtools.mcp.backend.account;

import java.time.Instant;

/**
 * Verwaltungsdaten eines MCP-Zugriffstokens. Das JWT selbst wird nie gespeichert, nur seine ID ({@code jti}).
 *
 * @param expiresAt  Ablauf, {@code null} = unbegrenzt
 * @param lastUsedAt letzte Verwendung (auf die Minute genau), {@code null} = nie
 * @param revokedAt  Widerruf, {@code null} = gültig
 */
public record ApiToken(String id, long userId, String name, Instant createdAt, Instant expiresAt, Instant lastUsedAt,
                       Instant revokedAt) {

    public boolean activeAt(Instant now) {
        return revokedAt == null && (expiresAt == null || now.isBefore(expiresAt));
    }
}
