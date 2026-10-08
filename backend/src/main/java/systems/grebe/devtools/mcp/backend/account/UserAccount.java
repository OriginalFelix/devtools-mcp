package systems.grebe.devtools.mcp.backend.account;

import java.time.Instant;
import java.util.List;

import systems.grebe.devtools.mcp.api.Grants;
import systems.grebe.devtools.mcp.api.Permission;

/**
 * Ein Benutzer (ohne Passwort-Hash – der verlässt das {@link AccountRepository} nur für die Anmeldung).
 *
 * @param username               Anmeldename, klein geschrieben ({@code a-z0-9._-})
 * @param displayName            Anzeigename, optional
 * @param email                  E-Mail, optional; Eigentümer der Skills dieses Benutzers
 * @param passwordChangeRequired muss sein Passwort ändern, bevor er weiterarbeiten kann
 * @param lastLoginAt            letzte Anmeldung (Web-UI oder Desktop-App), {@code null} = nie
 * @param roles                  Namen seiner Rollen
 * @param grants                 Rechte aller Rollen zusammen
 */
public record UserAccount(long id, String username, String displayName, String email, boolean enabled,
                          boolean passwordChangeRequired, Instant createdAt, Instant lastLoginAt, List<String> roles,
                          Grants grants) {

    public UserAccount {
        roles = roles == null ? List.of() : List.copyOf(roles);
        grants = grants == null ? Grants.NONE : grants;
    }

    /** Hat alle Rechte (Rolle Administrator). */
    public boolean admin() {
        return grants.all();
    }

    public boolean has(Permission p) {
        return grants.has(p);
    }

    /** Anzeigename oder, falls leer, der Anmeldename. */
    public String label() {
        return displayName == null || displayName.isBlank() ? username : displayName;
    }
}
