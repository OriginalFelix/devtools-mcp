package systems.grebe.devtools.mcp.modules.container.cli;

import java.util.ArrayList;
import java.util.List;

import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.modules.container.spi.ContainerRuntime;
import systems.grebe.devtools.mcp.modules.container.spi.ContainerRuntimeProvider;
import systems.grebe.devtools.mcp.modules.container.spi.RuntimeSettings;

/** Podman (lokal, Podman-Machine unter Windows/macOS oder Remote-Verbindung). */
public class PodmanRuntimeProvider implements ContainerRuntimeProvider {

    static final String BINARY = "binary";
    static final String CONNECTION = "connection";
    static final String URL = "url";

    @Override
    public String id() {
        return "podman";
    }

    @Override
    public String displayName() {
        return "Podman";
    }

    @Override
    public int priority() {
        return 20;
    }

    @Override
    public List<ConfigField> configFields() {
        return List.of(
                ConfigField.of(BINARY, "Programm", FieldType.STRING).withDefault("podman")
                        .withHelp("Pfad zur podman-CLI oder Name im PATH."),
                ConfigField.of(CONNECTION, "Verbindung", FieldType.STRING)
                        .withHelp("Optional: podman system connection (--connection). Leer = Standardverbindung."),
                ConfigField.of(URL, "Service-URL", FieldType.STRING)
                        .withHelp("Optional: --url, z.B. ssh://user@host/run/podman/podman.sock."));
    }

    @Override
    public ContainerRuntime create(RuntimeSettings s) {
        List<String> global = new ArrayList<>();
        s.get(CONNECTION).ifPresent(c -> global.addAll(List.of("--connection", c)));
        s.get(URL).ifPresent(u -> global.addAll(List.of("--url", u)));
        return new Runtime(s.getString(BINARY, "podman"), global);
    }

    static final class Runtime extends CliContainerRuntime {
        Runtime(String binary, List<String> global) {
            super("podman", binary, global);
        }
    }
}
