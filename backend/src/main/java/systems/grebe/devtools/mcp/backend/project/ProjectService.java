package systems.grebe.devtools.mcp.backend.project;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import systems.grebe.devtools.mcp.backend.account.AccountService;
import systems.grebe.devtools.mcp.backend.account.UserAccount;
import systems.grebe.devtools.mcp.backend.BackendChanged;

/**
 * Projekte der Benutzer und ihre Freigaben – nur Metadaten (Name, Beschreibung, Sonar-Schlüssel, Ticket-Projekt).
 *
 * <ul>
 *   <li>Jeder Benutzer legt eigene Projekte an; Eigentümer (und Administratoren) ändern, löschen und geben frei:
 *       {@code READ} oder {@code WRITE}.</li>
 *   <li>Die Desktop-App holt die sichtbaren Projekte über die REST-API und ordnet ihnen lokal ein Verzeichnis zu; Git,
 *       Build und Code-Graph arbeiten dort mit diesen Verzeichnissen, schreibende Tools nur bei {@code WRITE}.</li>
 * </ul>
 */
@Service
public class ProjectService {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._ -]{0,63}");

    private final ProjectRepository repo;
    private final AccountService accounts;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;
    private final Clock clock = Clock.systemUTC();

    public ProjectService(ProjectRepository repo, AccountService accounts,
                          @Qualifier("coreTransactions") TransactionTemplate tx, ApplicationEventPublisher events) {
        this.repo = repo;
        this.accounts = accounts;
        this.tx = tx;
        this.events = events;
    }

    // ---------------------------------------------------------------- Lesen

    /** Eigene und freigegebene Projekte des Benutzers. */
    public List<Project.Visible> visible(long userId) {
        List<Project.Visible> out = new ArrayList<>();
        repo.owned(userId).forEach(p -> out.add(new Project.Visible(p, Project.Access.OWNER)));
        out.addAll(repo.sharedWith(userId));
        return out;
    }

    /** Alle Projekte (Administratoren). */
    public List<Project> all() {
        return repo.all();
    }

    public List<Project.Share> shares(long actorId, long projectId) {
        manageable(actorId, projectId);
        return repo.shares(projectId);
    }

    // ---------------------------------------------------------------- Ändern

    public Project create(long actorId, String name, String description, String sonarKey,
                          String ticketProject) {
        user(actorId);
        String n = name(name);
        long id = tx.execute(s -> {
            if (repo.owned(actorId).stream().anyMatch(p -> p.name().equalsIgnoreCase(n))) {
                throw new IllegalArgumentException("Du hast schon ein Projekt „" + n + "“.");
            }
            return repo.insert(actorId, n, blank(description), blank(sonarKey), blank(ticketProject),
                    clock.instant());
        });
        changed(Set.of(actorId));
        return repo.project(id).orElseThrow();
    }

    public void update(long actorId, long projectId, String name, String description, String sonarKey,
                       String ticketProject) {
        Project p = manageable(actorId, projectId);
        String n = name(name);
        tx.executeWithoutResult(s -> {
            if (repo.owned(p.ownerId()).stream().anyMatch(o -> o.id() != projectId && o.name().equalsIgnoreCase(n))) {
                throw new IllegalArgumentException("Projekt „" + n + "“ gibt es schon.");
            }
            repo.update(projectId, n, blank(description), blank(sonarKey), blank(ticketProject));
        });
        changed(affected(p));
    }

    public void delete(long actorId, long projectId) {
        Set<Long> affected = affected(manageable(actorId, projectId));
        repo.delete(projectId);
        changed(affected);
    }

    /** Gibt ein Projekt frei bzw. ändert den Zugriff einer bestehenden Freigabe. */
    public void share(long actorId, long projectId, String username, Project.Access access) {
        Project p = manageable(actorId, projectId);
        if (access == Project.Access.OWNER) {
            throw new IllegalArgumentException("Freigabe nur als lesen oder lesen + schreiben.");
        }
        UserAccount target = accounts.userByName(username)
                .orElseThrow(() -> new IllegalArgumentException("Unbekannter Benutzer '" + username + "'"));
        if (target.id() == p.ownerId()) {
            throw new IllegalArgumentException("Der Eigentümer hat ohnehin vollen Zugriff.");
        }
        repo.upsertShare(projectId, target.id(), access);
        changed(Set.of(target.id()));
    }

    public void unshare(long actorId, long projectId, long userId) {
        manageable(actorId, projectId);
        repo.deleteShare(projectId, userId);
        changed(Set.of(userId));
    }

    // ---------------------------------------------------------------- intern

    private Project manageable(long actorId, long projectId) {
        Project p = repo.project(projectId).orElseThrow(() -> new IllegalArgumentException("Unbekanntes Projekt"));
        if (p.ownerId() != actorId && !user(actorId).admin()) {
            throw new IllegalArgumentException("Nur der Eigentümer kann das Projekt ändern oder freigeben.");
        }
        return p;
    }

    private Set<Long> affected(Project p) {
        Set<Long> ids = new LinkedHashSet<>();
        ids.add(p.ownerId());
        repo.shares(p.id()).forEach(s -> ids.add(s.userId()));
        return ids;
    }

    private void changed(Set<Long> userIds) {
        events.publishEvent(new BackendChanged(BackendChanged.Topic.PROJECTS, Set.copyOf(userIds)));
    }

    private UserAccount user(long id) {
        return accounts.user(id).orElseThrow(() -> new IllegalArgumentException("Unbekannter Benutzer " + id));
    }

    private static String name(String name) {
        String n = name == null ? "" : name.strip();
        if (!NAME.matcher(n).matches()) {
            throw new IllegalArgumentException("Projektname: 1–64 Zeichen, Buchstaben, Ziffern, Punkt, Unterstrich, "
                    + "Leerzeichen, Bindestrich.");
        }
        return n;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
