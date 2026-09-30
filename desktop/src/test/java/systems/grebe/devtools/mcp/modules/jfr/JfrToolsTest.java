package systems.grebe.devtools.mcp.modules.jfr;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import systems.grebe.devtools.mcp.modules.java.JavaEnvironment;
import systems.grebe.devtools.mcp.testjvm.Fixture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** jfr_* gegen eine echte Fixture-JVM: Aufzeichnen, Auswerten, Flame Graph, Übertragung über JMX. */
class JfrToolsTest {

    @TempDir
    static Path artifacts;

    static Fixture fixture;
    static JavaEnvironment env;
    static JfrTools tools;

    @BeforeAll
    static void start() {
        fixture = Fixture.start(true, false, false);
        env = fixture.environment(artifacts);
        tools = new JfrTools(() -> env, "profile", 60);
    }

    @AfterAll
    static void stop() {
        fixture.close();
    }

    @Test
    void recordAnalyzeAndFlameGraphLocal() throws Exception {
        String rec = tools.record(null, 4, null);
        assertThat(rec).contains("Aufzeichnung von lokale JVM").contains("CPU-Samples").contains("FixtureApp.hotMethod");
        Path file = env.artifacts().latest("jfr", null);
        assertThat(Files.size(file)).isPositive();

        assertThat(tools.analyze(null, "allocation", null, 10)).contains("Allokiert");
        assertThat(tools.analyze(null, "gc", null, 10)).contains("Garbage Collections");
        assertThat(tools.analyze(null, "cpu", "systems.grebe", 5)).contains("hotMethod").contains("nur Stacks mit systems.grebe");
        assertThat(tools.analyze(null, "threads", null, 5)).contains("fixture-burner");
        assertThat(tools.analyze(file.getFileName().toString(), null, null, null)).contains("Ereignisse");
        assertThatThrownBy(() -> tools.analyze(null, "quatsch", null, 5)).hasMessageContaining("Unbekannter Aspekt");
        assertThatThrownBy(() -> tools.analyze("../x.jfr", null, null, 5)).hasMessageContaining("außerhalb");

        String flame = tools.flamegraph(null, "cpu", null, null);
        assertThat(flame).contains("Flame Graph:").contains("-flamegraph.html").contains("hotMethod");
        Path html = env.artifacts().latest("html", "flamegraph");
        assertThat(Files.readString(html)).contains("hotMethod");
        assertThat(tools.flamegraph(file.getFileName().toString(), "alloc", null, null)).contains("Allokationen-Profil");
    }

    @Test
    void recordingOverJmxIsStreamed() throws Exception {
        assertThat(tools.start("jmx:fixture", "remote", null, null)).contains("Started recording");
        Thread.sleep(3000);
        assertThat(tools.status("jmx:fixture")).contains("remote");
        String dump = tools.dump("jmx:fixture", "remote");
        assertThat(dump).contains("jmx-fixture-remote").contains("Ereignisse");
        assertThat(tools.stop("jmx:fixture", "remote")).contains("Stopped");
        assertThatThrownBy(() -> tools.start(null, "bad name!", null, null)).hasMessageContaining("Ungültiger");
    }
}
