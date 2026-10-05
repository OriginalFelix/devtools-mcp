package systems.grebe.devtools.mcp.modules.build;

import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.Text;
import systems.grebe.devtools.mcp.modules.build.BuildModule.BuildTool;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.Workspaces;

/** Build-Tools für Gradle und Maven. */
public class BuildTools {

    private static final String PROJECT_PARAM = "Projektname (Ordnername) oder Pfad; leer = Standardprojekt";
    private static final Pattern ERROR_LINE = Pattern.compile(
            "(?i)(^\\[ERROR]|: error:|^e: |FAILED$|FAILURE:|BUILD FAILED|What went wrong|Compilation failure|"
                    + "^> |Exception|\\bTests? run:.*Fail)");
    private static final int MAX_FAILURES = 25;
    private static final int STACK_LINES = 12;

    private final BuildRunner runner;

    BuildTools(BuildRunner runner) {
        this.runner = runner;
    }

    @Tool(name = "list_projects", description = "Listet die freigegebenen Build-Projekte mit erkanntem Build-Werkzeug und die erlaubten Tasks." + ShellHints.BUILD)
    public String listProjects() {
        StringBuilder sb = new StringBuilder();
        runner.projects().all().forEach((name, dir) -> sb.append(name).append("  [")
                .append(BuildTool.detect(dir).label()).append("]  ").append(dir).append('\n'));
        if (sb.isEmpty() && !Workspaces.unrestricted()) {
            return "Keine Projekte konfiguriert.";
        }
        String hint = runner.projects().unrestrictedHint();
        if (!hint.isEmpty()) {
            sb.append(hint).append('\n');
        }
        sb.append("\nErlaubte Tasks/Goals: ")
                .append(runner.allowedTasks().isEmpty() ? "alle" : String.join(", ", runner.allowedTasks()));
        return sb.toString();
    }

    @Tool(name = "run", description = "Führt Gradle-Tasks bzw. Maven-Goals aus (z.B. ['clean','build'] oder [':core:compileJava']). "
            + "Liefert Exit-Code, Dauer, Fehlerzeilen (Compiler, fehlgeschlagene Tasks) und das Ende des Logs."
            + " Statt `./gradlew <task>`/`mvn <goal>` verwenden." + ShellHints.BUILD)
    public String run(
            @ToolParam(required = false, description = PROJECT_PARAM) String project,
            @ToolParam(description = "Tasks/Goals in Ausführungsreihenfolge") List<String> tasks,
            @ToolParam(required = false, description = "Zusätzliche Argumente, z.B. ['--offline','-x','test'] oder ['-DskipTests']") List<String> args) {
        BuildRunner.Result r = runner.run(project, tasks, args);
        return format(r, null);
    }

    @Tool(name = "test", description = "Führt Tests aus (Gradle 'test' bzw. Maven 'test') – optional gefiltert auf Klassen/Methoden – "
            + "und liefert eine Zusammenfassung mit Meldung und Stacktrace-Auszug jedes fehlgeschlagenen Tests."
            + " Statt `./gradlew test --tests …`/`mvn -Dtest=…` verwenden." + ShellHints.BUILD)
    public String test(
            @ToolParam(required = false, description = PROJECT_PARAM) String project,
            @ToolParam(required = false, description = "Testfilter, z.B. 'com.acme.FooTest' oder 'com.acme.FooTest.bar' (Gradle) / 'FooTest#bar' (Maven)") String filter,
            @ToolParam(required = false, description = "Gradle-Unterprojekt, z.B. ':core' (nur Gradle)") String subproject,
            @ToolParam(required = false, description = "Zusätzliche Argumente") List<String> args) {
        BuildTool tool = BuildTool.detect(runner.resolve(project));
        List<String> tasks = new ArrayList<>();
        List<String> extra = new ArrayList<>(args == null ? List.of() : args);
        if (tool == BuildTool.GRADLE) {
            String prefix = subproject == null || subproject.isBlank() ? "" : (subproject.startsWith(":") ? subproject : ":" + subproject) + ":";
            tasks.add(prefix + "test");
            if (filter != null && !filter.isBlank()) {
                extra.add("--tests");
                extra.add(filter.trim());
            }
        } else {
            tasks.add("test");
            if (filter != null && !filter.isBlank()) {
                extra.add("-Dtest=" + filter.trim());
                extra.add("-Dsurefire.failIfNoSpecifiedTests=false");
            }
        }
        FileTime started = FileTime.from(Instant.now().minusSeconds(2));
        BuildRunner.Result r = runner.run(project, tasks, extra);
        return format(r, TestReports.read(r.project(), started));
    }

    @Tool(name = "test_report", description = "Wertet die zuletzt geschriebenen JUnit-Testberichte eines Projekts aus, ohne erneut zu bauen."
            + " Statt selbst JUnit-XML unter build/test-results zu lesen verwenden." + ShellHints.BUILD)
    public String testReport(@ToolParam(required = false, description = PROJECT_PARAM) String project) {
        TestReports.Summary s = TestReports.read(runner.resolve(project), null);
        if (s.files() == 0) {
            return "Keine Testberichte gefunden (build/test-results bzw. target/surefire-reports).";
        }
        return formatTests(s);
    }

    // ------------------------------------------------------------------ Formatierung

    private String format(BuildRunner.Result r, TestReports.Summary tests) {
        StringBuilder sb = new StringBuilder();
        sb.append(r.success() ? "BUILD ERFOLGREICH" : r.timedOut() ? "BUILD ABGEBROCHEN (Timeout)" : "BUILD FEHLGESCHLAGEN")
                .append(" – Exit-Code ").append(r.exitCode())
                .append(", Dauer ").append(r.duration().toSeconds()).append(" s\n");
        sb.append("Projekt: ").append(r.project()).append(" [").append(r.tool().label()).append("]\n");
        sb.append("Befehl:  ").append(String.join(" ", r.command())).append('\n');

        if (tests != null && tests.files() > 0) {
            sb.append('\n').append(formatTests(tests)).append('\n');
        }

        int budget = runner.maxLines();
        if (!r.success()) {
            List<String> errors = r.output().stream().filter(l -> ERROR_LINE.matcher(l).find()).distinct().toList();
            if (!errors.isEmpty()) {
                int n = Math.min(errors.size(), budget / 2);
                sb.append("\nFehlerzeilen (").append(errors.size()).append("):\n")
                        .append(String.join("\n", errors.subList(0, n))).append('\n');
                budget -= n;
            }
        }
        int tail = r.success() ? Math.min(budget, 30) : budget;
        sb.append("\nLog (Ende):\n").append(Text.tailLines(r.output(), tail));
        return sb.toString();
    }

    private static String formatTests(TestReports.Summary s) {
        StringBuilder sb = new StringBuilder();
        sb.append("Tests: ").append(s.tests()).append(" gesamt, ").append(s.failures()).append(" fehlgeschlagen, ")
                .append(s.errors()).append(" Fehler, ").append(s.skipped()).append(" übersprungen (")
                .append(String.format("%.1f", s.seconds())).append(" s, ").append(s.files()).append(" Berichte)");
        int shown = 0;
        for (TestReports.Failure f : s.failed()) {
            if (shown++ >= MAX_FAILURES) {
                sb.append("\n… ").append(s.failed().size() - MAX_FAILURES).append(" weitere Fehlschläge");
                break;
            }
            sb.append("\n\n✗ ").append(f.testClass()).append('.').append(f.testName()).append(" (").append(f.kind()).append(")\n");
            if (f.message() != null && !f.message().isBlank()) {
                sb.append("  ").append(Text.limitLines(f.message().trim(), 8)).append('\n');
            }
            if (f.stackTrace() != null && !f.stackTrace().isBlank()) {
                sb.append(Text.limitLines(f.stackTrace().strip(), STACK_LINES).indent(4));
            }
        }
        return sb.toString().stripTrailing();
    }
}
