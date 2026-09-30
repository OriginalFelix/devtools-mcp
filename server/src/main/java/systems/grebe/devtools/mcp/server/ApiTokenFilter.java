package systems.grebe.devtools.mcp.server;

import java.io.IOException;
import java.util.Optional;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;
import systems.grebe.devtools.mcp.account.TokenService;

/**
 * Anmeldung an der REST-API per {@code Authorization: Bearer <JWT>} (Desktop-Token aus „Mein Konto“). Ohne gültiges
 * Token 401; sonst liegt der Benutzer als Request-Attribut {@link #USER} für {@link DesktopApi} bereit.
 *
 * <p>Bewusst keine Bean: Spring Boot registrierte einen Filter-Bean sonst für alle URLs, nicht nur für
 * {@code /api/**} (siehe {@link SecurityConfig}).
 */
class ApiTokenFilter extends OncePerRequestFilter {

    static final String USER = "systems.grebe.devtools.mcp.apiUser";

    private final TokenService tokens;

    ApiTokenFilter(TokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<TokenService.TokenUser> user = bearer(request.getHeader(HttpHeaders.AUTHORIZATION))
                .flatMap(tokens::verify);
        if (user.isEmpty()) {
            // bewusst kein sendError: der Error-Dispatch nach /error landete in der Web-Kette und dort beim Login (302)
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"devtools-server\"");
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Desktop-Token fehlt oder ist ungültig\"}");
            return;
        }
        request.setAttribute(USER, user.get().user());
        chain.doFilter(request, response);
    }

    private static Optional<String> bearer(String header) {
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return Optional.empty();
        }
        String t = header.substring(7).trim();
        return t.isEmpty() ? Optional.empty() : Optional.of(t);
    }
}
