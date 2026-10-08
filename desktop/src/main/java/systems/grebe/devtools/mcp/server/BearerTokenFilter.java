package systems.grebe.devtools.mcp.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import systems.grebe.devtools.mcp.config.ServerSettings;
import systems.grebe.devtools.mcp.config.SettingsStore;

/**
 * Schützt den MCP-Endpunkt optional per Bearer-Token (Einstellungen → Zugriffstoken). Die GraphQL-API des
 * eingebetteten Backends prüft ihre eigenen Tokens und ist hier ausgenommen.
 * Das Token wird bei jeder Anfrage aus dem {@link SettingsStore} gelesen – Änderungen wirken sofort.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BearerTokenFilter extends OncePerRequestFilter {

    private final SettingsStore store;
    private final String endpoint;

    public BearerTokenFilter(SettingsStore store,
                             @Value("${spring.ai.mcp.server.streamable-http.mcp-endpoint:" + ServerSettings.MCP_PATH
                                     + "}") String endpoint) {
        this.store = store;
        this.endpoint = endpoint;
    }

    /** Geschützt ist der konfigurierte Endpunkt samt allem darunter ({@code /mcp}, {@code /mcp/…}), nicht {@code /mcpfoo}. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getContextPath() + endpoint;
        String uri = request.getRequestURI();
        return !(uri.equals(path) || uri.startsWith(path + "/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = store.server().authToken();
        if (token.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)
                && constantTimeEquals(header.substring(7).trim(), token)) {
            chain.doFilter(request, response);
            return;
        }
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"devtools-mcp\"");
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
