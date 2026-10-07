package systems.grebe.devtools.mcp.project;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.api.ProjectInfo;
import systems.grebe.devtools.mcp.core.AccessModule;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolRegistry;
import systems.grebe.devtools.mcp.core.ToolScope;
import systems.grebe.devtools.mcp.core.Workspaces;
import systems.grebe.devtools.mcp.project.spi.ProjectDirectory;
import systems.grebe.devtools.mcp.project.spi.ProjectProvider;

/**
 * Stellt Plugins die freigegebenen Projektverzeichnisse bereit ({@link ProjectProvider} aus der Plugin-API): die
 * Verzeichnislisten von Git, Build, Code-Graph und Pull Requests in ihrer wirksamen Form – also inklusive der
 * Backend-Projekte ({@link ProjectInfo#DIRECTORY_FIELDS}) und der Verzeichnisse aus „Freigaben“. Aufgelöst wird wie in
 * diesen Modulen über {@link Workspaces}; Schreibrecht nach {@link ToolScope#canWrite}.
 */
@Component
public class ProjectDirectoryProvider implements ProjectProvider {

    private final Function<String, ModuleConfig> config;
    private final Predicate<String> hasModule;

    @Autowired
    public ProjectDirectoryProvider(ObjectProvider<ToolRegistry> registry) {
        // Registry erst beim Aufruf holen – sie wird mit allen Modulen aufgebaut
        this(moduleId -> registry.getObject().config(moduleId), moduleId -> registry.getObject().hasModule(moduleId));
    }

    /** Für Tests: Konfiguration je Modul direkt. */
    ProjectDirectoryProvider(Function<String, ModuleConfig> config, Predicate<String> hasModule) {
        this.config = config;
        this.hasModule = hasModule;
    }

    @Override
    public List<ProjectDirectory> projects(Predicate<Path> marker) {
        List<ProjectDirectory> out = new ArrayList<>();
        Set<Path> seen = new HashSet<>();
        // ein Verzeichnis kann benannt und über einen Sammelordner vorkommen – der erste (benannte) Eintrag gilt
        workspaces(marker).all().forEach((name, path) -> {
            if (seen.add(path)) {
                out.add(directory(name, path));
            }
        });
        return out;
    }

    @Override
    public ProjectDirectory resolve(String nameOrPath, Predicate<Path> marker) {
        Workspaces ws = workspaces(marker);
        Path path = ws.resolve(nameOrPath, null);
        String name = ws.all().entrySet().stream().filter(e -> e.getValue().equals(path)).map(Map.Entry::getKey)
                .findFirst().orElseGet(() -> path.getFileName() == null ? path.toString() : path.getFileName().toString());
        return directory(name, path);
    }

    private static ProjectDirectory directory(String name, Path path) {
        return new ProjectDirectory(name, path, ToolScope.current().canWrite(path));
    }

    /** Alle Einträge der Verzeichnisfelder, Dubletten (gleiches Verzeichnis) nur einmal – benannte zuerst. */
    private Workspaces workspaces(Predicate<Path> marker) {
        List<String> entries = new ArrayList<>();
        for (Map.Entry<String, String> field : ProjectInfo.DIRECTORY_FIELDS.entrySet()) {
            if (hasModule.test(field.getKey())) {
                config.apply(field.getKey()).getList(field.getValue()).forEach(e -> add(entries, e));
            }
        }
        if (hasModule.test(AccessModule.ID)) {
            config.apply(AccessModule.ID).getList(AccessModule.DIRECTORIES).forEach(e -> add(entries, e));
        }
        entries.sort((a, b) -> Boolean.compare(!a.contains("="), !b.contains("=")));
        return new Workspaces(entries, marker == null ? dir -> true : marker, "Projekte");
    }

    private static void add(List<String> entries, String entry) {
        Path dir = path(entry);
        if (dir == null) {
            return;
        }
        int existing = -1;
        for (int i = 0; i < entries.size(); i++) {
            if (dir.equals(path(entries.get(i)))) {
                existing = i;
                break;
            }
        }
        if (existing < 0) {
            entries.add(entry);
        } else if (entry.contains("=") && !entries.get(existing).contains("=")) {
            entries.set(existing, entry); // benannter Eintrag (Backend-Projekt) gewinnt
        }
    }

    private static Path path(String entry) {
        String raw = entry.matches("[A-Za-z0-9._@ -]+=.+") ? entry.substring(entry.indexOf('=') + 1) : entry;
        try {
            return Path.of(raw.strip()).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
