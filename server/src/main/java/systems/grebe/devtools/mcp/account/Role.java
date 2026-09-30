package systems.grebe.devtools.mcp.account;

/** Rolle eines Benutzers. {@code ADMIN} verwaltet Benutzer und globale Einstellungen. */
public enum Role {
    ADMIN,
    USER;

    /** Spring-Security-Authority, z.B. {@code ROLE_ADMIN}. */
    public String authority() {
        return "ROLE_" + name();
    }
}
