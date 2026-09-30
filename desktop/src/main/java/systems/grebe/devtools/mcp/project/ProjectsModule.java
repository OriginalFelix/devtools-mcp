package systems.grebe.devtools.mcp.project;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.api.ProjectInfo;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.ToolBeans;
import systems.grebe.devtools.mcp.core.ToolHints;
import systems.grebe.devtools.mcp.core.ToolModule;
import systems.grebe.devtools.mcp.remote.BackendConnection;

/**
 * Projekte aus dem Backend: {@code projects_list} zeigt dem LLM die eigenen und freigegebenen Projekte samt Zugriff,
 * lokalem Verzeichnis, Sonar-Schlüssel und Ticket-Projekt. Angelegt werden Projekte im Tab „Backend“ bzw. in der Web-UI
 * des Team-Servers; das Verzeichnis ordnet jeder in seiner Desktop-App zu.
 */
@Component
public class ProjectsModule implements ToolModule {

    public static final String ID = "projects";

    private final BackendConnection team;

    public ProjectsModule(BackendConnection team) {
        this.team = team;
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
        return "Eigene und freigegebene Projekte aus dem Backend. Git, Build und Code-Graph bekommen die Projekte, "
                + "denen hier ein lokales Verzeichnis zugeordnet ist; Freigaben in der Web-UI des Team-Servers.";
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
    public List<ToolCallback> createTools(ModuleConfig config) {
        return ToolBeans.callbacks(new ProjectTools(team));
    }

    /** Tools des Moduls. */
    @ToolHints(readOnly = true, openWorld = false)
    public static class ProjectTools {

        private final BackendConnection team;

        ProjectTools(BackendConnection team) {
            this.team = team;
        }

        @Tool(name = "list", description = "Listet deine Projekte und die dir freigegebenen mit Zugriff "
                + "(Eigentümer, lesen + schreiben, nur lesen), lokalem Verzeichnis, Sonar-Schlüssel, Ticket-Projekt und "
                + "was darin erkannt wurde (Git, Gradle/Maven)." + ShellHints.PROJECTS)
        public String list() {
            List<ProjectInfo> projects = team.projects();
            if (projects.isEmpty()) {
                return "Keine Projekte. Verzeichnisse stehen in den Modulen Git, Build und Code-Graph – "
                        + "git_list_repositories bzw. build_list_projects verwenden. Projekte legst du in der App unter "
                        + "„Backend“ bzw. in der Web-UI des Team-Servers an.";
            }
            StringBuilder sb = new StringBuilder();
            for (ProjectInfo p : projects) {
                Optional<Path> dir = team.projectPath(p.id());
                sb.append(p.toolName()).append("  [").append(label(p)).append("]  ")
                        .append(dir.map(Path::toString).orElse("(kein lokales Verzeichnis zugeordnet – in der "
                                + "Desktop-App unter Backend → Projekte wählen)"));
                dir.map(ProjectTools::detect).filter(f -> !f.isEmpty())
                        .ifPresent(found -> sb.append("  (").append(String.join(", ", found)).append(')'));
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

        private static String label(ProjectInfo p) {
            return switch (p.access()) {
                case "OWNER" -> "Eigentümer";
                case "WRITE" -> "lesen + schreiben";
                default -> "nur lesen";
            };
        }

        private static List<String> detect(Path root) {
            List<String> out = new ArrayList<>();
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
