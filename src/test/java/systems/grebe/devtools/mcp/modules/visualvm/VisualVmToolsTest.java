package systems.grebe.devtools.mcp.modules.visualvm;

import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.modules.java.JavaEnvironment;
import systems.grebe.devtools.mcp.modules.java.JvmTarget;
import systems.grebe.devtools.mcp.testjvm.Fixture;

import static org.assertj.core.api.Assertions.assertThat;

/** VisualVM-Engines gegen eine echte Fixture-JVM: Heap-Analyse (Leck finden) und CPU-Sampler über JMX. */
class VisualVmToolsTest {

    static Path artifacts;

    static Fixture fixture;
    static JavaEnvironment env;
    static VisualVmTools tools;

    @BeforeAll
    static void start() throws Exception {
        // kein @TempDir: die Heap-Engine hält ihren Index-Cache (memory-mapped) bis zum Ende der Test-JVM offen
        artifacts = Files.createTempDirectory("devtools-mcp-visualvm-test");
        fixture = Fixture.start(true, false, false);
        env = fixture.environment(artifacts);
        tools = new VisualVmTools(() -> env, ModuleConfig.of(new VisualVmModule(null, null).configSchema(), Map.of()));
    }

    @AfterAll
    static void stop() {
        fixture.close();
    }

    @Test
    void heapAnalysisFindsTheLeakAndItsRoot() {
        JvmTarget t = env.target(null);
        Path dump = t.heapDump(true, env.artifacts());
        String out = tools.heapAnalyze(dump.getFileName().toString(), null, 10, 5, null);
        assertThat(out).contains("Lebende Objekte").contains("byte[]");
        // das Leck: HashMap an statischem Feld FixtureApp.CACHE hält ~30 MB
        assertThat(out).contains("java.util.HashMap#").contains("static systems.grebe.devtools.mcp.testjvm.FixtureApp.CACHE");

        String filtered = tools.heapAnalyze(null, "testjvm", 5, 0, null);
        assertThat(filtered).contains("FixtureApp").doesNotContain("größte Objekte nach zurückgehaltenem");

        String hashMap = out.lines().filter(l -> l.contains("java.util.HashMap#")).findFirst().orElseThrow().trim();
        String spec = hashMap.substring(hashMap.indexOf("java.util.HashMap#")).split("\\s")[0];
        String detail = tools.heapAnalyze(null, null, null, null, spec);
        assertThat(detail).contains("Felder:").contains("table =").contains("Pfad zur GC-Wurzel");
    }

    @Test
    void cpuSamplerOverJmxFindsHotMethodAndWritesNps() throws Exception {
        String out = tools.sampleCpu(null, 3, null);
        assertThat(out).contains("VisualVM-Sampler").contains("hotMethod").contains("Snapshot:");
        assertThat(out).doesNotContain("Net.accept").doesNotContain("dumpThreads0");
        Path nps = env.artifacts().latest("nps", null);
        try (DataInputStream in = new DataInputStream(Files.newInputStream(nps))) {
            byte[] magic = new byte[10];
            in.readFully(magic);
            assertThat(new String(magic)).isEqualTo("nBpRoFiLeR");
            assertThat(in.readByte()).isEqualTo((byte) 1);
            assertThat(in.readByte()).isEqualTo((byte) 2);
            assertThat(in.readInt()).isEqualTo(1);
        }
        assertThat(tools.sampleCpu("jmx:fixture", 2, "testjvm")).contains("hotMethod");
    }

    @Test
    void launcherRequiresConfiguration() {
        var off = ModuleConfig.of(new VisualVmModule(null, null).configSchema(), Map.of(VisualVmModule.AUTO_DOWNLOAD, "false"));
        var launcher = new VisualVmLauncher(off, Path.of(System.getProperty("java.home")));
        org.assertj.core.api.Assertions.assertThatThrownBy(launcher::executable).hasMessageContaining("nicht konfiguriert");
        assertThat(List.of(".hprof", ".jfr")).isNotEmpty();
    }
}
