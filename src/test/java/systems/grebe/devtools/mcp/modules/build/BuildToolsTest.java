package systems.grebe.devtools.mcp.modules.build;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import systems.grebe.devtools.mcp.core.ModuleConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BuildToolsTest {

    @TempDir
    Path parent;

    private BuildTools tools(Path projects, Map<String, String> extra) {
        var values = new java.util.HashMap<>(extra);
        values.put(BuildModule.PROJECTS, projects.toString());
        return new BuildTools(new BuildRunner(ModuleConfig.of(new BuildModule().configSchema(), values)));
    }

    @Test
    void detectsProjectsAndRejectsUnsafeInput() throws Exception {
        Path gradle = Files.createDirectory(parent.resolve("app"));
        Files.writeString(gradle.resolve("build.gradle.kts"), "");
        Path maven = Files.createDirectory(parent.resolve("lib"));
        Files.writeString(maven.resolve("pom.xml"), "<project/>");
        Files.createDirectory(parent.resolve("docs"));

        BuildTools t = tools(parent, Map.of());
        assertThat(t.listProjects()).contains("app  [Gradle]").contains("lib  [Maven]").doesNotContain("docs");

        assertThatThrownBy(() -> t.run("app", List.of("publish"), null)).hasMessageContaining("nicht freigegeben");
        assertThatThrownBy(() -> t.run("app", List.of("build"), List.of("&", "calc"))).hasMessageContaining("Unzulässiges Argument");
        assertThatThrownBy(() -> t.run(null, List.of("build"), null)).hasMessageContaining("Mehrere");
        assertThat(BuildRunner.taskName(":core:test")).isEqualTo("test");
    }

    @Test
    void readsJunitReports() throws Exception {
        Path project = Files.createDirectory(parent.resolve("app"));
        Files.writeString(project.resolve("build.gradle"), "");
        Path results = Files.createDirectories(project.resolve("build/test-results/test"));
        Files.writeString(results.resolve("TEST-com.acme.FooTest.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.acme.FooTest" tests="3" skipped="1" failures="1" errors="0" time="0.42">
                  <testcase name="ok" classname="com.acme.FooTest" time="0.01"/>
                  <testcase name="kaputt" classname="com.acme.FooTest" time="0.02">
                    <failure message="expected: &lt;1&gt; but was: &lt;2&gt;" type="org.opentest4j.AssertionFailedError">org.opentest4j.AssertionFailedError: expected: &lt;1&gt; but was: &lt;2&gt;
                	at com.acme.FooTest.kaputt(FooTest.java:17)</failure>
                  </testcase>
                  <testcase name="skip" classname="com.acme.FooTest"><skipped/></testcase>
                </testsuite>""");
        String report = tools(project, Map.of()).testReport(null);
        assertThat(report).contains("Tests: 3 gesamt, 1 fehlgeschlagen, 0 Fehler, 1 übersprungen")
                .contains("✗ com.acme.FooTest.kaputt (failure)")
                .contains("expected: <1> but was: <2>")
                .contains("FooTest.java:17");
    }

    /** Führt echt einen Gradle-Build dieses Projekts aus (Wrapper vorhanden, offline). */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void runsRealGradleWrapper() {
        Path self = Path.of("").toAbsolutePath();
        BuildTools t = tools(self, Map.of(BuildModule.EXTRA_ARGS, "--offline -q", BuildModule.JAVA_HOME,
                System.getProperty("java.home")));
        String out = t.run(null, List.of("tasks"), null);
        assertThat(out).startsWith("BUILD ERFOLGREICH").contains("Exit-Code 0").contains("gradlew.bat");
        assertThatThrownBy(() -> t.run(null, List.of("help"), null)).hasMessageContaining("nicht freigegeben");
    }
}
