package systems.grebe.devtools.mcp.server;

import java.io.IOException;
import java.net.InetAddress;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import systems.grebe.devtools.mcp.backend.blobs.BlobController;
import systems.grebe.devtools.mcp.modules.a2a.A2aController;

/**
 * Von anderen Rechnern nur das Backend: Mit Advertise-Endpunkt lauscht die App auf allen Adressen, damit andere
 * Desktop-Apps das eingebettete Backend nutzen können ({@code /graphql}, {@code /blobs}, beide mit eigener
 * Anmeldung, und dasselbe je API-Version unter {@code /api/…}) sowie der A2A-Server ({@link A2aController}, mit
 * eigenem Token). Der MCP-Endpunkt, der Channel und alles andere bleiben
 * diesem Rechner vorbehalten.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LocalOnlyFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (local(request.getRemoteAddr()) || backend(request)) {
            chain.doFilter(request, response);
            return;
        }
        response.sendError(HttpServletResponse.SC_FORBIDDEN);
    }

    static boolean backend(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.equals("/graphql") || path.startsWith("/graphql/") || path.equals(BlobController.PATH)
                || path.startsWith(BlobController.PATH + "/") || path.startsWith("/api/")
                || path.equals(A2aController.CARD) || path.equals(A2aController.RPC); // A2A prüft selbst das Token
    }

    public static boolean local(String address) {
        if (address == null) {
            return false;
        }
        try {
            return InetAddress.ofLiteral(address.replaceAll("^\\[|]$", "")).isLoopbackAddress();
        } catch (IllegalArgumentException e) {
            return false; // kein IP-Literal – nie auflösen
        }
    }
}
