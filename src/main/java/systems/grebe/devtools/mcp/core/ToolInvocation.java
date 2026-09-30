package systems.grebe.devtools.mcp.core;

import java.time.Duration;
import java.time.Instant;

/**
 * Ein protokollierter Tool-Aufruf.
 *
 * @param userId Benutzer-ID aus dem {@link ToolScope}, {@code null} im Einzelplatz-Betrieb
 * @param user   Anmeldename bzw. {@code lokal}
 */
public record ToolInvocation(
        long id,
        Instant timestamp,
        String moduleId,
        String toolName,
        String arguments,
        String result,
        Duration duration,
        boolean success,
        String userId,
        String user) {
}
