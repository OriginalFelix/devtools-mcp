package systems.grebe.devtools.mcp.web;

import java.util.List;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsPasswordService;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.backend.account.AccountService;
import systems.grebe.devtools.mcp.backend.account.UserAccount;

/** Formular-Anmeldung der Web-UI (Spring Security) gegen die Benutzerkonten des Backends. */
@Component
public class WebLogin implements UserDetailsService, UserDetailsPasswordService {

    private final AccountService accounts;

    public WebLogin(AccountService accounts) {
        this.accounts = accounts;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        AccountService.Login login = accounts.login(username)
                .orElseThrow(() -> new UsernameNotFoundException("Unbekannter Benutzer"));
        return principal(login.user(), login.passwordHash());
    }

    /** Hash neu berechnen, wenn die Iterationszahl angehoben wurde. */
    @Override
    public UserDetails updatePassword(UserDetails user, String newPassword) {
        AccountPrincipal p = (AccountPrincipal) user;
        accounts.storeRehashedPassword(p.id(), newPassword);
        return principal(accounts.user(p.id()).orElseThrow(), newPassword);
    }

    private static AccountPrincipal principal(UserAccount u, String hash) {
        return new AccountPrincipal(u.id(), u.username(), hash, u.enabled(),
                List.of(new SimpleGrantedAuthority(u.role().authority())));
    }

    /** Angemeldeter Benutzer in der Web-UI; trägt die Benutzer-ID. */
    public static final class AccountPrincipal extends User {
        private final long id;

        AccountPrincipal(long id, String username, String hash, boolean enabled,
                         List<SimpleGrantedAuthority> authorities) {
            super(username, hash, enabled, true, true, true, authorities);
            this.id = id;
        }

        public long id() {
            return id;
        }
    }
}
