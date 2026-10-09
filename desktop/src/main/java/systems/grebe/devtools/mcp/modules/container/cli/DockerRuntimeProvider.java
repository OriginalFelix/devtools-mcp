package systems.grebe.devtools.mcp.modules.container.cli;

import java.util.List;

import systems.grebe.container4j.ContainerRuntime;
import systems.grebe.container4j.cli.DockerRuntime;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.modules.container.spi.ContainerRuntimeProvider;
import systems.grebe.devtools.mcp.modules.container.spi.RuntimeSettings;

/** Docker (Docker Desktop, Docker Engine, Remote-Daemons über Kontext bzw. Host). */
public class DockerRuntimeProvider implements ContainerRuntimeProvider {

    static final String BINARY = "binary";
    static final String CONTEXT = "context";
    static final String HOST = "host";

    @Override
    public String id() {
        return "docker";
    }

    @Override
    public String displayName() {
        return "Docker";
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public List<ConfigField> configFields() {
        return List.of(
                ConfigField.of(BINARY, "Programm", FieldType.STRING).withDefault("docker")
                        .withHelp("Pfad zur docker-CLI oder Name im PATH."),
                ConfigField.of(CONTEXT, "Kontext", FieldType.STRING)
                        .withHelp("Optional: docker context (--context). Leer = aktueller Kontext."),
                ConfigField.of(HOST, "Daemon-Adresse", FieldType.STRING)
                        .withHelp("Optional: -H, z.B. tcp://build01:2375 oder npipe:////./pipe/docker_engine."));
    }

    @Override
    public ContainerRuntime create(RuntimeSettings s) {
        return new DockerRuntime(s.getString(BINARY, "docker"), s.get(CONTEXT).orElse(null), s.get(HOST).orElse(null));
    }
}
