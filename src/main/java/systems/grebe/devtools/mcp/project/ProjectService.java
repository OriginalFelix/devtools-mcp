package systems.grebe.devtools.mcp.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import systems.grebe.devtools.mcp.account.AccountService;
import systems.grebe.devtools.mcp.account.UserAccount;
import systems.grebe.devtools.mcp.config.ModuleSettings;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolRegistry;

/**
 * Projekte der Benutzer und ihre Freigaben.
 *
 * <ul>
 *   <li>Anlegen dürfen Administratoren überall, andere Benutzer nur unterhalb der „Erlaubten Projektwurzeln“ (Modul
 *       Projekte, global). Ohne Wurzeln legen nur Administratoren Projekte an. Symlinks werden aufgelöst geprüft.</li>
 *   <li>Eigentümer (und Administratoren) ändern, löschen und geben frei: {@code READ} oder {@code WRITE}.</li>
 *   <li>Für angemeldete Benutzer arbeiten Git, Build und Code-Graph nur mit den eigenen und freigegebenen Projekten
 *       ({@link ProjectSettings}); schreibende Tools prüfen {@link #canWrite}.</li>
 * </ul>
 */
@Service
public class ProjectService {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._ -]{0,63}");

    private final ProjectRepository repo;
    private final AccountService accounts;
    private final ObjectProvider<ToolRegistry> registry;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;
    private final Clock clock = Clock.systemUTC();

    public ProjectService(ProjectRepository repo, AccountService accounts, ObjectProvider<ToolRegistry> registry,
                          @Qualifier("coreTransactions") TransactionTemplate tx, ApplicationEventPublisher events) {
        this.repo = repo;
        this.accounts = accounts;
        this.registry = registry;
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

    /** Ob der Benutzer im Verzeichnis (oder darunter) eines seiner Projekte schreiben darf. */
    public boolean canWrite(long userId, Path dir) {
        Path d = dir.toAbsolutePath().normalize();
        return visible(userId).stream()
                .anyMatch(v -> v.access().canWrite() && d.startsWith(v.project().root()));
    }

    /** Erlaubte Projektwurzeln (Modul Projekte, globale Einstellung). */
    public List<Path> allowedRoots() {
        ModuleSettings s = registry.getObject().settings(ProjectsModule.ID);
        return ModuleConfig.splitLines(s.values().getOrDefault(ProjectsModule.ALLOWED_ROOTS, "")).stream()
                .map(r -> Path.of(r).toAbsolutePath().normalize()).toList();
    }

    // ---------------------------------------------------------------- Ändern

    public Project create(long actorId, String name, String root, String description, String sonarKey,
                          String ticketProject) {
        UserAccount actor = user(actorId);
        String n = name(name);
        Path dir = checkRoot(actor, root);
        long id = tx.execute(s -> {
            if (repo.owned(actorId).stream().anyMatch(p -> p.name().equalsIgnoreCase(n))) {
                throw new IllegalArgumentException("Du hast schon ein Projekt „" + n + "“.");
            }
            return repo.insert(actorId, n, dir, blank(description), blank(sonarKey), blank(ticketProject),
                    clock.instant());
        });
        changed(Set.of(actorId));
        return repo.project(id).orElseThrow();
    }

    public void update(long actorId, long projectId, String name, String root, String description, String sonarKey,
                       String ticketProject) {
        Project p = manageable(actorId, projectId);
        String n = name(name);
        // Wurzel prüft die Policy des Eigentümers – ein Administrator darf auch fremde Projekte umziehen
        UserAccount actor = user(actorId);
        Path dir = p.root().toString().equals(root) ? p.root() : checkRoot(actor.admin() ? actor : user(p.ownerId()), root);
        tx.executeWithoutResult(s -> {
            if (repo.owned(p.ownerId()).stream().anyMatch(o -> o.id() != projectId && o.name().equalsIgnoreCase(n))) {
                throw new IllegalArgumentException("Projekt „" + n + "“ gibt es schon.");
            }
            repo.update(projectId, n, dir, blank(description), blank(sonarKey), blank(ticketProject));
        });
        changed(affected(p));
    }

    public void delete(long actorId, long projectId) {
        Project p = manageable(actorId, projectId);
        Set<Long> affected = affected(p);
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

    /** Prüft und normalisiert das Verzeichnis gegen die Wurzel-Policy. */
    Path checkRoot(UserAccount actor, String root) {
        Path dir;
        Path real;
        try {
            dir = Path.of(root == null ? "" : root.strip()).toAbsolutePath().normalize();
            if (root == null || root.isBlank() || !Files.isDirectory(dir)) {
                throw new IllegalArgumentException("Verzeichnis existiert nicht: " + root);
            }
            real = dir.toRealPath();
        } catch (InvalidPathException | IOException e) {
            throw new IllegalArgumentException("Ungültiges Verzeichnis: " + root);
        }
        if (actor.admin()) {
            return dir;
        }
        List<Path> allowed = allowedRoots();
        if (allowed.isEmpty()) {
            throw new IllegalArgumentException("Projekte legen nur Administratoren an – oder ein Administrator trägt "
                    + "„Erlaubte Projektwurzeln“ im Modul Projekte ein.");
        }
        for (Path a : allowed) {
            try {
                if (real.startsWith(a.toRealPath())) {
                    return dir;
                }
            } catch (IOException ignored) {
                // Wurzel existiert nicht (mehr)
            }
        }
        throw new IllegalArgumentException("Verzeichnis liegt außerhalb der erlaubten Projektwurzeln " + allowed);
    }

    private Set<Long> affected(Project p) {
        Set<Long> ids = new LinkedHashSet<>();
        ids.add(p.ownerId());
        repo.shares(p.id()).forEach(s -> ids.add(s.userId()));
        return ids;
    }

    private void changed(Set<Long> userIds) {
        events.publishEvent(new ProjectsChangedEvent(Set.copyOf(userIds)));
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

    /** Projekte oder Freigaben dieser Benutzer haben sich geändert. */
    public record ProjectsChangedEvent(Set<Long> userIds) {
    }

    /** Ob das Feld für angemeldete Benutzer aus ihren Projekten kommt (Überschreiben wirkungslos). */
    public static boolean projectField(String moduleId, String key) {
        return key.equals(DIRECTORY_FIELDS.get(moduleId));
    }

    /** Für {@link ProjectSettings}: Modul → Feld mit den Projektverzeichnissen. */
    static final Map<String, String> DIRECTORY_FIELDS = Map.of("git", "repositories", "build", "projects",
            "graph", "projects");

    /** Für {@link ProjectSettings}: Modul → Feld mit dem Standardprojekt. */
    static final Map<String, String> DEFAULT_FIELDS = Map.of("git", "defaultRepository", "build", "defaultProject",
            "graph", "defaultProject");
}
