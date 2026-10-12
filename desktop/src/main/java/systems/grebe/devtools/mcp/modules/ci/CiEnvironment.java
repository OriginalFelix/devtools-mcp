package systems.grebe.devtools.mcp.modules.ci;

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
import org.eclipse.jgit.lib.Repository;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.Workspaces;
import systems.grebe.devtools.mcp.modules.ci.spi.CiProvider;
import systems.grebe.devtools.mcp.modules.ci.spi.CiSystem;
import systems.grebe.devtools.mcp.modules.ticket.spi.ProviderSettings;

/**
 * Ausgewertete Konfiguration des Moduls CI/CD: aktive Systeme, lokale Repositories und die Zuordnung eines Aufrufs zu
 * System und Projekt (über Parameter, Build-URL oder das Remote des lokalen Repositories).
 */
public final class CiEnvironment {

    /** Ein aktives System mit seinem Provider. */
    public record Entry(CiProvider provider, CiSystem system) { }

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
     * @param project Projekt im CI-System (Repository, Projektpfad, Jenkins-Job)
     * @param local lokales Repository, aus dem System und Projekt ermittelt wurden, sonst {@code null}
     */
    public record Target(Entry entry, String project, LocalRepo local) {
        public CiSystem system() {
            return entry.system();
        }

        public String providerId() {
            return entry.provider().id();
        }

        /** Aktueller Branch des lokalen Repositories oder {@code null}. */
        public String localBranch() {
            return local == null ? null : local.branch();
        }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final String defaultProvider;
    private final Workspaces repositories;
    private final String defaultRepository;
    private final String remote;
    private final int maxLines;
    private final int logLines;
    private final List<String> writeProjects;

    public CiEnvironment(CiProviders providers, ModuleConfig c) {
        Duration timeout = Duration.ofSeconds(Math.max(5, c.getInt(CiModule.TIMEOUT, 30)));
        for (CiProvider p : providers.providers()) {
            if (!c.getBoolean(CiModule.enabledKey(p.id()))) {
                continue;
            }
            ProviderSettings ps = new ProviderSettings(k -> c.get(CiModule.key(p.id(), k)), timeout);
            entries.put(p.id(), new Entry(p, p.create(ps)));
        }
        this.defaultProvider = c.getString(CiModule.DEFAULT_PROVIDER, "auto");
        this.repositories = new Workspaces(c.getList(CiModule.REPOSITORIES), dir -> Files.exists(dir.resolve(".git")),
                "Git-Repositories");
        this.defaultRepository = c.getString(CiModule.DEFAULT_REPOSITORY, null);
        this.remote = c.getString(CiModule.REMOTE, "origin");
        this.maxLines = Math.max(50, c.getInt(CiModule.MAX_LINES, 1500));
        this.logLines = Math.max(10, c.getInt(CiModule.LOG_LINES, 200));
        this.writeProjects = c.getList(CiModule.WRITE_PROJECTS);
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

    public int logLines() {
        return logLines;
    }

    // ------------------------------------------------------------------ Zuordnung

    /**
     * Bestimmt System und Projekt eines Aufrufs. Reihenfolge: ausdrücklicher {@code provider} bzw. das System, dem die
     * Build-URL gehört; Projekt aus {@code project}, aus einem vollen Schlüssel ({@code owner/repo#123}) oder aus dem
     * Remote des lokalen Repositories (das dann auch das System bestimmt).
     */
    public Target target(String provider, String repository, String project, String build) {
        if (entries.isEmpty()) {
            throw new IllegalStateException("Kein CI-System aktiviert – in der DevTools-App unter Module → CI/CD "
                    + "z.B. 'Jenkins: aktiv' einschalten und Zugangsdaten eintragen.");
        }
        Entry entry = blank(provider) ? owner(build) : byId(provider);
        if (!blank(project)) {
            Entry e = entry != null ? entry : fallback();
            return new Target(e, project.trim(), blank(repository) ? null : local(repository));
        }
        if (entry != null && !blank(build)) {
            String fromKey = projectFromKey(entry, build);
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
                            + local.remoteUrl() + ") gehört zu keinem aktiven CI-System " + entries.keySet()
                            + " – 'provider' und 'project' angeben (Jenkins: Job-Zuordnung in der DevTools-App "
                            + "eintragen).");
                } else {
                    entry = fallback();
                }
            }
            String p = projectOfRemote(entry, local.remoteUrl());
            if (p == null) {
                throw new IllegalArgumentException("Remote " + local.remoteUrl() + " von '" + local.name()
                        + "' ist " + entry.provider().displayName() + " (" + entry.system().instance()
                        + ") nicht zugeordnet – 'project' (" + entry.provider().projectHelp() + ") angeben.");
            }
            return new Target(entry, p, local);
        }
        Entry e = entry != null ? entry : fallback();
        String fromKey = blank(build) ? null : projectFromKey(e, build);
        if (fromKey == null) {
            throw new IllegalArgumentException("Kein Projekt bestimmbar – 'project' (" + e.provider().projectHelp()
                    + ") angeben, einen vollen Schlüssel/URL verwenden oder in der DevTools-App lokale Repositories "
                    + "freigeben.");
        }
        return new Target(e, fromKey, null);
    }

    private static String projectFromKey(Entry e, String build) {
        try {
            return e.system().projectOf(build.trim(), null);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String projectOfRemote(Entry e, String url) {
        try {
            return e.system().projectOfRemote(url);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private Entry byId(String provider) {
        Entry e = entries.get(provider.trim().toLowerCase(Locale.ROOT));
        if (e == null) {
            throw new IllegalArgumentException("CI-System '" + provider + "' ist nicht aktiviert. Aktiv: "
                    + entries.keySet() + " (siehe ci_providers).");
        }
        return e;
    }

    /** System, dem die Build-URL eindeutig gehört, sonst {@code null}. */
    private Entry owner(String build) {
        if (blank(build)) {
            return null;
        }
        List<Entry> owners = entries.values().stream().filter(e -> {
            try {
                return e.system().ownsKey(build.trim());
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
        throw new IllegalArgumentException("Mehrere CI-Systeme aktiv " + entries.keySet()
                + " – 'provider' angeben (oder in der App ein Standard-System wählen).");
    }

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

    // ------------------------------------------------------------------ Schreibfreigabe

    /**
     * Prüft vor jeder steuernden Aktion, ob das Projekt freigegeben ist. Geprüft wird das Projekt aus dem
     * Build-Schlüssel, damit {@code owner/anderes-repo#1} die Freigabe nicht über {@code project} umgeht.
     *
     * @param build Build-Angabe oder {@code null} beim Starten (dann zählt das Projekt des Ziels)
     */
    public String checkWrite(Target t, String build, String action) {
        String target = build == null ? t.project() : t.system().projectOf(build.trim(), t.project());
        if (writeProjects.isEmpty()) {
            return target;
        }
        if (target == null || !writeAllowed(t.providerId(), target)) {
            throw new IllegalStateException(action + " in '" + target + "' (" + t.providerId() + ") ist nicht "
                    + "freigegeben. Freigegeben: " + writeProjects + ". Der Nutzer kann das Projekt in der DevTools-App "
                    + "unter Module → CI/CD → 'Steuern nur in diesen Projekten' ergänzen.");
        }
        return target;
    }

    /** Eintrag {@code [system:]projekt} bzw. {@code [system:]präfix*}; ohne Groß-/Kleinschreibung. */
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

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
