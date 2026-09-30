package systems.grebe.devtools.mcp.backend.account;

import java.time.Instant;

/**
 * Ein Benutzer (ohne Passwort-Hash – der verlässt das {@link AccountRepository} nur für die Anmeldung).
 *
 * @param username    Anmeldename, klein geschrieben ({@code a-z0-9._-})
 * @param displayName Anzeigename, optional
 * @param email       E-Mail, optional; Eigentümer der Skills dieses Benutzers
 */
public record UserAccount(long id, String username, String displayName, String email, Role role, boolean enabled,
                          Instant createdAt) {

    public boolean admin() {
        return role == Role.ADMIN;
    }

    /** Anzeigename oder, falls leer, der Anmeldename. */
    public String label() {
        return displayName == null || displayName.isBlank() ? username : displayName;
    }
}
