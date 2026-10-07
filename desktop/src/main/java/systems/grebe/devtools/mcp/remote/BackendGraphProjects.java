package systems.grebe.devtools.mcp.remote;

import java.nio.file.Path;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.api.ProjectInfo;
import systems.grebe.devtools.mcp.modules.graph.GraphProjects;
import systems.grebe.devtools.mcp.project.spi.ProjectDirectory;
import systems.grebe.devtools.mcp.project.spi.ProjectProvider;

/**
 * Projekt-Identität für den Code-Graphen: Ist das Verzeichnis einem Backend-Projekt zugeordnet, zählt dieses Projekt
 * (ID, eindeutiger Name {@code name@eigentümer}) – sein Graph je Branch ist für alle mit Zugriff derselbe. Sonst gilt
 * der Name aus dem {@link ProjectProvider}.
 */
@Component
public class BackendGraphProjects implements GraphProjects {

    private final BackendConnection backend;
    private final ObjectProvider<ProjectProvider> projects;

    public BackendGraphProjects(BackendConnection backend, ObjectProvider<ProjectProvider> projects) {
        this.backend = backend;
        this.projects = projects;
    }

    @Override
    public Identity identify(Path root) {
        Path dir = root.toAbsolutePath().normalize();
        for (ProjectInfo p : backend.projects()) {
            Optional<Path> local = backend.projectPath(p.id()).map(x -> x.toAbsolutePath().normalize());
            if (local.isPresent() && local.get().equals(dir)) {
                return backendProject(p);
            }
        }
        ProjectProvider provider = projects.getIfAvailable();
        if (provider == null) {
            return null;
        }
        String name = provider.projects(null).stream().filter(p -> p.path().equals(dir)).map(ProjectDirectory::name)
                .findFirst().orElse(null);
        if (name == null) {
            return null;
        }
        // Name eines Backend-Projekts (eigenes: name, fremdes: name@eigentümer), auch ohne zugeordnetes Verzeichnis
        for (ProjectInfo p : backend.projects()) {
            if (p.toolName().equalsIgnoreCase(name)) {
                return backendProject(p);
            }
        }
        return new Identity(name, null);
    }

    private static Identity backendProject(ProjectInfo p) {
        return new Identity(p.name() + "@" + p.owner(), p.id());
    }
}
