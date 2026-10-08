package systems.grebe.devtools.mcp.api;

/** Ein Systemrecht für die Anzeige (Query {@code permissionCatalog}). */
public record PermissionInfo(String key, String name, String label, String description) {

    public static PermissionInfo of(Permission p) {
        return new PermissionInfo(p.key(), p.name(), p.label(), p.description());
    }
}
