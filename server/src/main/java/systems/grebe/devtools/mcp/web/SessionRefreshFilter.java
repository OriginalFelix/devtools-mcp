package systems.grebe.devtools.mcp.web;

import java.io.IOException;
import java.util.Optional;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;
import systems.grebe.devtools.mcp.backend.account.AccountService;
import systems.grebe.devtools.mcp.backend.account.UserAccount;
import systems.grebe.devtools.mcp.web.WebLogin.AccountPrincipal;

/**
 * Hält die Anmeldung der Web-UI auf dem Stand der Datenbank: bei jeder Anfrage werden Rechte und Status des
 * Benutzers neu gelesen. Geänderte Rollen gelten so sofort (nicht erst nach erneuter Anmeldung); gesperrte oder
 * gelöschte Benutzer verlieren ihre Sitzung.
 */
public class SessionRefreshFilter extends OncePerRequestFilter {

    private final AccountService accounts;
    private final SecurityContextRepository contexts = new HttpSessionSecurityContextRepository();

    public SessionRefreshFilter(AccountService accounts) {
        this.accounts = accounts;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AccountPrincipal current) {
            Optional<UserAccount> user = accounts.user(current.id());
            if (user.isEmpty() || !user.get().enabled()) {
                SecurityContextHolder.clearContext();
                HttpSession session = request.getSession(false);
                if (session != null) {
                    session.invalidate();
                }
            } else {
                AccountPrincipal fresh = WebLogin.principal(user.get(), "", false);
                if (!fresh.sameAs(current)) {
                    SecurityContext context = SecurityContextHolder.createEmptyContext();
                    context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(fresh, null,
                            fresh.getAuthorities()));
                    SecurityContextHolder.setContext(context);
                    contexts.saveContext(context, request, response);
                }
            }
        }
        chain.doFilter(request, response);
    }
}
