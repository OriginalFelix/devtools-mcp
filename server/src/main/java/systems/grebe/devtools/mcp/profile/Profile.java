package systems.grebe.devtools.mcp.profile;

import java.time.Instant;

/** Ein Profil eines Benutzers (z.B. „Work“, „Home“) mit eigenen Einstellungen. */
public record Profile(long id, long userId, String name, String description, Instant createdAt) {
}
