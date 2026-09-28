package systems.grebe.devtools.mcp.modules.container;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.Text;
import systems.grebe.devtools.mcp.modules.container.spi.ContainerRuntime;
import systems.grebe.devtools.mcp.core.ShellHints;

/** Container anlegen und Images laden. */
public class ContainerCreateTools {

    private final ContainerEnvironment env;

    ContainerCreateTools(ContainerEnvironment env) {
        this.env = env;
    }

    @Tool(name = "run", description = "Startet einen neuen Container im Hintergrund (docker/podman run -d). Der Container "
            + "erhält das Label '" + ContainerModule.OWN_LABEL + "'. Ports ohne IP werden an 127.0.0.1 gebunden (Einstellung), "
            + "Bind-Mounts nur aus freigegebenen Host-Verzeichnissen."
            + " Statt `podman run -d` verwenden." + ShellHints.CONTAINER)
    public String run(
            @ToolParam(description = "Image, z.B. postgres:17 (muss freigegeben sein)") String image,
            @ToolParam(description = "Containername (muss zum Filter 'Erlaubte Container' passen)") String name,
            @ToolParam(required = false, description = "Umgebungsvariablen als KEY=VALUE") List<String> env,
            @ToolParam(required = false, description = "Ports als [ip:]host:container[/tcp|udp], z.B. 5432:5432") List<String> ports,
            @ToolParam(required = false, description = "Volumes als quelle:/ziel[:ro]; quelle = Volumename oder Hostpfad") List<String> volumes,
            @ToolParam(required = false, description = "Netzwerk") String network,
            @ToolParam(required = false, description = "Befehl/Argumente statt des Image-Standards") List<String> command,
            @ToolParam(required = false, description = ContainerReadTools.RUNTIME) String runtime) {
        this.env.checkImage(image);
        this.env.checkContainer(name);
        List<String> envs = env == null ? List.of() : env;
        for (String e : envs) {
            if (!e.matches("[A-Za-z_][A-Za-z0-9_.]*=.*")) {
                throw new IllegalArgumentException("Ungültige Umgebungsvariable (KEY=VALUE erwartet): " + e);
            }
        }
        List<String> p = new ArrayList<>();
        (ports == null ? List.<String>of() : ports).forEach(x -> p.add(this.env.normalizePort(x)));
        List<String> v = new ArrayList<>();
        (volumes == null ? List.<String>of() : volumes).forEach(x -> v.add(this.env.normalizeVolume(x)));
        if (network != null && !network.isBlank() && !network.matches("[A-Za-z0-9][A-Za-z0-9_.-]*")) {
            throw new IllegalArgumentException("Ungültiger Netzwerkname: " + network);
        }
        ContainerRuntime rt = this.env.runtime(runtime);
        String id = rt.run(new ContainerRuntime.RunSpec(image, name, envs, p, v, network,
                List.of(ContainerModule.OWN_LABEL + "=true"), command));
        return "Container " + name + " gestartet (" + rt.id() + ", ID " + id + ")."
                + (p.isEmpty() ? "" : " Ports: " + String.join(", ", p));
    }

    @Tool(name = "pull", description = "Lädt ein Image aus der Registry (muss freigegeben sein)."
            + " Statt `podman pull` verwenden." + ShellHints.CONTAINER)
    public String pull(
            @ToolParam(description = "Image, z.B. redis:7-alpine") String image,
            @ToolParam(required = false, description = ContainerReadTools.RUNTIME) String runtime) {
        env.checkImage(image);
        String out = env.runtime(runtime).pull(image, Duration.ofMinutes(15));
        return "Image " + image + " geladen.\n" + Text.tailLines(out.lines().toList(), 10);
    }
}
