package systems.grebe.devtools.mcp.modules.pr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.Workspaces;
import systems.grebe.devtools.mcp.modules.pr.spi.GitServer;
import systems.grebe.devtools.mcp.modules.pr.spi.GitServerProvider;
import systems.grebe.devtools.mcp.modules.ticket.spi.ProviderSettings;

/**
 * Ausgewertete Konfiguration des Moduls Pull Requests: aktive Git-Server, lokale Repositories und die Zuordnung eines
 * Aufrufs zu Server und Repository (über Parameter, Pull-Request-URL oder das Remote des lokalen Repositories).
 */
public final class PrEnvironment {

    /** Ein aktiver Server mit seinem Provider. */
    public record Entry(GitServerProvider provider, GitServer server) { }

    /**
     * Lokales Git-Repository mit Remote.
     *
     * @param branch aktueller Branch ({@code null} bei losgelöstem HEAD)
     * @param remoteUrl URL des konfigurierten Remotes oder {@code null}
     */
    public record LocalRepo(String name, Path dir, String branch, String remote, String remoteUrl) { }

    /**
     * Wohin ein Aufruf geht.
     *
     * @param project Repository-Pfad auf dem Server
     * @param local lokales Repository, aus dem Server und Repository ermittelt wurden, sonst {@code null}
     */
    public record Target(Entry entry, String project, LocalRepo local) {
        public GitServer server() {
            return entry.server();
        }

        public String providerId() {
            return entry.provider().id();
        }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final String defaultProvider;
    private final Workspaces repositories;
    private final String defaultRepository;
    private final String remote;
    private final int maxLines;
    private final List<String> writeProjects;
    private final String commentSuffix;
    private final Duration pushTimeout;

    public PrEnvironment(GitServerProviders providers, ModuleConfig c) {
        Duration timeout = Duration.ofSeconds(Math.max(5, c.getInt(PrModule.TIMEOUT, 30)));
        for (GitServerProvider p : providers.providers()) {
            if (!c.getBoolean(PrModule.enabledKey(p.id()))) {
                continue;
            }
            ProviderSettings ps = new ProviderSettings(k -> c.get(PrModule.key(p.id(), k)), timeout);
            entries.put(p.id(), new Entry(p, p.create(ps)));
        }
        this.defaultProvider = c.getString(PrModule.DEFAULT_PROVIDER, "auto");
        this.repositories = new Workspaces(c.getList(PrModule.REPOSITORIES), dir -> Files.exists(dir.resolve(".git")),
                "Git-Repositories");
        this.defaultRepository = c.getString(PrModule.DEFAULT_REPOSITORY, null);
        this.remote = c.getString(PrModule.REMOTE, "origin");
        this.maxLines = Math.max(50, c.getInt(PrModule.MAX_LINES, 1500));
        this.writeProjects = c.getList(PrModule.WRITE_PROJECTS);
        this.commentSuffix = c.getString(PrModule.COMMENT_SUFFIX, "");
        this.pushTimeout = Duration.ofSeconds(Math.max(30, c.getInt(PrModule.PUSH_TIMEOUT, 120)));
    }

    public List<Entry> entries() {
        return List.copyOf(entries.values());
    }

    public Workspaces repositories() {
        return repositories;
    }

    public int maxLines() {
        return maxLines;
    }

    public Duration pushTimeout() {
        return pushTimeout;
    }

    // ------------------------------------------------------------------ Zuordnung

    /**
     * Bestimmt Server und Repository eines Aufrufs. Reihenfolge: ausdrücklicher {@code provider} bzw. der Server,
     * dem die Pull-Request-URL gehört; Repository aus {@code project}, aus einem vollen Schlüssel ({@code owner/repo#12})
     * oder aus dem Remote des lokalen Repositories (das dann auch den Server bestimmt).
     */
    public Target target(String provider, String repository, String project, String pr) {
        if (entries.isEmpty()) {
            throw new IllegalStateException("Kein Git-Server aktiviert – in der DevTools-App unter Module → Pull Requests "
                    + "z.B. 'GitHub: aktiv' einschalten und ein Token eintragen.");
        }
        Entry entry = blank(provider) ? owner(pr) : byId(provider);
        if (!blank(project)) {
            Entry e = entry != null ? entry : fallback();
            return new Target(e, project.trim(), blank(repository) ? null : local(repository));
        }
        if (entry != null && !blank(pr)) {
            String fromKey = projectFromKey(entry, pr);
            if (fromKey != null) {
                return new Target(entry, fromKey, null);
            }
        }
        if (!blank(repository) || !repositories.isEmpty()) {
            LocalRepo local = local(repository);
            if (local.remoteUrl() == null) {
                throw new IllegalStateException("Repository '" + local.name() + "' hat kein Remote '" + local.remote()
                        + "' – 'project' angeben oder in der DevTools-App ein anderes Remote einstellen.");
            }
            if (entry == null) {
                List<Entry> owners = entries.values().stream()
                        .filter(e -> projectOfRemote(e, local.remoteUrl()) != null).toList();
                if (owners.size() == 1) {
                    entry = owners.getFirst();
                } else if (owners.isEmpty()) {
                    throw new IllegalArgumentException("Remote '" + local.remote() + "' von '" + local.name() + "' ("
                            + local.remoteUrl() + ") gehört zu keinem aktiven Git-Server " + entries.keySet()
                            + " – Server-URL in der DevTools-App prüfen oder 'provider' und 'project' angeben.");
                } else {
                    entry = fallback();
                }
            }
            String p = projectOfRemote(entry, local.remoteUrl());
            if (p == null) {
                throw new IllegalArgumentException("Remote " + local.remoteUrl() + " von '" + local.name()
                        + "' gehört nicht zu " + entry.provider().displayName() + " (" + entry.server().instance()
                        + ") – 'project' angeben.");
            }
            return new Target(entry, p, local);
        }
        Entry e = entry != null ? entry : fallback();
        String fromKey = blank(pr) ? null : projectFromKey(e, pr);
        if (fromKey == null) {
            throw new IllegalArgumentException("Kein Repository bestimmbar – 'project' (" + e.provider().projectHelp()
                    + ") angeben, einen vollen Schlüssel/URL verwenden oder in der DevTools-App lokale Repositories "
                    + "freigeben.");
        }
        return new Target(e, fromKey, null);
    }

    private static String projectFromKey(Entry e, String pr) {
        try {
            return e.server().projectOf(pr.trim(), null);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String projectOfRemote(Entry e, String url) {
        try {
            return e.server().projectOfRemote(url);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private Entry byId(String provider) {
        Entry e = entries.get(provider.trim().toLowerCase(Locale.ROOT));
        if (e == null) {
            throw new IllegalArgumentException("Git-Server '" + provider + "' ist nicht aktiviert. Aktiv: "
                    + entries.keySet() + " (siehe pr_providers).");
        }
        return e;
    }

    /** Server, dem die Pull-Request-URL eindeutig gehört, sonst {@code null}. */
    private Entry owner(String pr) {
        if (blank(pr)) {
            return null;
        }
        List<Entry> owners = entries.values().stream().filter(e -> {
            try {
                return e.server().ownsKey(pr.trim());
            } catch (RuntimeException ex) {
                return false;
            }
        }).toList();
        return owners.size() == 1 ? owners.getFirst() : null;
    }

    private Entry fallback() {
        if (!"auto".equals(defaultProvider) && entries.containsKey(defaultProvider)) {
            return entries.get(defaultProvider);
        }
        if (entries.size() == 1) {
            return entries.values().iterator().next();
        }
        throw new IllegalArgumentException("Mehrere Git-Server aktiv " + entries.keySet()
                + " – 'provider' angeben (oder in der App einen Standard-Server wählen).");
    }

    // ------------------------------------------------------------------ Lokale Repositories

    /** Lokales Repository mit aktuellem Branch und Remote-URL. */
    public LocalRepo local(String repository) {
        Path dir = repositories.resolve(repository, defaultRepository);
        String name = repositories.all().entrySet().stream().filter(e -> e.getValue().equals(dir))
                .map(Map.Entry::getKey).findFirst().orElse(dir.getFileName().toString());
        try (Git git = Git.open(dir.toFile())) {
            Repository repo = git.getRepository();
            String branch = repo.getFullBranch() != null && repo.getFullBranch().startsWith(Constants.R_HEADS)
                    ? repo.getBranch() : null;
            String url = repo.getConfig().getString("remote", remote, "url");
            return new LocalRepo(name, dir, branch, remote, url);
        } catch (IOException e) {
            throw new IllegalStateException("Git-Repository '" + name + "' nicht lesbar: " + e.getMessage(), e);
        }
    }

    /**
     * Commits des lokalen Branches, die das Remote nicht hat; {@code -1} = Branch existiert auf dem Remote nicht
     * (Stand des letzten Fetch/Push).
     */
    public static int unpushed(LocalRepo local, String branch) {
        try (Git git = Git.open(local.dir().toFile())) {
            Repository repo = git.getRepository();
            Ref remoteRef = repo.exactRef(Constants.R_REMOTES + local.remote() + "/" + branch);
            Ref localRef = repo.exactRef(Constants.R_HEADS + branch);
            if (remoteRef == null) {
                return -1;
            }
            if (localRef == null) {
                return 0;
            }
            ObjectId remoteId = remoteRef.getObjectId();
            try (RevWalk walk = new RevWalk(repo)) {
                walk.markStart(walk.parseCommit(localRef.getObjectId()));
                walk.markUninteresting(walk.parseCommit(remoteId));
                int n = 0;
                while (walk.next() != null) {
                    n++;
                }
                return n;
            }
        } catch (IOException e) {
            return 0;
        }
    }

    /** Ob es den lokalen Branch gibt. */
    public static boolean hasBranch(LocalRepo local, String branch) {
        try (Git git = Git.open(local.dir().toFile())) {
            return git.getRepository().exactRef(Constants.R_HEADS + branch) != null;
        } catch (IOException e) {
            return false;
        }
    }

    /** Standard-Branch des Remotes laut {@code refs/remotes/<remote>/HEAD}, sonst {@code null}. */
    public static String remoteDefaultBranch(LocalRepo local) {
        try (Git git = Git.open(local.dir().toFile())) {
            Ref head = git.getRepository().exactRef(Constants.R_REMOTES + local.remote() + "/HEAD");
            if (head != null && head.isSymbolic()) {
                String name = head.getTarget().getName();
                String prefix = Constants.R_REMOTES + local.remote() + "/";
                return name.startsWith(prefix) ? name.substring(prefix.length()) : null;
            }
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ Schreibfreigabe

    /**
     * Prüft vor jeder schreibenden Aktion, ob das Repository freigegeben ist. Geprüft wird das Repository aus dem
     * Pull-Request-Schlüssel, damit {@code owner/anderes-repo#1} die Freigabe nicht über {@code project} umgeht.
     *
     * @param pr Pull-Request-Angabe oder {@code null} beim Anlegen (dann zählt das Repository des Ziels)
     */
    public String checkWrite(Target t, String pr, String action) {
        String target = pr == null ? t.project() : t.server().projectOf(pr.trim(), t.project());
        if (writeProjects.isEmpty()) {
            return target;
        }
        if (target == null || !writeAllowed(t.providerId(), target)) {
            throw new IllegalStateException(action + " in '" + target + "' (" + t.providerId() + ") ist nicht "
                    + "freigegeben. Freigegeben: " + writeProjects + ". Der Nutzer kann das Repository in der DevTools-App "
                    + "unter Module → Pull Requests → 'Schreiben nur in diesen Repositories' ergänzen.");
        }
        return target;
    }

    /** Eintrag {@code [system:]repo} bzw. {@code [system:]präfix*}; ohne Groß-/Kleinschreibung. */
    boolean writeAllowed(String providerId, String project) {
        String p = project.toLowerCase(Locale.ROOT);
        for (String raw : writeProjects) {
            String entry = raw.trim().toLowerCase(Locale.ROOT);
            int colon = entry.indexOf(':');
            if (colon >= 0) {
                if (!entry.substring(0, colon).trim().equals(providerId)) {
                    continue;
                }
                entry = entry.substring(colon + 1).trim();
            }
            if (entry.equals("*") || entry.equals(p)
                    || entry.endsWith("*") && p.startsWith(entry.substring(0, entry.length() - 1))) {
                return true;
            }
        }
        return false;
    }

    /** Kommentartext mit optionaler Kennzeichnung. */
    public String commentBody(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("Leerer Kommentar – Text in 'body' angeben.");
        }
        return commentSuffix.isBlank() ? body.strip() : body.strip() + "\n\n" + commentSuffix.strip();
    }

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
