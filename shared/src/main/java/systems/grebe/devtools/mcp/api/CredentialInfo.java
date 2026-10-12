package systems.grebe.devtools.mcp.api;

import java.time.Duration;

/**
 * Zugangsdaten eines Benutzers für einen Server (Modul „Zugangsdaten“ der Desktop-App, wie beim Git Credential
 * Manager). Im Backend verschlüsselt gespeichert; über die API nur an den Benutzer selbst, entschlüsselt.
 *
 * <p>Zwei Arten ({@code auth}): {@link #TOKEN} – Token bzw. Passwort von Hand eingegeben – oder {@link #OAUTH} –
 * Anmeldung im Browser nach OAuth 2.0 / OpenID Connect; {@code secret} ist dann das Access-Token, das Backend erneuert
 * es mit dem Refresh-Token.
 *
 * @param id           ID im Backend; {@code null} für einen neuen Eintrag
 * @param url          Server (http/https), für den sie gelten – auch für Subdomains und Pfade darunter
 * @param username     Benutzer, falls der Server einen braucht (z.B. E-Mail zum Jira-Cloud-Token), sonst {@code null}
 * @param secret       Token, Passwort bzw. Access-Token; beim Speichern {@code null}/leer oder unverändert = bisheriges
 *                     des Eintrags mit {@code id}
 * @param auth         {@link #TOKEN} oder {@link #OAUTH}; beim Speichern {@code null} = bisherige bzw. {@link #TOKEN}
 * @param issuer       OAuth: Aussteller für die Discovery (RFC 8414 / OIDC); leer = Herkunft der URL
 * @param clientId     OAuth: registrierter Client
 * @param clientSecret OAuth: nur für vertrauliche Clients; beim Speichern leer = bisheriges
 * @param scopes       OAuth: angeforderte Scopes, durch Leerzeichen getrennt
 * @param refreshToken OAuth: nur beim Speichern nach der Anmeldung; geht nie aus dem Backend heraus
 * @param expiresAt    OAuth: Ablauf des Access-Tokens (ISO-8601), {@code null} = läuft nicht ab
 * @param options      OAuth: Angaben von Hand ({@code systems.grebe.devtools.mcp.oauth.OAuthOptions}) – Endpunkte für
 *                     Server ohne Discovery, feste Rückleitung, weitere Parameter
 */
public record CredentialInfo(Long id, String name, String url, String username, String secret, String description,
                             String auth, String issuer, String clientId, String clientSecret, String scopes,
                             String refreshToken, String expiresAt, String options) {

    public static final String TOKEN = "TOKEN";
    public static final String OAUTH = "OAUTH";
    /** So lange vor dem Ablauf wird ein Access-Token erneuert. */
    public static final Duration REFRESH_BEFORE = Duration.ofMinutes(5);

    /** Zugangsdaten mit Token bzw. Passwort (API-Version 1). */
    public CredentialInfo(Long id, String name, String url, String username, String secret, String description) {
        this(id, name, url, username, secret, description, null, null, null, null, null, null, null, null);
    }

    /** Zugangsdaten ohne OAuth-Optionen (API-Version 2). */
    public CredentialInfo(Long id, String name, String url, String username, String secret, String description,
                          String auth, String issuer, String clientId, String clientSecret, String scopes,
                          String refreshToken, String expiresAt) {
        this(id, name, url, username, secret, description, auth, issuer, clientId, clientSecret, scopes, refreshToken,
                expiresAt, null);
    }

    public boolean oauth() {
        return OAUTH.equalsIgnoreCase(auth);
    }

    /** Dieselben Zugangsdaten mit neuem Access-Token (nach Anmeldung oder Erneuern). */
    public CredentialInfo withTokens(String accessToken, String refresh, String expires) {
        return new CredentialInfo(id, name, url, username, accessToken, description, auth, issuer, clientId,
                clientSecret, scopes, refresh, expires, options);
    }
}
