package systems.grebe.devtools.mcp.modules.container;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Container-Tools gegen eine echte Laufzeit (podman oder docker – was erreichbar ist). Übersprungen, wenn keine
 * Laufzeit mit dem Image nginx:alpine verfügbar ist.
 */
class ContainerRuntimeIntegrationTest {

    static final String IMAGE = "docker.io/library/nginx:alpine";
    static final String NAME = "devtools-mcp-it-web";

    @TempDir
    static Path shared;

    static String runtimeId;
    static ContainerEnvironment env;

    @BeforeAll
    static void setUp() {
        ContainerRuntimes runtimes = new ContainerRuntimes();
        ContainerModule module = new ContainerModule(runtimes);
        for (String id : List.of("podman", "docker")) {
            ContainerEnvironment e = new ContainerEnvironment(runtimes, ModuleConfig.of(module.configSchema(), Map.of()));
            if (e.availability(id, true).available()
                    && CommandRunner.run(List.of(id, "image", "inspect", IMAGE), Duration.ofSeconds(20)).ok()) {
                runtimeId = id;
                break;
            }
        }
        Assumptions.assumeTrue(runtimeId != null, "Keine Container-Laufzeit mit " + IMAGE);
        CommandRunner.run(List.of(runtimeId, "rm", "-f", NAME), Duration.ofSeconds(30));
        env = new ContainerEnvironment(runtimes, ModuleConfig.of(module.configSchema(), Map.of(
                "defaultRuntime", runtimeId,
                "allowedContainers", "devtools-mcp-it-.*",
                "allowedImages", "(docker\\.io/library/)?nginx:alpine",
                "hostDirectories", shared.toString(),
                "execAllowlist", "cat\nsh\nls")));
    }

    @AfterAll
    static void tearDown() {
        if (runtimeId != null) {
            CommandRunner.run(List.of(runtimeId, "rm", "-f", NAME), Duration.ofSeconds(30));
        }
    }

    @Test
    void fullLifecycleAgainstRealRuntime() throws Exception {
        var create = new ContainerCreateTools(env);
        var read = new ContainerReadTools(env);
        var exec = new ContainerExecTools(env);
        var life = new ContainerLifecycleTools(env);
        var copy = new ContainerCopyTools(env);
        var remove = new ContainerRemoveTools(env);

        assertThat(read.runtimes()).contains(runtimeId + " (").contains("verfügbar");

        String run = create.run(IMAGE, NAME, List.of("APP_MODE=test", "DB_PASSWORD=geheim"), List.of("80"), null, null, null, null);
        assertThat(run).contains("gestartet").contains("127.0.0.1::80");

        assertThat(read.list(false, "it-web", null)).contains(NAME).contains("[running]").contains("nginx:alpine");
        String inspect = read.inspect(NAME, null, null);
        assertThat(inspect).contains("Zustand: running").contains("DB_PASSWORD=***").doesNotContain("geheim")
                .contains("APP_MODE=test").contains("devtools-mcp=true").contains("80/tcp -> 127.0.0.1:");

        Thread.sleep(1500);
        assertThat(read.logs(NAME, 50, null, null, null, null)).containsIgnoringCase("start");
        assertThat(read.stats(NAME, null)).contains(NAME);
        assertThat(read.top(NAME, null)).contains("nginx");
        assertThat(read.images("nginx", null)).contains("nginx");
        assertThat(read.networks(null)).isNotBlank();

        String out = exec.exec(NAME, List.of("cat", "/etc/nginx/nginx.conf"), null, null, null, null);
        assertThat(out).startsWith("Exit-Code 0").contains("worker_processes");
        assertThat(exec.exec(NAME, List.of("sh", "-c", "exit 3"), null, null, null, null)).startsWith("Exit-Code 3");

        Path local = shared.resolve("hallo.txt");
        Files.writeString(local, "hallo aus dem Host");
        copy.copyTo(NAME, local.toString(), "/tmp/hallo.txt", null);
        assertThat(exec.exec(NAME, List.of("cat", "/tmp/hallo.txt"), null, null, null, null)).contains("hallo aus dem Host");
        copy.copyFrom(NAME, "/etc/nginx/nginx.conf", shared.resolve("nginx.conf").toString(), null);
        assertThat(Files.readString(shared.resolve("nginx.conf"))).contains("worker_processes");
        assertThat(read.diff(NAME, null)).contains("/tmp/hallo.txt");

        assertThat(life.stop(NAME, 2, null)).contains("gestoppt");
        assertThat(read.list(true, "it-web", null)).contains("[exited]");
        assertThat(life.start(NAME, null)).contains("gestartet");
        assertThat(read.list(false, "it-web", null)).contains("[running]");

        assertThat(remove.rm(NAME, true, null, null)).contains("gelöscht");
        assertThat(read.list(true, "it-web", null)).contains("Keine");
    }

    @Test
    void composeAgainstRealRuntime() throws Exception {
        ContainerRuntimes runtimes = new ContainerRuntimes();
        Path project = Files.createDirectories(shared.resolve("devtools-mcp-it-compose"));
        Files.writeString(project.resolve("compose.yaml"), """
                services:
                  web:
                    image: docker.io/library/nginx:alpine
                """);
        ContainerModule module = new ContainerModule(runtimes);
        ContainerEnvironment e = new ContainerEnvironment(runtimes, ModuleConfig.of(module.configSchema(), Map.of(
                "defaultRuntime", runtimeId, "composeProjects", project.toString())));
        Assumptions.assumeTrue(e.runtime(null).supportsCompose(), "Compose nicht verfügbar");
        var read = new ComposeReadTools(e);
        var write = new ComposeWriteTools(e);
        try {
            assertThat(read.projects()).contains("devtools-mcp-it-compose");
            assertThat(read.config(null, null)).contains("nginx:alpine");
            String up = write.up(null, null, null, null);
            assertThat(up).doesNotContain("Fehler");
            assertThat(read.ps(null, null)).contains("web");
            assertThat(read.logs(null, "web", 20, null)).doesNotStartWith("Fehler");
        } finally {
            assertThat(write.down(null, null, null)).doesNotContain("Fehler");
        }
    }
}
