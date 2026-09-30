package systems.grebe.devtools.mcp.modules.debug;

import java.nio.file.Path;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import systems.grebe.devtools.mcp.modules.java.JavaEnvironment;
import systems.grebe.devtools.mcp.testjvm.Fixture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** JDI-Debugger gegen eine echte, per JDWP gestartete Fixture-JVM. */
class DebugToolsTest {

    @TempDir
    static Path artifacts;

    static Fixture fixture;
    static JavaEnvironment env;
    static DebugSessions sessions = new DebugSessions();
    static DebugTools tools;

    /** Zeile "int counter = round * 2;" in FixtureApp.tick(). */
    static int counterLine;

    @BeforeAll
    static void start() throws Exception {
        fixture = Fixture.start(false, true, false);
        env = fixture.environment(artifacts);
        tools = new DebugTools(() -> env, sessions, 60, 2);
        var src = java.nio.file.Files.readAllLines(Path.of("src/test/java/systems/grebe/devtools/mcp/testjvm/FixtureApp.java"));
        counterLine = src.indexOf(src.stream().filter(l -> l.contains("int counter = round * 2;")).findFirst().orElseThrow()) + 1;
    }

    @AfterAll
    static void stop() {
        sessions.closeAll();
        fixture.close();
    }

    @Test
    void breakpointStackVariablesStepResumeDetach() {
        assertThatThrownBy(() -> tools.attach("example.com", 5005)).hasMessageContaining("nicht freigegeben");

        String attach = tools.attach("127.0.0.1", fixture.jdwpPort);
        assertThat(attach).contains("Sitzung s").contains("verbunden");

        String bp = tools.setBreakpoint(null, "systems.grebe.devtools.mcp.testjvm.FixtureApp", counterLine);
        assertThat(bp).contains("aktiv");

        String hit = tools.waitForBreak(null, 10);
        assertThat(hit).contains("Breakpoint").contains("fixture-ticker").contains("FixtureApp.tick(FixtureApp.java:" + counterLine + ")")
                .contains("round = ");

        String step = tools.step(null, null, "over");
        assertThat(step).contains("Schritt").contains("counter = ");
        int round = Integer.parseInt(step.lines().filter(l -> l.startsWith("round = ")).findFirst().orElseThrow().substring(8).trim());
        assertThat(step).contains("counter = " + (round * 2));

        assertThat(tools.stack(null, null, 5)).contains("#0 systems.grebe.devtools.mcp.testjvm.FixtureApp.tick");
        assertThat(tools.threads(null)).contains("⏸ fixture-ticker");
        assertThat(tools.sessionsList()).contains("angehalten: fixture-ticker");

        assertThat(tools.clearBreakpoint(null, 1)).contains("entfernt");
        assertThat(tools.resume(null, null, null)).contains("fortgesetzt");
        assertThat(tools.waitForBreak(null, 1)).contains("kein Treffer");
        assertThat(tools.detach(null)).contains("getrennt");
        assertThat(sessions.all()).isEmpty();
    }

    @Test
    void breakpointOnNotYetLoadedClassIsDeferred() {
        tools.attach("127.0.0.1", fixture.jdwpPort);
        try {
            assertThat(tools.setBreakpoint(null, "com.example.NochNichtGeladen", 10)).contains("noch nicht geladen");
        } finally {
            tools.detach(null);
        }
    }
}
