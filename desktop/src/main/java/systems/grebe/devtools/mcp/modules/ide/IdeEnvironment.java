package systems.grebe.devtools.mcp.modules.ide;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.modules.ide.spi.Ide;
import systems.grebe.devtools.mcp.modules.ide.spi.IdeProvider;
import systems.grebe.devtools.mcp.modules.ticket.spi.ProviderSettings;
import systems.grebe.devtools.mcp.project.spi.ProjectProvider;

/**
 * Ausgewertete Konfiguration des Moduls IDE: aktive IDEs, Auswahl der IDE für ein Projekt und die Auflösung von
 * Projekten und Dateien – nur innerhalb der freigegebenen Projekte ({@link ProjectProvider}, wie Git und Build).
 */
public final class IdeEnvironment {

    /** Eine aktive IDE mit ihrem Provider. */
    public record Entry(IdeProvider provider, Ide ide) {

        public String id() {
            return provider.id();
        }

        public String name() {
            return provider.displayName();
        }
    }

    /** Dateien und Ordner, an denen ein Projektverzeichnis zu erkennen ist (dazu, was die Provider erkennen). */
    static final List<String> PROJECT_MARKERS = List.of(".git", ".idea", ".vscode", ".project", "nbproject", "pom.xml",
            "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts", "package.json", "Cargo.toml",
            "go.mod", "pyproject.toml", "Package.swift", "CMakeLists.txt", "composer.json", "Gemfile", "build.xml");

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final List<IdeProvider> all;
    private final String defaultIde;
    private final ProjectProvider projects;
    private final Predicate<Path> marker;

    public IdeEnvironment(IdeProviders providers, ProjectProvider projects, ModuleConfig c) {
        Duration timeout = Duration.ofSeconds(Math.max(2, c.getInt(IdeModule.TIMEOUT, 15)));
        this.all = providers.providers();
        for (IdeProvider p : all) {
            if (c.getBoolean(IdeModule.enabledKey(p.id()))) {
                ProviderSettings ps = new ProviderSettings(k -> c.get(IdeModule.key(p.id(), k)), timeout);
                entries.put(p.id(), new Entry(p, p.create(ps)));
            }
        }
        this.defaultIde = c.getString(IdeModule.DEFAULT_IDE, "auto");
        this.projects = projects;
        this.marker = this::isProject;
    }

    public List<Entry> entries() {
        return List.copyOf(entries.values());
    }

    /** Alle bekannten Provider, auch nicht aktivierte. */
    public List<IdeProvider> providers() {
        return all;
    }

    public String defaultIde() {
        return defaultIde;
    }

    // ------------------------------------------------------------------ Auswahl der IDE

    /**
     * IDE für einen Aufruf: die angegebene, sonst die eingestellte Standard-IDE, sonst ({@code auto}) die erste aktive,
     * die Spuren im Projekt erkennt ({@code .idea}, {@code .vscode}, {@code *.sln} …), sonst die erste aktive.
     *
     * @param project Projektverzeichnis oder {@code null}
     */
    public Entry select(String ide, Path project) {
        if (entries.isEmpty()) {
            throw new IllegalStateException("Keine IDE aktiviert – in der DevTools-App unter Module → IDE z.B. "
                    + "'IntelliJ IDEA: aktiv' einschalten.");
        }
        if (!blank(ide)) {
            Entry e = entries.get(ide.trim().toLowerCase(Locale.ROOT));
            if (e == null) {
                throw new IllegalArgumentException("IDE '" + ide + "' ist nicht aktiviert. Aktiv: " + entries.keySet()
                        + " (siehe ide_providers).");
            }
            return e;
        }
        if (!"auto".equals(defaultIde) && entries.containsKey(defaultIde)) {
            return entries.get(defaultIde);
        }
        if (project != null && entries.size() > 1) {
            for (Entry e : entries.values()) {
                if (recognizes(e.provider(), project)) {
                    return e;
                }
            }
        }
        return entries.values().iterator().next();
    }

    private static boolean recognizes(IdeProvider p, Path dir) {
        try {
            return p.recognizes(dir);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Projektverzeichnis: bekannte Marker oder eine IDE, die es erkennt. */
    boolean isProject(Path dir) {
        for (String m : PROJECT_MARKERS) {
            if (Files.exists(dir.resolve(m))) {
                return true;
            }
        }
        return all.stream().anyMatch(p -> recognizes(p, dir));
    }

    // ------------------------------------------------------------------ Projekte und Dateien

    /**
     * Projektverzeichnis zu einem Namen (wie in {@code projects_list}/{@code git_list_repositories}) oder Pfad. Ein
     * Pfad darf auch ein Verzeichnis innerhalb eines freigegebenen Projekts sein (Modul, Worktree) und wird dann genau
     * so geöffnet; ohne Angabe das einzige Projekt.
     *
     * @throws IllegalArgumentException wenn es nicht freigegeben ist
     */
    public Path project(String nameOrPath) {
        Path given = absolute(nameOrPath);
        if (given != null && Files.isDirectory(given)) {
            projects.resolve(given.toString(), marker); // Freigabe prüfen
            return given;
        }
        return projects.resolve(blank(nameOrPath) ? null : nameOrPath.trim(), marker).path();
    }

    /**
     * Stelle in einer Datei. {@code file} ist absolut oder relativ zu {@code project} (ohne Projekt: zum einzigen).
     * Das Projekt der Stelle ist das angegebene, sonst das nächste Projektverzeichnis über der Datei innerhalb der
     * Freigabe.
     */
    public Ide.Location location(String file, Integer line, Integer column, String project) {
        if (blank(file)) {
            throw new IllegalArgumentException("'file' fehlt.");
        }
        Path f = absolute(file);
        if (f == null) {
            f = project(project).resolve(file.trim()).normalize();
        }
        if (!Files.isRegularFile(f)) {
            throw new IllegalArgumentException("Datei nicht gefunden: " + f);
        }
        Path root = projects.resolve(f.getParent().toString(), marker).path(); // Freigabe prüfen
        Path dir = blank(project) ? nearestProject(f.getParent(), root) : project(project);
        if (line != null && line < 0 || column != null && column < 0) {
            throw new IllegalArgumentException("'line' und 'column' zählen ab 1.");
        }
        return new Ide.Location(f, line == null ? 0 : line, column == null ? 0 : column, dir);
    }

    /** Ob eine Datei bzw. ein Verzeichnis in einem freigegebenen Projekt liegt. */
    public boolean released(Path path) {
        try {
            projects.resolve((Files.isDirectory(path) ? path : path.getParent()).toString(), marker);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Nächstes Projektverzeichnis ab {@code dir} aufwärts, höchstens bis {@code root}. */
    private Path nearestProject(Path dir, Path root) {
        for (Path d = dir; d != null && d.startsWith(root); d = d.getParent()) {
            if (isProject(d)) {
                return d;
            }
        }
        return root;
    }

    private static Path absolute(String s) {
        if (blank(s)) {
            return null;
        }
        String v = s.trim();
        if (v.startsWith("~/") || v.startsWith("~\\")) {
            v = System.getProperty("user.home") + v.substring(1);
        }
        try {
            Path p = Path.of(v);
            return p.isAbsolute() ? p.normalize() : null;
        } catch (InvalidPathException e) {
            return null;
        }
    }

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
