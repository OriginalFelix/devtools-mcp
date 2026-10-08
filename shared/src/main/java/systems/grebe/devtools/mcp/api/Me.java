package systems.grebe.devtools.mcp.api;

import java.util.List;

/**
 * Der angemeldete Benutzer mit seinen Profilen, Rollen und Rechten (Query {@code me}).
 *
 * @param email                  Konto-E-Mail; Eigentümer der Skills, {@code null} = keine hinterlegt
 * @param admin                  hat alle Rechte ({@value Grants#ALL}, Rolle Administrator)
 * @param roles                  Namen der Rollen
 * @param permissions            wirksame Rechte aller Rollen (siehe {@link Grants})
 * @param passwordChangeRequired muss sein Passwort ändern, bevor er weiterarbeiten kann
 */
public record Me(long id, String username, String displayName, String email, boolean admin,
                 List<ProfileInfo> profiles, long activeProfileId, List<String> roles, List<String> permissions,
                 boolean passwordChangeRequired) {

    public Me {
        profiles = profiles == null ? List.of() : List.copyOf(profiles);
        roles = roles == null ? List.of() : List.copyOf(roles);
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
    }

    public Grants grants() {
        return Grants.of(permissions);
    }

    /** Anzeigename oder, falls leer, der Anmeldename. */
    public String label() {
        return displayName == null || displayName.isBlank() ? username : displayName;
    }

    /** Ein Profil des Benutzers. */
    public record ProfileInfo(long id, String name, String description) {
    }
}
