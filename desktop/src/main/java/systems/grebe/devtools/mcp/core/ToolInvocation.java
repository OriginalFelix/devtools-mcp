package systems.grebe.devtools.mcp.core;

import java.time.Duration;
import java.time.Instant;

/**
 * Ein protokollierter Tool-Aufruf.
 *
 * @param userId      Benutzer-ID aus dem {@link ToolScope}, {@code null} im Einzelplatz-Betrieb
 * @param user        Anmeldename bzw. {@code lokal}
 * @param rawChars    Länge des Ergebnisses, wie das Tool es geliefert hat
 * @param resultChars Länge des Ergebnisses, das an das LLM ging (nach Kürzen und Hinweisen; {@link #result} selbst
 *                    ist fürs Protokoll gekürzt)
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
        String user,
        int rawChars,
        int resultChars) {

    /** Geschätzte Tokens des Ergebnisses an das LLM. */
    public int resultTokens() {
        return TokenStats.estimate(resultChars);
    }
}
