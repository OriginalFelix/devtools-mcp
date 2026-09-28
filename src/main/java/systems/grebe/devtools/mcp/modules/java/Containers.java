package systems.grebe.devtools.mcp.modules.java;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import systems.grebe.devtools.mcp.core.CommandRunner;

/** Zugriff auf Container über die docker- bzw. podman-CLI. */
public final class Containers {

    private final String configured;
    private final Pattern allowed;
    private volatile String resolvedCli;
    private volatile boolean resolved;

    Containers(String configured, Pattern allowed) {
        this.configured = configured == null ? "auto" : configured;
        this.allowed = allowed;
    }

    /** Verfügbare CLI oder {@code null}. */
    public String cli() {
        if (!resolved) {
            resolvedCli = detect();
            resolved = true;
        }
        return resolvedCli;
    }

    private String detect() {
        if ("aus".equals(configured)) {
            return null;
        }
        List<String> candidates = "auto".equals(configured) ? List.of("docker", "podman") : List.of(configured);
        for (String c : candidates) {
            try {
                var r = CommandRunner.run(List.of(c, "ps", "-q"), Duration.ofSeconds(15));
                if (r.ok()) {
                    return c;
                }
            } catch (RuntimeException ignored) {
                // nicht installiert
            }
        }
        return null;
    }

    private String requireCli() {
        String c = cli();
        if (c == null) {
            throw new IllegalStateException("Keine Container-Laufzeit verfügbar (docker/podman nicht gefunden oder nicht gestartet).");
        }
        return c;
    }

    public void checkAllowed(String container) {
        if (!container.matches("[A-Za-z0-9][A-Za-z0-9_.-]*")) {
            throw new IllegalArgumentException("Ungültiger Containername: " + container);
        }
        if (allowed != null && !allowed.matcher(container).matches()) {
            throw new IllegalArgumentException("Container '" + container + "' ist in den Java-Grundeinstellungen nicht freigegeben.");
        }
    }

    public record Container(String name, String image, String status) { }

    public List<Container> running() {
        String c = cli();
        if (c == null) {
            return List.of();
        }
        var r = CommandRunner.runUtf8(List.of(c, "ps", "--format", "{{.Names}}\t{{.Image}}\t{{.Status}}"), Duration.ofSeconds(20));
        List<Container> out = new ArrayList<>();
        if (!r.ok()) {
            return out;
        }
        for (String line : r.output().split("\\R")) {
            String[] parts = line.split("\t");
            if (parts.length >= 3 && (allowed == null || allowed.matcher(parts[0]).matches())) {
                out.add(new Container(parts[0], parts[1], parts[2]));
            }
        }
        return out;
    }

    /** Führt einen Befehl im Container aus. */
    public CommandRunner.Result exec(String container, Duration timeout, String... command) {
        checkAllowed(container);
        List<String> cmd = new ArrayList<>(List.of(requireCli(), "exec", container));
        cmd.addAll(List.of(command));
        return CommandRunner.runUtf8(cmd, timeout);
    }

    public void copyFrom(String container, String containerPath, Path local) {
        checkAllowed(container);
        CommandRunner.run(List.of(requireCli(), "cp", container + ":" + containerPath, local.toString()), Duration.ofMinutes(5))
                .orThrow("Kopieren aus Container " + container);
    }

    public void copyTo(String container, Path local, String containerPath) {
        checkAllowed(container);
        CommandRunner.run(List.of(requireCli(), "cp", local.toString(), container + ":" + containerPath), Duration.ofMinutes(5))
                .orThrow("Kopieren in Container " + container);
    }
}
