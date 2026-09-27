package systems.grebe.devtools.mcp.modules.build;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.ConnectionTestResult;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolModule;
import systems.grebe.devtools.mcp.core.Workspaces;

/** Führt Gradle- und Maven-Builds in freigegebenen Projekten aus und wertet Testberichte aus. */
@Component
public class BuildModule implements ToolModule {

    static final String PROJECTS = "projects";
    static final String DEFAULT_PROJECT = "defaultProject";
    static final String ALLOWED_TASKS = "allowedTasks";
    static final String JAVA_HOME = "javaHome";
    static final String EXTRA_ARGS = "extraArgs";
    static final String TIMEOUT = "timeoutMinutes";
    static final String MAX_LINES = "maxOutputLines";

    @Override
    public String id() {
        return "build";
    }

    @Override
    public String displayName() {
        return "Build (Gradle/Maven)";
    }

    @Override
    public String description() {
        return "Startet Gradle-/Maven-Tasks (z.B. build, test, compileJava) in freigegebenen Projekten und liefert "
                + "kompakte Ergebnisse inkl. Compiler-Fehlern und fehlgeschlagener Tests.";
    }

    @Override
    public List<ConfigField> configSchema() {
        return List.of(
                ConfigField.of(PROJECTS, "Projekte", FieldType.DIRECTORY_LIST).asRequired()
                        .withHelp("Projektverzeichnisse oder Sammelordner (Unterordner mit build.gradle(.kts)/pom.xml werden übernommen)."),
                ConfigField.of(DEFAULT_PROJECT, "Standardprojekt", FieldType.STRING)
                        .withHelp("Ordnername des Projekts, das ohne Angabe verwendet wird."),
                ConfigField.of(ALLOWED_TASKS, "Erlaubte Tasks/Goals", FieldType.STRING_LIST)
                        .withDefault("clean\nbuild\nassemble\ncompileJava\ncompileTestJava\ntest\ncheck\ndependencies\ntasks\n"
                                + "compile\ntest-compile\npackage\nverify\ndependency:tree")
                        .withHelp("Eine Zeile je Task/Goal. Leer = alle erlaubt. Projekt-Präfixe (':modul:test') werden auf den Tasknamen geprüft."),
                ConfigField.of(JAVA_HOME, "JAVA_HOME", FieldType.DIRECTORY)
                        .withHelp("Optional: JDK für die Builds. Leer = JAVA_HOME der Umgebung."),
                ConfigField.of(EXTRA_ARGS, "Zusätzliche Argumente", FieldType.STRING)
                        .withHelp("Werden jedem Aufruf vorangestellt, z.B. --offline oder -q"),
                ConfigField.of(TIMEOUT, "Timeout (Minuten)", FieldType.INT).withDefault("10"),
                ConfigField.of(MAX_LINES, "Max. Ausgabezeilen", FieldType.INT).withDefault("200")
                        .withHelp("Vom Build-Log werden Fehlerzeilen und die letzten Zeilen bis zu dieser Anzahl geliefert."));
    }

    @Override
    public List<ToolCallback> createTools(ModuleConfig config) {
        return List.of(ToolCallbacks.from(new BuildTools(new BuildRunner(config))));
    }

    @Override
    public ConnectionTestResult testConnection(ModuleConfig config) {
        List<String> errors = config.validate();
        if (!errors.isEmpty()) {
            return ConnectionTestResult.failed(String.join("\n", errors));
        }
        Workspaces projects = new BuildRunner(config).projects();
        if (projects.isEmpty()) {
            return ConnectionTestResult.failed("Keine Gradle-/Maven-Projekte gefunden.");
        }
        StringBuilder sb = new StringBuilder(projects.all().size() + " Projekt(e) gefunden:\n");
        projects.all().forEach((name, dir) -> sb.append(name).append(" [").append(BuildTool.detect(dir).label())
                .append("] ").append(dir).append('\n'));
        return ConnectionTestResult.ok(sb.toString().trim());
    }

    static boolean isProject(Path dir) {
        return BuildTool.detect(dir) != BuildTool.NONE;
    }

    /** Erkanntes Build-Werkzeug. */
    enum BuildTool {
        GRADLE("Gradle"), MAVEN("Maven"), NONE("-");

        private final String label;

        BuildTool(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }

        static BuildTool detect(Path dir) {
            if (Files.exists(dir.resolve("build.gradle")) || Files.exists(dir.resolve("build.gradle.kts"))
                    || Files.exists(dir.resolve("settings.gradle")) || Files.exists(dir.resolve("settings.gradle.kts"))) {
                return GRADLE;
            }
            if (Files.exists(dir.resolve("pom.xml"))) {
                return MAVEN;
            }
            return NONE;
        }
    }
}
