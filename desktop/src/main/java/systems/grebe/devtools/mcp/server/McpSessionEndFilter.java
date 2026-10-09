package systems.grebe.devtools.mcp.server;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import systems.grebe.devtools.mcp.core.McpSessionClosed;

/**
 * Meldet beendete MCP-Sessions als {@link McpSessionClosed}: Der Streamable-HTTP-Transport von Spring AI entfernt eine
 * Session bei {@code DELETE} auf den MCP-Endpunkt, sagt es aber niemandem. Gemeldet wird erst, wenn der Transport das
 * Beenden angenommen hat (2xx) – abgewiesene Anfragen (fehlendes Token, unbekannte Session) ändern nichts.
 */
@Component
public class McpSessionEndFilter extends OncePerRequestFilter {

    /** Kopfzeile mit der Session-ID (Streamable HTTP). */
    static final String SESSION_HEADER = "Mcp-Session-Id";

    private final ApplicationEventPublisher events;
    private final String endpoint;

    public McpSessionEndFilter(ApplicationEventPublisher events,
                               @Value("${spring.ai.mcp.server.streamable-http.mcp-endpoint:/mcp}") String endpoint) {
        this.events = events;
        this.endpoint = endpoint;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !"DELETE".equals(request.getMethod()) || !path.equals(endpoint);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(request, response);
        String session = request.getHeader(SESSION_HEADER);
        if (session != null && !session.isBlank() && response.getStatus() >= 200 && response.getStatus() < 300) {
            events.publishEvent(new McpSessionClosed(session.strip()));
        }
    }
}
