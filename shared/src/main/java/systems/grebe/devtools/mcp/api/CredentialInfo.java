package systems.grebe.devtools.mcp.api;

/**
 * Zugangsdaten eines Benutzers für einen Server (Modul „Zugangsdaten“ der Desktop-App, wie beim Git Credential
 * Manager). Im Backend verschlüsselt gespeichert; über die API nur an den Benutzer selbst, entschlüsselt.
 *
 * @param id       ID im Backend; {@code null} für einen neuen Eintrag
 * @param url      Server (http/https), für den sie gelten – auch für Subdomains und Pfade darunter
 * @param username Benutzer, falls der Server einen braucht (z.B. E-Mail zum Jira-Cloud-Token), sonst {@code null}
 * @param secret   Token bzw. Passwort; beim Speichern {@code null}/leer = bisheriges des Eintrags mit {@code id}
 */
public record CredentialInfo(Long id, String name, String url, String username, String secret, String description) {
}
