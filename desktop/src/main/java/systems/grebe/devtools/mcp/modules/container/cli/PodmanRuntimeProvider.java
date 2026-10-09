package systems.grebe.devtools.mcp.modules.container.cli;

import java.util.List;

import systems.grebe.container4j.ContainerRuntime;
import systems.grebe.container4j.cli.PodmanRuntime;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.FieldType;
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
        return new PodmanRuntime(s.getString(BINARY, "podman"), s.get(CONNECTION).orElse(null), s.get(URL).orElse(null));
    }
}
