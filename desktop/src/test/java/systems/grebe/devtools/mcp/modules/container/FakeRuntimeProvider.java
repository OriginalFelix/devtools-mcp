package systems.grebe.devtools.mcp.modules.container;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import systems.grebe.container4j.ContainerRuntime;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.modules.container.spi.ContainerRuntimeProvider;
import systems.grebe.devtools.mcp.modules.container.spi.RuntimeSettings;

/**
 * Test-Laufzeit, die nur über {@code src/test/resources/META-INF/services} registriert ist. Beweist, dass neue
 * Laufzeiten ohne Codeänderung am Modul erkannt, konfiguriert und in den Tools angeboten werden.
 */
public class FakeRuntimeProvider implements ContainerRuntimeProvider {

    @Override
    public String id() {
        return "fake";
    }

    @Override
    public String displayName() {
        return "Fake-Laufzeit";
    }

    @Override
    public int priority() {
        return 900;
    }

    @Override
    public List<ConfigField> configFields() {
        return List.of(ConfigField.of("greeting", "Begrüßung", FieldType.STRING).withDefault("hallo"));
    }

    @Override
    public ContainerRuntime create(RuntimeSettings settings) {
        return new Fake(settings.getString("greeting", "?"));
    }

    /** Liefert feste Daten; merkt sich ausgeführte Aktionen. */
    public static final class Fake implements ContainerRuntime {
        public static final List<String> CALLS = new ArrayList<>();
        final String greeting;

        Fake(String greeting) {
            this.greeting = greeting;
        }

        @Override
        public String id() {
            return "fake";
        }

        @Override
        public Availability probe() {
            return Availability.ok("1.0-" + greeting);
        }

        @Override
        public List<ContainerSummary> list(boolean all) {
            List<ContainerSummary> l = new ArrayList<>(List.of(
                    new ContainerSummary("abc123", "app-web", "nginx:alpine", "running", "Up 5 minutes", "127.0.0.1:8080->80/tcp"),
                    new ContainerSummary("def456", "secret-db", "postgres:17", "running", "Up 1 hour", "")));
            if (all) {
                l.add(new ContainerSummary("0815ab", "app-old", "busybox", "exited", "Exited (1)", ""));
            }
            return l;
        }

        @Override
        public String inspect(String container) {
            String labels = container.startsWith("own") ? "{\"devtools-mcp\":\"true\"}" : "{}";
            return """
                    {"Id":"abc1234567890","Name":"/%s","State":{"Status":"running","StartedAt":"2026-09-28T10:00:00Z"},
                     "Config":{"Image":"nginx:alpine","Env":["APP_MODE=test","DB_PASSWORD=geheim","API_KEY=xyz"],
                       "Cmd":["nginx"],"Entrypoint":null,"Labels":%s},
                     "HostConfig":{"RestartPolicy":{"Name":"no"}},
                     "NetworkSettings":{"Ports":{"80/tcp":[{"HostIp":"127.0.0.1","HostPort":"8080"}]},
                       "Networks":{"bridge":{"IPAddress":"10.0.0.2"}}},
                     "Mounts":[],"GraphDriver":{"Data":{}}}""".formatted(container, labels);
        }

        @Override
        public String logs(String container, int tail, String since, boolean timestamps) {
            return "start\nERROR kaputt\nok\n";
        }

        @Override
        public List<ContainerStats> stats(List<String> containers) {
            return containers.stream().map(c -> new ContainerStats(c, "1%", "10MB / 1GB", "1%", "0B / 0B", "0B / 0B", "3")).toList();
        }

        @Override
        public String top(String container) {
            return "PID CMD\n1 nginx";
        }

        @Override
        public List<String> diff(String container) {
            return List.of("C /etc");
        }

        @Override
        public List<ImageInfo> images() {
            return List.of(new ImageInfo("nginx", "alpine", "1b595815db66", "70MB", "3 weeks ago"));
        }

        @Override
        public List<NamedResource> networks() {
            return List.of(new NamedResource("bridge", "bridge"));
        }

        @Override
        public List<NamedResource> volumes() {
            return List.of();
        }

        @Override
        public ExecResult exec(String container, List<String> command, String workDir, String user, Duration timeout) {
            CALLS.add("exec " + container + " " + command);
            return new ExecResult(0, false, "ausgeführt: " + String.join(" ", command));
        }

        @Override
        public void start(String container) {
            CALLS.add("start " + container);
        }

        @Override
        public void stop(String container, int timeoutSeconds) {
            CALLS.add("stop " + container + " " + timeoutSeconds);
        }

        @Override
        public void restart(String container, int timeoutSeconds) {
            CALLS.add("restart " + container);
        }

        @Override
        public void copyFrom(String container, String containerPath, Path localPath) {
            CALLS.add("cp " + container + ":" + containerPath + " " + localPath);
        }

        @Override
        public void copyTo(String container, Path localPath, String containerPath) {
            CALLS.add("cp " + localPath + " " + container + ":" + containerPath);
        }

        @Override
        public String run(RunSpec spec) {
            CALLS.add("run " + spec);
            return "newid";
        }

        @Override
        public void remove(String container, boolean force, boolean volumes) {
            CALLS.add("rm " + container);
        }

        @Override
        public String pull(String image, Duration timeout) {
            CALLS.add("pull " + image);
            return "fertig";
        }

        @Override
        public void removeImage(String image, boolean force) {
            CALLS.add("rmi " + image);
        }
    }
}
