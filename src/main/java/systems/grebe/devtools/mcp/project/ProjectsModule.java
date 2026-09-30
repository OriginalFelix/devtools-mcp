package systems.grebe.devtools.mcp.project;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.ToolBeans;
import systems.grebe.devtools.mcp.core.ToolHints;
import systems.grebe.devtools.mcp.core.ToolModule;
import systems.grebe.devtools.mcp.core.ToolScope;

/**
 * Projekte (Team-Server): {@code projects_list} zeigt dem LLM die eigenen und freigegebenen Projekte samt Zugriff,
 * Sonar-Schlüssel und Ticket-Projekt. Die globale Einstellung „Erlaubte Projektwurzeln“ legt fest, wo Benutzer
 * Projekte anlegen dürfen. Verwaltet werden Projekte in der Web-UI.
 */
@Component
public class ProjectsModule implements ToolModule {

    public static final String ID = "projects";
    static final String ALLOWED_ROOTS = "allowedRoots";

    private final ProjectService projects;

    public ProjectsModule(ProjectService projects) {
        this.projects = projects;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Projekte";
    }

    @Override
    public String description() {
        return "Eigene und freigegebene Projekte der Benutzer (Team-Server). Git, Build und Code-Graph arbeiten für "
                + "angemeldete Benutzer genau mit diesen Projekten; Verwaltung und Freigaben in der Web-UI.";
    }

    @Override
    public String instructions() {
        return """
                Wenn `projects_list` angeboten wird: zuerst damit die verfügbaren Projekte ermitteln. Die dort \
                genannten Namen sind in git_*, build_* und graph_* als Repository/Projekt zu verwenden; fremde \
                Projekte heißen `name@eigentümer`. „nur lesen“ heißt: keine Commits, Builds oder Graph-Aufbauten – \
                die Tools lehnen das ab. Sonar-Schlüssel und Ticket-Projekt aus der Liste an sonar_*/ticket_* übergeben.""";
    }

    @Override
    public boolean enabledByDefault() {
        return true;
    }

    @Override
    public int order() {
        return 5;
    }

    @Override
    public List<ConfigField> configSchema() {
        return List.of(ConfigField.of(ALLOWED_ROOTS, "Erlaubte Projektwurzeln", FieldType.DIRECTORY_LIST)
                .withHelp("Benutzer dürfen Projekte nur in Verzeichnissen darunter anlegen (Symlinks werden aufgelöst). "
                        + "Leer = nur Administratoren legen Projekte an."));
    }

    @Override
    public List<ToolCallback> createTools(ModuleConfig config) {
        return ToolBeans.callbacks(new ProjectTools(projects));
    }

    /** Tools des Moduls. */
    @ToolHints(readOnly = true, openWorld = false)
    public static class ProjectTools {

        private final ProjectService projects;

        ProjectTools(ProjectService projects) {
            this.projects = projects;
        }

        @Tool(name = "list", description = "Listet deine Projekte und die dir freigegebenen mit Zugriff (Eigentümer, "
                + "lesen + schreiben, nur lesen), Pfad, Sonar-Schlüssel, Ticket-Projekt und was darin erkannt wurde "
                + "(Git, Gradle/Maven)." + ShellHints.PROJECTS)
        public String list() {
            ToolScope scope = ToolScope.current();
            if (scope.userId().isEmpty()) {
                return "Einzelplatz-Betrieb ohne Benutzer: Verzeichnisse stehen in den Modulen Git, Build und "
                        + "Code-Graph – git_list_repositories bzw. build_list_projects verwenden.";
            }
            List<Project.Visible> visible = projects.visible(Long.parseLong(scope.userId().get()));
            if (visible.isEmpty()) {
                return "Keine Projekte. Projekte legst du in der Web-UI unter „Projekte“ an oder lässt sie dir "
                        + "freigeben.";
            }
            StringBuilder sb = new StringBuilder();
            for (Project.Visible v : visible) {
                Project p = v.project();
                sb.append(v.toolName()).append("  [").append(v.access().label()).append("]  ").append(p.root());
                List<String> found = detect(p.root());
                if (!found.isEmpty()) {
                    sb.append("  (").append(String.join(", ", found)).append(')');
                }
                sb.append('\n');
                if (p.description() != null) {
                    sb.append("    ").append(p.description()).append('\n');
                }
                if (p.sonarKey() != null) {
                    sb.append("    Sonar: ").append(p.sonarKey()).append('\n');
                }
                if (p.ticketProject() != null) {
                    sb.append("    Tickets: ").append(p.ticketProject()).append('\n');
                }
            }
            return sb.toString().strip();
        }

        private static List<String> detect(Path root) {
            List<String> out = new ArrayList<>();
            if (!Files.isDirectory(root)) {
                out.add("Verzeichnis fehlt");
                return out;
            }
            if (Files.exists(root.resolve(".git"))) {
                out.add("Git");
            }
            if (Files.exists(root.resolve("build.gradle")) || Files.exists(root.resolve("build.gradle.kts"))) {
                out.add("Gradle");
            }
            if (Files.exists(root.resolve("pom.xml"))) {
                out.add("Maven");
            }
            return out;
        }
    }
}
