package systems.grebe.devtools.mcp.backend.account;

import java.time.Clock;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import systems.grebe.devtools.mcp.api.Grants;
import systems.grebe.devtools.mcp.backend.BackendChanged;

/**
 * Rollen und ihre Rechte (siehe {@link Grants}). Die eingebaute Rolle {@value Role#ADMINISTRATOR} hat alle Rechte
 * und lässt sich weder ändern noch löschen; alle anderen sind frei anlegbar. Änderungen wirken sofort: Token-Prüfungen
 * lesen die Rechte neu, die Desktop-Apps der betroffenen Benutzer laden Rollen und Rechte nach.
 */
@Service
public class RoleService {

    private static final int MAX_NAME = 64;
    private static final int MAX_DESCRIPTION = 500;
    private static final Logger LOG = LoggerFactory.getLogger(RoleService.class);

    private final AccountRepository repo;
    private final AccountService accounts;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;
    private final Clock clock = Clock.systemUTC();

    public RoleService(AccountRepository repo, AccountService accounts,
                       @Qualifier("coreTransactions") TransactionTemplate tx, ApplicationEventPublisher events) {
        this.repo = repo;
        this.accounts = accounts;
        this.tx = tx;
        this.events = events;
    }

    public List<Role> roles() {
        return repo.roles();
    }

    public Optional<Role> role(long id) {
        return repo.role(id);
    }

    public Role create(String name, String description, Collection<String> permissions) {
        String n = name(name);
        Set<String> perms = permissions(permissions);
        long id = tx.execute(s -> {
            if (repo.roleByName(n).isPresent()) {
                throw new IllegalArgumentException("Rolle „" + n + "“ gibt es schon.");
            }
            long roleId = repo.insertRole(n, description(description), false, clock.instant());
            repo.replacePermissions(roleId, perms);
            return roleId;
        });
        LOG.info("Rolle {} angelegt: {}", n, perms);
        return repo.role(id).orElseThrow();
    }

    public Role update(long id, String name, String description, Collection<String> permissions) {
        String n = name(name);
        Set<String> perms = permissions(permissions);
        String[] before = new String[1];
        accounts.guardUserManagers(() -> {
            Role r = repo.role(id).orElseThrow(() -> unknown(id));
            if (r.builtin()) {
                throw new IllegalArgumentException("Die Rolle „" + r.name() + "“ ist eingebaut und nicht änderbar.");
            }
            before[0] = r.name();
            if (repo.roleByName(n).filter(o -> o.id() != id).isPresent()) {
                throw new IllegalArgumentException("Rolle „" + n + "“ gibt es schon.");
            }
            repo.updateRole(id, n, description(description));
            repo.replacePermissions(id, perms);
        });
        LOG.info("Rolle {} geändert: {}", n, perms);
        if (before[0] != null && !before[0].equals(n)) {
            events.publishEvent(new RoleRenamedEvent(before[0], n));
        }
        changed(id);
        return repo.role(id).orElseThrow();
    }

    public void delete(long id) {
        List<Long> users = repo.roleUsers(id);
        String[] name = new String[1];
        accounts.guardUserManagers(() -> {
            Role r = repo.role(id).orElseThrow(() -> unknown(id));
            if (r.builtin()) {
                throw new IllegalArgumentException("Die Rolle „" + r.name() + "“ ist eingebaut und nicht löschbar.");
            }
            name[0] = r.name();
            repo.deleteRole(id);
        });
        LOG.info("Rolle {} gelöscht", id);
        events.publishEvent(new RoleDeletedEvent(name[0]));
        notify(users);
    }

    private void changed(long roleId) {
        notify(repo.roleUsers(roleId));
    }

    private void notify(List<Long> users) {
        events.publishEvent(new AccountService.AccountChangedEvent(null));
        if (!users.isEmpty()) {
            events.publishEvent(new BackendChanged(BackendChanged.Topic.SETTINGS, Set.copyOf(users)));
        }
    }

    private static String name(String name) {
        String n = name == null ? "" : name.strip();
        if (n.isEmpty() || n.length() > MAX_NAME) {
            throw new IllegalArgumentException("Rollenname: 1–" + MAX_NAME + " Zeichen.");
        }
        return n;
    }

    private static String description(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        String d = description.strip();
        if (d.length() > MAX_DESCRIPTION) {
            throw new IllegalArgumentException("Beschreibung: höchstens " + MAX_DESCRIPTION + " Zeichen.");
        }
        return d;
    }

    private static Set<String> permissions(Collection<String> permissions) {
        Set<String> out = new LinkedHashSet<>();
        if (permissions != null) {
            for (String p : permissions) {
                String v = p == null ? "" : p.strip();
                if (v.isEmpty()) {
                    continue;
                }
                if (Grants.ALL.equals(v)) {
                    throw new IllegalArgumentException("Alle Rechte hat nur die Rolle „" + Role.ADMINISTRATOR + "“.");
                }
                if (!Grants.valid(v)) {
                    throw new IllegalArgumentException("Unbekanntes Recht '" + v + "'");
                }
                out.add(v);
            }
        }
        return out;
    }

    private static IllegalArgumentException unknown(long id) {
        return new IllegalArgumentException("Unbekannte Rolle " + id);
    }

    /** Eine Rolle heißt jetzt anders – Freigaben an sie wandern mit. */
    public record RoleRenamedEvent(String from, String to) {
    }

    /** Eine Rolle wurde gelöscht – Freigaben an sie entfallen. */
    public record RoleDeletedEvent(String name) {
    }
}
