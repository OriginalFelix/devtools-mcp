package systems.grebe.devtools.mcp.api;

import java.util.List;

/**
 * Eine Rolle mit ihren Rechten (siehe {@link Grants}).
 *
 * @param builtin eingebaut (Administrator): alle Rechte, nicht änderbar oder löschbar
 * @param users   Anzahl der Benutzer mit dieser Rolle
 */
public record RoleInfo(long id, String name, String description, boolean builtin, List<String> permissions,
                       int users) {

    public RoleInfo {
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
    }
}
