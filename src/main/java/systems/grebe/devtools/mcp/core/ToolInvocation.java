package systems.grebe.devtools.mcp.core;

import java.time.Duration;
import java.time.Instant;

/** Ein protokollierter Tool-Aufruf. */
public record ToolInvocation(
        long id,
        Instant timestamp,
        String moduleId,
        String toolName,
        String arguments,
        String result,
        Duration duration,
        boolean success) {
}
