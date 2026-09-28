package systems.grebe.devtools.mcp.modules.jvm;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import systems.grebe.devtools.mcp.modules.java.JavaEnvironment;
import systems.grebe.devtools.mcp.modules.java.JvmTarget;
import systems.grebe.devtools.mcp.modules.java.LocalJvms;
import systems.grebe.devtools.mcp.testjvm.Fixture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** jvm_* gegen eine echte Fixture-JVM (mit Deadlock), lokal und über JMX. */
class JvmToolsTest {

    @TempDir
    static Path artifacts;

    static Fixture fixture;
    static JavaEnvironment env;
    static JvmTools tools;
    static JvmInvasiveTools invasive;

    @BeforeAll
    static void start() {
        fixture = Fixture.start(true, false, true);
        env = fixture.environment(artifacts);
        Supplier<JavaEnvironment> s = () -> env;
        tools = new JvmTools(s);
        invasive = new JvmInvasiveTools(s, List.of("VM.uptime", "VM.metaspace"));
    }

    @AfterAll
    static void stop() {
        fixture.close();
    }

    @Test
    void discoversOnlyAllowedProcesses() {
        List<LocalJvms.Jvm> list = env.processes().list();
        assertThat(list).extracting(LocalJvms.Jvm::pid).containsExactly(fixture.pid());
        assertThat(env.target(null)).isInstanceOf(JvmTarget.Local.class);
        assertThat(env.target("FixtureApp").describe()).contains(String.valueOf(fixture.pid()));
        assertThatThrownBy(() -> env.target("gibtsnicht")).hasMessageContaining("Keine freigegebene JVM");
        assertThatThrownBy(() -> env.target("jmx:unbekannt")).hasMessageContaining("nicht konfiguriert");
        assertThat(tools.processes(false)).contains(String.valueOf(fixture.pid())).contains("jmx:fixture");
    }

    @Test
    void infoAndHeapLocal() {
        assertThat(tools.info(null)).contains("## Version").contains("## VM-Flags").contains("NativeMemoryTracking");
        assertThat(tools.heap(null, 10, null)).contains("Klassenhistogramm").contains("[B");
        assertThat(tools.heap(null, 5, "HashMap")).contains("java.util.HashMap");
    }

    @Test
    void threadDumpFindsDeadlock() {
        String out = tools.threads(null, null, null, null, null);
        assertThat(out).contains("DEADLOCK GEFUNDEN").contains("fixture-deadlock-a").contains("BLOCKED");
        assertThat(tools.threads(null, "burner", null, 5, null)).contains("fixture-burner").contains("RUNNABLE");
    }

    @Test
    void nativeMemory() {
        assertThat(tools.nativeMemory(null, null)).contains("Java Heap");
    }

    @Test
    void sameCommandsWorkOverJmx() {
        assertThat(env.target("jmx:fixture")).isInstanceOf(JvmTarget.Remote.class);
        assertThat(tools.info("jmx:fixture")).contains("JVM über JMX fixture").contains("## Version");
        assertThat(tools.threads("jmx:fixture", null, null, null, null)).contains("DEADLOCK GEFUNDEN");
        assertThat(tools.heap("jmx:fixture", 5, null)).contains("Klassenhistogramm");
    }

    @Test
    void invasiveOperations() {
        String out = invasive.heapDump(null, true);
        assertThat(out).contains(".hprof").contains("visualvm_heap_analyze");
        assertThat(invasive.jcmd(null, "VM.uptime", null)).containsPattern("\\d+[.,]\\d+ s");
        assertThatThrownBy(() -> invasive.jcmd(null, "VM.set_flag", null)).hasMessageContaining("nicht freigegeben");
        assertThat(invasive.gcRun(null)).contains("Vorher:").contains("Nachher:");
    }
}
