package systems.grebe.devtools.mcp.oauth;

import java.time.Instant;

/**
 * Token-Antwort (RFC 6749 §5.1).
 *
 * @param refreshToken {@code null}, wenn der Server keins ausgibt (z.B. GitHub-OAuth-Apps: Tokens laufen nicht ab)
 * @param expiresAt    {@code null}, wenn das Token nicht abläuft bzw. der Server nichts angibt
 */
public record OAuthTokens(String accessToken, String refreshToken, Instant expiresAt, String scope) {
}
