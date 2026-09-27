package systems.grebe.devtools.mcp.server;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import systems.grebe.devtools.mcp.config.SettingsStore;

/**
 * Schützt den MCP-Endpunkt optional per Bearer-Token (Einstellungen → Zugriffstoken).
 * Das Token wird bei jeder Anfrage aus dem {@link SettingsStore} gelesen – Änderungen wirken sofort.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BearerTokenFilter implements WebFilter {

    private final SettingsStore store;

    public BearerTokenFilter(SettingsStore store) {
        this.store = store;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String token = store.server().authToken();
        if (token.isEmpty()) {
            return chain.filter(exchange);
        }
        String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)
                && constantTimeEquals(header.substring(7).trim(), token)) {
            return chain.filter(exchange);
        }
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"devtools-mcp\"");
        return response.setComplete();
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
