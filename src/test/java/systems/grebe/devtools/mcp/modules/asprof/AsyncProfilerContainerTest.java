package systems.grebe.devtools.mcp.modules.asprof;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import systems.grebe.devtools.mcp.core.CommandRunner;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.modules.java.JavaEnvironment;
import systems.grebe.devtools.mcp.modules.java.JavaSettingsModule;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * async-profiler in einem echten Linux-Container (docker oder podman). Übersprungen, wenn keine
 * Container-Laufzeit oder kein Image {@code eclipse-temurin:25-jdk} verfügbar ist.
 */
class AsyncProfilerContainerTest {

    static final String IMAGE = "docker.io/library/eclipse-temurin:25-jdk";
    static final String NAME = "devtools-mcp-asprof-test";

    @TempDir
    static Path artifacts;

    static String cli;
    static AsyncProfilerTools tools;
    static JavaEnvironment env;

    @BeforeAll
    static void startContainer() throws Exception {
        for (String c : List.of("docker", "podman")) {
            try {
                if (CommandRunner.run(List.of(c, "image", "exists", IMAGE), Duration.ofSeconds(20)).ok()
                        || CommandRunner.run(List.of(c, "image", "inspect", IMAGE), Duration.ofSeconds(20)).ok()) {
                    cli = c;
                    break;
                }
            } catch (RuntimeException ignored) {
                // nicht installiert
            }
        }
        Assumptions.assumeTrue(cli != null, "Keine Container-Laufzeit mit Image " + IMAGE);
        CommandRunner.run(List.of(cli, "rm", "-f", NAME), Duration.ofSeconds(30));
        String src = """
                public class Load {
                  static volatile Object sink;
                  static double hot(int n){ double s=0; for(int i=1;i<n;i++) s+=Math.sqrt(i)*Math.log(i); return s; }
                  public static void main(String[] a) throws Exception { double t=0; while(true){ t+=hot(100000); sink=new byte[64*1024]; if(t<0) System.out.println(t);} }
                }
                """;
        Path file = Files.createTempDirectory("load").resolve("Load.java");
        Files.writeString(file, src);
        // Container wartet, bis die Quelldatei da ist, und startet dann die Last-JVM
        CommandRunner.run(List.of(cli, "run", "-d", "--name", NAME, IMAGE, "sh", "-c",
                "while [ ! -f /tmp/Load.java ]; do sleep 0.2; done; exec java /tmp/Load.java"), Duration.ofMinutes(2))
                .orThrow("Container starten");
        CommandRunner.run(List.of(cli, "cp", file.toString(), NAME + ":/tmp/Load.java"), Duration.ofMinutes(1))
                .orThrow("Quelle kopieren");
        Thread.sleep(8000);

        env = new JavaEnvironment(ModuleConfig.of(new JavaSettingsModule().configSchema(), Map.of(
                "artifactDir", artifacts.toString(), "containerCli", cli, "allowedContainers", "devtools-mcp-.*")));
        var installer = new AsprofInstaller(ModuleConfig.of(new AsyncProfilerModule(null).configSchema(), Map.of()));
        tools = new AsyncProfilerTools(() -> env, installer, 60);
    }

    @AfterAll
    static void removeContainer() {
        if (cli != null) {
            CommandRunner.run(List.of(cli, "rm", "-f", NAME), Duration.ofSeconds(30));
        }
    }

    @Test
    void profilesJvmInsideContainer() throws Exception {
        String out = tools.profile("container:" + NAME, "cpu", 3, null, null);
        assertThat(out).contains("JVM").contains("im Container " + NAME).contains("Load.hot").contains("Flame Graph:");
        assertThat(env.artifacts().latest("html", "flamegraph")).exists();
        assertThat(env.artifacts().latest("jfr", null)).exists();

        String alloc = tools.profile("container:" + NAME, "alloc", 2, null, null);
        assertThat(alloc).contains("Allokationen-Profil");

        assertThat(tools.start("container:" + NAME, "cpu")).contains("gestartet");
        Thread.sleep(2000);
        assertThat(tools.stop("container:" + NAME, "cpu", null)).contains("Load.hot");
        assertThat(tools.status("container:" + NAME)).contains("cpu");
    }

    @Test
    void jcmdViaContainerTarget() {
        var t = env.target("container:" + NAME);
        assertThat(t.dcmd("VM.version")).contains("OpenJDK");
        assertThat(env.containers().running()).extracting(c -> c.name()).contains(NAME);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> env.target("container:anderer"))
                .hasMessageContaining("nicht freigegeben");
    }
}
