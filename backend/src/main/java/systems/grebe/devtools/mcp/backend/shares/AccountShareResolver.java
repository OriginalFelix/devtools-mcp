package systems.grebe.devtools.mcp.backend.shares;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.backend.account.AccountService;
import systems.grebe.devtools.mcp.backend.account.Role;
import systems.grebe.devtools.mcp.backend.account.RoleService;
import systems.grebe.devtools.mcp.backend.account.UserAccount;
import systems.grebe.devtools.mcp.modules.shares.ShareViews;

/**
 * Ziele von Freigaben aus der Core-Datenbank: aktive Benutzer mit E-Mail (die E-Mail ist der Eigentümer ihrer Skills
 * und Memories) und Rollen. Hält außerdem Freigaben an Rollen aktuell, wenn eine Rolle umbenannt oder gelöscht wird.
 */
@Component
public class AccountShareResolver implements ShareResolver {

    private final AccountService accounts;
    private final RoleService roles;
    private final ItemShareRepository shares;

    public AccountShareResolver(AccountService accounts, RoleService roles, ItemShareRepository shares) {
        this.accounts = accounts;
        this.roles = roles;
        this.shares = shares;
    }

    @Override
    public Optional<String> userEmail(String usernameOrEmail) {
        String v = usernameOrEmail == null ? "" : usernameOrEmail.strip();
        if (v.isEmpty()) {
            return Optional.empty();
        }
        Optional<UserAccount> user = v.contains("@")
                ? accounts.users().stream().filter(u -> u.email() != null && u.email().strip().equalsIgnoreCase(v))
                .findFirst()
                : accounts.userByName(v.toLowerCase(Locale.ROOT));
        return user.filter(UserAccount::enabled).map(UserAccount::email).filter(e -> !e.isBlank())
                .map(ShareViews::email);
    }

    @Override
    public Optional<String> role(String name) {
        String v = name == null ? "" : name.strip();
        return roles.roles().stream().map(Role::name).filter(n -> n.equalsIgnoreCase(v)).findFirst();
    }

    @Override
    public List<ShareViews.Candidate> candidates(String self) {
        List<ShareViews.Candidate> list = new ArrayList<>();
        accounts.users().stream()
                .filter(u -> u.enabled() && u.email() != null && !u.email().isBlank()
                        && !ShareViews.email(u.email()).equals(self))
                .sorted(Comparator.comparing(u -> u.label().toLowerCase(Locale.ROOT)))
                .forEach(u -> list.add(new ShareViews.Candidate(ShareViews.Target.USER, ShareViews.email(u.email()),
                        u.label() + " (" + u.username() + ")")));
        roles.roles().stream().sorted(Comparator.comparing(Role::name))
                .forEach(r -> list.add(new ShareViews.Candidate(ShareViews.Target.ROLE, r.name(), r.name())));
        return list;
    }

    @EventListener
    public void roleRenamed(RoleService.RoleRenamedEvent event) {
        shares.renameRole(event.from(), event.to(), ShareViews.Target.ROLE);
    }

    @EventListener
    public void roleDeleted(RoleService.RoleDeletedEvent event) {
        if (event.name() != null) {
            shares.deleteRole(event.name(), ShareViews.Target.ROLE);
        }
    }
}
