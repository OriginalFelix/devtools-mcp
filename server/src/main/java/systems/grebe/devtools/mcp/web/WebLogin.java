package systems.grebe.devtools.mcp.web;

import java.util.ArrayList;
import java.util.List;

import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsPasswordService;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.api.Permission;
import systems.grebe.devtools.mcp.backend.account.AccountService;
import systems.grebe.devtools.mcp.backend.account.LoginThrottle;
import systems.grebe.devtools.mcp.backend.account.UserAccount;

/**
 * Formular-Anmeldung der Web-UI (Spring Security) gegen die Benutzerkonten des Backends. Jedes Systemrecht des
 * Benutzers wird zur Rolle gleichen Namens ({@code @RolesAllowed("USERS_MANAGE")}); Fehlversuche bremst dieselbe
 * {@link LoginThrottle} wie bei den Desktop-Apps (gesperrt = {@code LockedException}, ohne Passwortprüfung).
 */
@Component
public class WebLogin implements UserDetailsService, UserDetailsPasswordService {

    private final AccountService accounts;
    private final LoginThrottle throttle;

    public WebLogin(AccountService accounts, LoginThrottle throttle) {
        this.accounts = accounts;
        this.throttle = throttle;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        AccountService.Login login = accounts.login(username)
                .orElseThrow(() -> new UsernameNotFoundException("Unbekannter Benutzer"));
        return principal(login.user(), login.passwordHash(), throttle.blocked(username).isPresent());
    }

    /** Hash neu berechnen, wenn die Iterationszahl angehoben wurde. */
    @Override
    public UserDetails updatePassword(UserDetails user, String newPassword) {
        AccountPrincipal p = (AccountPrincipal) user;
        accounts.storeRehashedPassword(p.id(), newPassword);
        return principal(accounts.user(p.id()).orElseThrow(), newPassword, false);
    }

    @EventListener
    public void onFailure(AuthenticationFailureBadCredentialsEvent e) {
        throttle.failed(e.getAuthentication().getName());
    }

    @EventListener
    public void onSuccess(AuthenticationSuccessEvent e) {
        if (e.getAuthentication().getPrincipal() instanceof AccountPrincipal p) {
            throttle.succeeded(p.getUsername());
            accounts.recordLogin(p.id());
        }
    }

    /** Angemeldeter Benutzer mit seinen Rechten als Rollen; {@code hash} leer für eine aufgefrischte Sitzung. */
    static AccountPrincipal principal(UserAccount u, String hash, boolean locked) {
        return new AccountPrincipal(u.id(), u.username(), hash == null ? "" : hash, u.enabled(), !locked,
                authorities(u), u.passwordChangeRequired());
    }

    static List<GrantedAuthority> authorities(UserAccount u) {
        List<GrantedAuthority> list = new ArrayList<>();
        for (Permission p : Permission.values()) {
            if (u.has(p)) {
                list.add(new SimpleGrantedAuthority("ROLE_" + p.name()));
            }
        }
        return list;
    }

    /** Angemeldeter Benutzer in der Web-UI; trägt die Benutzer-ID. */
    public static final class AccountPrincipal extends User {
        private final long id;
        private final boolean passwordChangeRequired;

        AccountPrincipal(long id, String username, String hash, boolean enabled, boolean nonLocked,
                         List<GrantedAuthority> authorities, boolean passwordChangeRequired) {
            super(username, hash, enabled, true, true, nonLocked, authorities);
            this.id = id;
            this.passwordChangeRequired = passwordChangeRequired;
        }

        public long id() {
            return id;
        }

        /** Muss das Passwort ändern, bevor er weiterarbeiten kann (nur „Mein Konto“ erreichbar). */
        public boolean passwordChangeRequired() {
            return passwordChangeRequired;
        }

        /** Gleicher Stand wie {@code other}: Name, Rechte, Pflicht zum Passwortwechsel. */
        boolean sameAs(AccountPrincipal other) {
            return getUsername().equals(other.getUsername()) && passwordChangeRequired == other.passwordChangeRequired
                    && java.util.Set.copyOf(getAuthorities()).equals(java.util.Set.copyOf(other.getAuthorities()));
        }
    }
}
