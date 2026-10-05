package systems.grebe.devtools.mcp.api;

import java.util.List;

/**
 * Ein Benutzer in der Verwaltung (Query {@code users}, nur mit {@link Permission#USERS_MANAGE}).
 *
 * @param createdAt ISO-8601, UTC
 * @param roles     Namen der Rollen
 */
public record UserInfo(long id, String username, String displayName, String email, boolean enabled,
                       boolean passwordChangeRequired, String createdAt, List<String> roles) {

    public UserInfo {
        roles = roles == null ? List.of() : List.copyOf(roles);
    }
}
