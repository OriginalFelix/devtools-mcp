package systems.grebe.devtools.mcp.server;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.account.TokenService;
import systems.grebe.devtools.mcp.config.SettingsStore;

/**
 * Entscheidet für jede Anfrage an {@code /mcp}, wessen MCP-Server sie bedient:
 *
 * <ul>
 *   <li>{@code Authorization: Bearer <JWT>} eines Benutzers → dessen Runtime.</li>
 *   <li>Bearer mit dem Zugriffstoken aus den Einstellungen (Einzelplatz, vor den Benutzerkonten) → lokale Runtime.</li>
 *   <li>Ohne Header → lokale Runtime, aber nur wenn der Server ausschließlich auf einer Loopback-Adresse lauscht, kein
 *       Einzelplatz-Token gesetzt ist und {@code devtools.mcp.allow-anonymous-local} nicht abgeschaltet ist.</li>
 * </ul>
 *
 * <p>Achtung: Ein Reverse-Proxy auf demselben Rechner, der an {@code 127.0.0.1} weiterleitet, machte anonyme Zugriffe
 * von außen möglich – dann {@code devtools.mcp.allow-anonymous-local=false} setzen.
 */
@Component
public class McpAccess {

    /** Ergebnis der Prüfung: genau eins von lokal, Benutzer oder abgelehnt. */
    public record Result(boolean local, TokenService.TokenUser user) {

        static final Result DENIED = new Result(false, null);
        static final Result LOCAL = new Result(true, null);

        public boolean denied() {
            return !local && user == null;
        }
    }

    private final TokenService tokens;
    private final SettingsStore store;
    private final boolean anonymousLocal;

    public McpAccess(TokenService tokens, SettingsStore store,
                     @Value("${server.address:127.0.0.1}") String bindAddress,
                     @Value("${devtools.mcp.allow-anonymous-local:true}") boolean allowAnonymousLocal) {
        this.tokens = tokens;
        this.store = store;
        this.anonymousLocal = allowAnonymousLocal && loopback(bindAddress);
    }

    public Result check(HttpServletRequest request) {
        String legacy = store.server().authToken();
        Optional<String> bearer = bearer(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (bearer.isEmpty()) {
            return legacy.isEmpty() && anonymousLocal ? Result.LOCAL : Result.DENIED;
        }
        String token = bearer.get();
        if (!legacy.isEmpty() && constantTimeEquals(token, legacy)) {
            return Result.LOCAL;
        }
        return tokens.verify(token).map(u -> new Result(false, u)).orElse(Result.DENIED);
    }

    /** Ob Anfragen ohne Token die lokale Runtime bekommen können (für Anzeige und Tests). */
    public boolean anonymousLocal() {
        return anonymousLocal;
    }

    private static Optional<String> bearer(String header) {
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return Optional.empty();
        }
        String t = header.substring(7).trim();
        return t.isEmpty() ? Optional.empty() : Optional.of(t);
    }

    static boolean loopback(String bindAddress) {
        if (bindAddress == null || bindAddress.isBlank()) {
            return false; // Spring lauscht dann auf allen Adressen
        }
        try {
            return InetAddress.getByName(bindAddress.strip()).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
