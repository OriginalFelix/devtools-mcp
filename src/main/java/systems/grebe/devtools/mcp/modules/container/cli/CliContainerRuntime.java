package systems.grebe.devtools.mcp.modules.container.cli;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import systems.grebe.devtools.mcp.core.CommandRunner;
import systems.grebe.devtools.mcp.core.Text;
import systems.grebe.devtools.mcp.modules.container.spi.ContainerRuntime;

/**
 * Gemeinsame Basis für Laufzeiten mit Docker-kompatibler Kommandozeile (docker, podman, nerdctl …).
 * Unterklassen legen nur Programm und globale Optionen (Kontext/Verbindung) fest.
 */
public abstract class CliContainerRuntime implements ContainerRuntime {

    protected static final Duration SHORT = Duration.ofSeconds(30);
    protected static final Duration MEDIUM = Duration.ofMinutes(2);

    private final String id;
    private final String binary;
    private final List<String> globalArgs;
    private volatile Boolean compose;

    protected CliContainerRuntime(String id, String binary, List<String> globalArgs) {
        this.id = id;
        this.binary = binary;
        this.globalArgs = List.copyOf(globalArgs);
    }

    @Override
    public String id() {
        return id;
    }

    public String binary() {
        return binary;
    }

    /** Vollständige Kommandozeile: Programm, globale Optionen, Argumente. */
    protected List<String> command(List<String> args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(binary);
        cmd.addAll(globalArgs);
        cmd.addAll(args);
        return cmd;
    }

    protected CommandRunner.Result raw(Duration timeout, List<String> args) {
        return CommandRunner.run(command(args), timeout, StandardCharsets.UTF_8);
    }

    protected String call(Duration timeout, String what, String... args) {
        return raw(timeout, List.of(args)).orThrow(id + " " + what).output();
    }

    /** Version aus {@code version --format}. Unterklassen können das Template anpassen. */
    protected String versionTemplate() {
        return "{{.Server.Version}}";
    }

    @Override
    public Availability probe() {
        try {
            var r = raw(Duration.ofSeconds(15), List.of("version", "--format", versionTemplate()));
            String out = r.output().strip();
            if (r.ok() && !out.isEmpty() && !out.contains("<no value>")) {
                return Availability.ok(Text.firstLine(out));
            }
            return Availability.unavailable(r.timedOut() ? "Zeitüberschreitung" : Text.limitLines(out, 3));
        } catch (RuntimeException e) {
            return Availability.unavailable(e.getMessage());
        }
    }

    // ------------------------------------------------------------------ Lesen

    @Override
    public List<ContainerSummary> list(boolean all) {
        List<String> args = new ArrayList<>(List.of("ps", "--no-trunc", "--format",
                "{{.ID}}\t{{.Names}}\t{{.Image}}\t{{.State}}\t{{.Status}}\t{{.Ports}}"));
        if (all) {
            args.add(1, "-a");
        }
        List<ContainerSummary> out = new ArrayList<>();
        for (String[] f : rows(raw(SHORT, args).orThrow(id + " ps").output(), 6)) {
            out.add(new ContainerSummary(shortId(f[0]), f[1], f[2], f[3].toLowerCase(java.util.Locale.ROOT), f[4], f[5]));
        }
        return out;
    }

    @Override
    public String inspect(String container) {
        String json = call(SHORT, "inspect", "inspect", "--type", "container", container);
        JsonNode node = JsonMapper.shared().readTree(json);
        if (node.isArray()) {
            if (node.isEmpty()) {
                throw new IllegalStateException("Container '" + container + "' nicht gefunden.");
            }
            node = node.get(0);
        }
        return node.toString();
    }

    @Override
    public String logs(String container, int tail, String since, boolean timestamps) {
        List<String> args = new ArrayList<>(List.of("logs", "--tail", String.valueOf(tail)));
        if (since != null && !since.isBlank()) {
            args.addAll(List.of("--since", since));
        }
        if (timestamps) {
            args.add("--timestamps");
        }
        args.add(container);
        return raw(MEDIUM, args).orThrow(id + " logs").output();
    }

    @Override
    public List<ContainerStats> stats(List<String> containers) {
        List<String> args = new ArrayList<>(List.of("stats", "--no-stream", "--format",
                "{{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}\t{{.NetIO}}\t{{.BlockIO}}\t{{.PIDs}}"));
        args.addAll(containers);
        List<ContainerStats> out = new ArrayList<>();
        for (String[] f : rows(raw(MEDIUM, args).orThrow(id + " stats").output(), 7)) {
            out.add(new ContainerStats(f[0], f[1], f[2], f[3], f[4], f[5], f[6]));
        }
        return out;
    }

    @Override
    public String top(String container) {
        return call(SHORT, "top", "top", container);
    }

    @Override
    public List<String> diff(String container) {
        return call(SHORT, "diff", "diff", container).lines().map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    @Override
    public List<ImageInfo> images() {
        List<ImageInfo> out = new ArrayList<>();
        String o = call(SHORT, "images", "images", "--format",
                "{{.Repository}}\t{{.Tag}}\t{{.ID}}\t{{.Size}}\t{{.CreatedSince}}");
        for (String[] f : rows(o, 5)) {
            out.add(new ImageInfo(f[0], f[1], shortId(f[2]), f[3], f[4]));
        }
        return out;
    }

    @Override
    public List<NamedResource> networks() {
        return named(call(SHORT, "network ls", "network", "ls", "--format", "{{.Name}}\t{{.Driver}}"));
    }

    @Override
    public List<NamedResource> volumes() {
        return named(call(SHORT, "volume ls", "volume", "ls", "--format", "{{.Name}}\t{{.Driver}}"));
    }

    // ------------------------------------------------------------------ Aktionen

    @Override
    public ExecResult exec(String container, List<String> command, String workDir, String user, Duration timeout) {
        List<String> args = new ArrayList<>(List.of("exec"));
        if (workDir != null && !workDir.isBlank()) {
            args.addAll(List.of("--workdir", workDir));
        }
        if (user != null && !user.isBlank()) {
            args.addAll(List.of("--user", user));
        }
        args.add(container);
        args.addAll(command);
        var r = raw(timeout, args);
        return new ExecResult(r.exitCode(), r.timedOut(), r.output());
    }

    @Override
    public void start(String container) {
        call(SHORT, "start", "start", container);
    }

    @Override
    public void stop(String container, int timeoutSeconds) {
        raw(Duration.ofSeconds(timeoutSeconds + 30L), List.of("stop", "-t", String.valueOf(timeoutSeconds), container))
                .orThrow(id + " stop");
    }

    @Override
    public void restart(String container, int timeoutSeconds) {
        raw(Duration.ofSeconds(2L * timeoutSeconds + 30), List.of("restart", "-t", String.valueOf(timeoutSeconds), container))
                .orThrow(id + " restart");
    }

    @Override
    public void copyFrom(String container, String containerPath, Path localPath) {
        raw(Duration.ofMinutes(5), List.of("cp", container + ":" + containerPath, localPath.toString())).orThrow(id + " cp");
    }

    @Override
    public void copyTo(String container, Path localPath, String containerPath) {
        raw(Duration.ofMinutes(5), List.of("cp", localPath.toString(), container + ":" + containerPath)).orThrow(id + " cp");
    }

    @Override
    public String run(RunSpec spec) {
        List<String> args = new ArrayList<>(List.of("run", "-d"));
        if (spec.name() != null && !spec.name().isBlank()) {
            args.addAll(List.of("--name", spec.name()));
        }
        spec.env().forEach(e -> args.addAll(List.of("-e", e)));
        spec.ports().forEach(p -> args.addAll(List.of("-p", p)));
        spec.volumes().forEach(v -> args.addAll(List.of("-v", v)));
        spec.labels().forEach(l -> args.addAll(List.of("--label", l)));
        if (spec.network() != null && !spec.network().isBlank()) {
            args.addAll(List.of("--network", spec.network()));
        }
        args.add(spec.image());
        args.addAll(spec.command());
        String out = raw(Duration.ofMinutes(10), args).orThrow(id + " run").output().strip();
        // letzte Zeile ist die Container-ID (davor ggf. Pull-Fortschritt)
        List<String> lines = out.lines().map(String::strip).filter(s -> !s.isEmpty()).toList();
        return lines.isEmpty() ? "" : shortId(lines.getLast());
    }

    @Override
    public void remove(String container, boolean force, boolean volumes) {
        List<String> args = new ArrayList<>(List.of("rm"));
        if (force) {
            args.add("-f");
        }
        if (volumes) {
            args.add("-v");
        }
        args.add(container);
        raw(MEDIUM, args).orThrow(id + " rm");
    }

    @Override
    public String pull(String image, Duration timeout) {
        return raw(timeout, List.of("pull", image)).orThrow(id + " pull").output();
    }

    @Override
    public void removeImage(String image, boolean force) {
        raw(MEDIUM, force ? List.of("rmi", "-f", image) : List.of("rmi", image)).orThrow(id + " rmi");
    }

    // ------------------------------------------------------------------ Compose

    @Override
    public boolean supportsCompose() {
        if (compose == null) {
            try {
                compose = raw(Duration.ofSeconds(20), List.of("compose", "version")).ok();
            } catch (RuntimeException e) {
                compose = false;
            }
        }
        return compose;
    }

    @Override
    public ExecResult compose(Path projectDir, List<String> args, Duration timeout) {
        List<String> a = new ArrayList<>(List.of("compose"));
        a.addAll(args);
        var r = CommandRunner.run(command(a), timeout, StandardCharsets.UTF_8, projectDir);
        return new ExecResult(r.exitCode(), r.timedOut(), r.output());
    }

    // ------------------------------------------------------------------ Hilfen

    /** Zerlegt tab-getrennte Zeilen; fehlende Felder werden mit "" aufgefüllt, Hinweiszeilen ignoriert. */
    protected static List<String[]> rows(String output, int fields) {
        List<String[]> out = new ArrayList<>();
        for (String line : output.split("\\R")) {
            if (line.isBlank() || !line.contains("\t") && fields > 1) {
                continue;
            }
            String[] parts = line.split("\t", -1);
            String[] f = new String[fields];
            for (int i = 0; i < fields; i++) {
                f[i] = i < parts.length ? parts[i].strip() : "";
            }
            out.add(f);
        }
        return out;
    }

    private static List<NamedResource> named(String output) {
        return rows(output, 2).stream().map(f -> new NamedResource(f[0], f[1])).toList();
    }

    protected static String shortId(String id) {
        String s = id.startsWith("sha256:") ? id.substring(7) : id;
        return s.length() > 12 ? s.substring(0, 12) : s;
    }
}
