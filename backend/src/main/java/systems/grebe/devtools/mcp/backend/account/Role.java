package systems.grebe.devtools.mcp.backend.account;

import java.time.Instant;
import java.util.Set;

import systems.grebe.devtools.mcp.api.Grants;

/**
 * Eine Rolle mit ihren Rechten (siehe {@link Grants}). Benutzer haben beliebig viele Rollen; ihre Rechte addieren
 * sich.
 *
 * @param builtin eingebaut ({@value #ADMINISTRATOR}): alle Rechte, weder änderbar noch löschbar
 * @param users   Anzahl der Benutzer mit dieser Rolle
 */
public record Role(long id, String name, String description, boolean builtin, Set<String> permissions,
                   Instant createdAt, int users) {

    /** Name der eingebauten Rolle mit allen Rechten. */
    public static final String ADMINISTRATOR = "Administrator";
    /** Name der vorbelegten Rolle für neue Benutzer (änderbar, löschbar). */
    public static final String USER = "Benutzer";

    public Role {
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }

    public Grants grants() {
        return new Grants(permissions);
    }
}
