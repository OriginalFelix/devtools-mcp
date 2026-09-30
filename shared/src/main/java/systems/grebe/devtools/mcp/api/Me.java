package systems.grebe.devtools.mcp.api;

import java.util.List;

/**
 * Der angemeldete Benutzer mit seinen Profilen ({@code GET /api/me}).
 *
 * @param email  Konto-E-Mail; Eigentümer der Skills, {@code null} = keine hinterlegt
 * @param admin  Rolle Administrator (darf u.a. globale Skill-Vorlagen veröffentlichen)
 */
public record Me(long id, String username, String displayName, String email, boolean admin,
                 List<ProfileInfo> profiles, long activeProfileId) {

    public Me {
        profiles = profiles == null ? List.of() : List.copyOf(profiles);
    }

    /** Ein Profil des Benutzers. */
    public record ProfileInfo(long id, String name, String description) {
    }
}
