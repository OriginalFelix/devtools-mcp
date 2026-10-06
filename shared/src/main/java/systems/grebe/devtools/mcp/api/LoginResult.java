package systems.grebe.devtools.mcp.api;

/**
 * Ergebnis einer Anmeldung mit Benutzername und Passwort (Mutation {@code login}).
 *
 * @param token                  Sitzungs-Token (JWT) für alle weiteren Aufrufe; gilt bis zur Abmeldung oder zum Ablauf
 * @param expiresAt              Ablauf (ISO-8601, UTC)
 * @param passwordChangeRequired erst das Passwort ändern ({@code changePassword}) – sonst lehnt das Backend alles ab
 */
public record LoginResult(String token, String expiresAt, boolean passwordChangeRequired) {
}
