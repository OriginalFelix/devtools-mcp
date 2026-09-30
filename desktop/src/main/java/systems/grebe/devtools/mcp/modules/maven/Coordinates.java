package systems.grebe.devtools.mcp.modules.maven;

/** Maven-Koordinaten {@code groupId:artifactId[:version]}. */
record Coordinates(String groupId, String artifactId, String version) {

    /** Liest {@code g:a} oder {@code g:a:v} (auch {@code g:a:packaging:v}, der Packaging-Teil wird ignoriert). */
    static Coordinates parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Koordinaten fehlen – Format groupId:artifactId, z.B. org.slf4j:slf4j-api");
        }
        String[] p = text.trim().split(":");
        if (p.length < 2 || p.length > 5 || p[0].isBlank() || p[1].isBlank()) {
            throw new IllegalArgumentException("Ungültige Koordinaten '" + text
                    + "' – Format groupId:artifactId[:version], z.B. org.slf4j:slf4j-api:2.0.16");
        }
        String version = p.length >= 3 ? p[p.length - 1].trim() : null;
        return new Coordinates(p[0].trim(), p[1].trim(), version == null || version.isEmpty() ? null : version);
    }

    String ga() {
        return groupId + ":" + artifactId;
    }

    Coordinates withVersion(String v) {
        return new Coordinates(groupId, artifactId, v);
    }
}
