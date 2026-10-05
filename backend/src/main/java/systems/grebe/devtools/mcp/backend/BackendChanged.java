package systems.grebe.devtools.mcp.backend;

import java.util.Set;

/**
 * Etwas im Backend hat sich geändert, das verbundene Desktop-Apps sofort übernehmen sollen (GraphQL-Subscriptions,
 * siehe {@link ChangeBus}).
 *
 * @param userIds betroffene Benutzer; {@code null} = alle
 */
public record BackendChanged(Topic topic, Set<Long> userIds) {

    /** Was sich geändert hat. */
    public enum Topic {
        /** Einstellungs-Vorgaben, Sperren, Profile oder aktives Profil. */
        SETTINGS,
        /** Projekte oder Freigaben. */
        PROJECTS,
        /** Skills. */
        SKILLS,
        /** Groovy-Skripte. */
        SCRIPTS
    }

    public static BackendChanged all(Topic topic) {
        return new BackendChanged(topic, null);
    }

    public static BackendChanged of(Topic topic, long... userIds) {
        return new BackendChanged(topic, java.util.Arrays.stream(userIds).boxed()
                .collect(java.util.stream.Collectors.toUnmodifiableSet()));
    }

    public boolean concerns(long userId) {
        return userIds == null || userIds.contains(userId);
    }
}
