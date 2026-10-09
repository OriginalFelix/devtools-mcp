package systems.grebe.devtools.mcp.modules.container;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import tools.jackson.databind.JsonNode;
import systems.grebe.container4j.ContainerRuntime;
import systems.grebe.devtools.mcp.core.Text;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.ToolHints;

/** Lesende Container-Tools. */
@ToolHints(readOnly = true, openWorld = false)
public class ContainerReadTools {

    static final String RUNTIME = "Container-Laufzeit (z.B. docker, podman). Leer = Standard-Laufzeit.";
    static final String CONTAINER = "Name oder ID des Containers";

    private final ContainerEnvironment env;

    ContainerReadTools(ContainerEnvironment env) {
        this.env = env;
    }

    @Tool(name = "runtimes", description = "Listet die konfigurierten Container-Laufzeiten (Docker, Podman …) mit "
            + "Erreichbarkeit, Version und Compose-Unterstützung."
            + " Statt `podman version`/`docker version` verwenden." + ShellHints.CONTAINER)
    public String runtimes() {
        StringBuilder sb = new StringBuilder();
        for (ContainerEnvironment.Entry e : env.entries()) {
            var a = env.availability(e.provider().id(), false);
            sb.append(e.provider().id()).append(" (").append(e.provider().displayName()).append("): ");
            if (a.available()) {
                sb.append("verfügbar, Version ").append(a.version());
                sb.append(e.runtime().supportsCompose() ? ", Compose ja" : ", Compose nein");
            } else {
                sb.append("nicht erreichbar – ").append(a.message());
            }
            sb.append('\n');
        }
        ContainerRuntime def = env.runtimeOrNull(null);
        sb.append("Standard: ").append(def == null ? "keine erreichbar" : def.id());
        return sb.toString();
    }

    @Tool(name = "list", description = "Listet Container (Name, Image, Zustand, Status, Ports). Nur freigegebene Container."
            + " Statt `podman ps -a` verwenden." + ShellHints.CONTAINER)
    public String list(
            @ToolParam(required = false, description = "true = auch gestoppte Container (Standard true)") Boolean all,
            @ToolParam(required = false, description = "Filter: Teil von Name oder Image") String filter,
            @ToolParam(required = false, description = RUNTIME) String runtime) {
        ContainerRuntime rt = env.runtime(runtime);
        String f = filter == null ? null : filter.toLowerCase(Locale.ROOT);
        List<ContainerRuntime.ContainerSummary> list = rt.list(all == null || all).stream()
                .filter(c -> env.containerAllowed(c.name()))
                .filter(c -> f == null || c.name().toLowerCase(Locale.ROOT).contains(f) || c.image().toLowerCase(Locale.ROOT).contains(f))
                .toList();
        if (list.isEmpty()) {
            return "Keine (freigegebenen) Container gefunden (" + rt.id() + ").";
        }
        StringBuilder sb = new StringBuilder(list.size() + " Container (" + rt.id() + "):\n");
        for (var c : list) {
            sb.append("- ").append(c.name()).append("  [").append(c.state()).append("]  ").append(c.image())
                    .append("  ").append(c.status());
            if (!c.ports().isBlank()) {
                sb.append("  Ports: ").append(c.ports());
            }
            sb.append("  (").append(c.id()).append(")\n");
        }
        return Text.limitLines(sb.toString().strip(), env.maxLines());
    }

    @Tool(name = "inspect", description = "Details zu einem Container: Zustand, Image, Befehl, Umgebung (Geheimnisse maskiert), "
            + "Ports, Netzwerke, Mounts, Restart-Policy, Health. Mit full=true das komplette Inspect-JSON."
            + " Statt `podman inspect` verwenden." + ShellHints.CONTAINER)
    public String inspect(
            @ToolParam(description = CONTAINER) String container,
            @ToolParam(required = false, description = "true = vollständiges JSON statt Zusammenfassung") Boolean full,
            @ToolParam(required = false, description = RUNTIME) String runtime) {
        env.checkContainer(container);
        ContainerRuntime rt = env.runtime(runtime);
        JsonNode n = env.sanitizeInspect(rt.inspect(container));
        if (Boolean.TRUE.equals(full)) {
            return Text.limitLines(n.toPrettyString(), env.maxLines());
        }
        StringBuilder sb = new StringBuilder();
        JsonNode state = n.path("State");
        JsonNode cfg = n.path("Config");
        sb.append("Container: ").append(n.path("Name").asString("").replaceFirst("^/", "")).append(" (")
                .append(n.path("Id").asString("").substring(0, Math.min(12, n.path("Id").asString("").length()))).append(")\n");
        sb.append("Image: ").append(cfg.path("Image").asString(n.path("ImageName").asString(""))).append('\n');
        sb.append("Zustand: ").append(state.path("Status").asString(""));
        if (state.has("ExitCode") && !"running".equals(state.path("Status").asString(""))) {
            sb.append(" (Exit-Code ").append(state.path("ExitCode").asString("")).append(')');
        }
        sb.append(", gestartet ").append(state.path("StartedAt").asString("-")).append('\n');
        if (!state.path("Error").asString("").isBlank()) {
            sb.append("Fehler: ").append(state.path("Error").asString("")).append('\n');
        }
        JsonNode health = state.path("Health");
        if (health.isObject()) {
            sb.append("Health: ").append(health.path("Status").asString("")).append('\n');
        }
        sb.append("Restarts: ").append(n.path("RestartCount").asString("0")).append(", Policy: ")
                .append(n.path("HostConfig").path("RestartPolicy").path("Name").asString("-")).append('\n');
        sb.append("Entrypoint: ").append(join(cfg.path("Entrypoint"))).append('\n');
        sb.append("Cmd: ").append(join(cfg.path("Cmd"))).append('\n');
        sb.append("Arbeitsverzeichnis: ").append(cfg.path("WorkingDir").asString("-")).append('\n');
        sb.append("Umgebung:\n");
        for (JsonNode e : cfg.path("Env")) {
            sb.append("  ").append(e.asString("")).append('\n');
        }
        sb.append("Ports:\n");
        for (var p : n.path("NetworkSettings").path("Ports").properties()) {
            List<String> bind = new ArrayList<>();
            for (JsonNode b : p.getValue()) {
                bind.add(b.path("HostIp").asString("") + ":" + b.path("HostPort").asString(""));
            }
            sb.append("  ").append(p.getKey()).append(bind.isEmpty() ? " (nicht veröffentlicht)" : " -> " + String.join(", ", bind)).append('\n');
        }
        sb.append("Netzwerke: ");
        List<String> nets = new ArrayList<>();
        for (var net : n.path("NetworkSettings").path("Networks").properties()) {
            nets.add(net.getKey() + " (" + net.getValue().path("IPAddress").asString("") + ")");
        }
        sb.append(nets.isEmpty() ? "-" : String.join(", ", nets)).append('\n');
        sb.append("Mounts:\n");
        for (JsonNode m : n.path("Mounts")) {
            sb.append("  ").append(m.path("Type").asString("")).append(' ')
                    .append(m.path("Name").asString(m.path("Source").asString(""))).append(" -> ")
                    .append(m.path("Destination").asString("")).append(m.path("RW").asBoolean(true) ? "" : " (ro)").append('\n');
        }
        JsonNode labels = cfg.path("Labels");
        if (labels.isObject() && !labels.isEmpty()) {
            sb.append("Labels:\n");
            for (var l : labels.properties()) {
                sb.append("  ").append(l.getKey()).append('=').append(l.getValue().asString("")).append('\n');
            }
        }
        return Text.limitLines(sb.toString().strip(), env.maxLines());
    }

    private static String join(JsonNode arr) {
        if (!arr.isArray() || arr.isEmpty()) {
            return "-";
        }
        List<String> out = new ArrayList<>();
        arr.forEach(a -> out.add(a.asString("")));
        return String.join(" ", out);
    }

    @Tool(name = "logs", description = "Liest die Logs eines Containers (letzte Zeilen), optional ab Zeitpunkt und gefiltert."
            + " Statt `podman logs` verwenden." + ShellHints.CONTAINER)
    public String logs(
            @ToolParam(description = CONTAINER) String container,
            @ToolParam(required = false, description = "Anzahl letzter Zeilen (Standard aus Einstellungen)") Integer tail,
            @ToolParam(required = false, description = "Nur Logs seit, z.B. 10m, 2h oder 2026-09-28T10:00:00") String since,
            @ToolParam(required = false, description = "Nur Zeilen, die diesen regulären Ausdruck enthalten (Groß/Klein egal)") String grep,
            @ToolParam(required = false, description = "true = Zeitstempel voranstellen") Boolean timestamps,
            @ToolParam(required = false, description = RUNTIME) String runtime) {
        env.checkContainer(container);
        if (since != null && !since.isBlank() && !since.matches("[0-9A-Za-z:.+-]+")) {
            throw new IllegalArgumentException("Ungültige Zeitangabe für since: " + since);
        }
        ContainerRuntime rt = env.runtime(runtime);
        int n = tail == null || tail <= 0 ? env.logTail() : Math.min(tail, 10_000);
        String out = rt.logs(container, n, since, Boolean.TRUE.equals(timestamps));
        if (grep != null && !grep.isBlank()) {
            Pattern p;
            try {
                p = Pattern.compile(grep, Pattern.CASE_INSENSITIVE);
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException("Ungültiger regulärer Ausdruck: " + grep);
            }
            out = String.join("\n", out.lines().filter(l -> p.matcher(l).find()).toList());
        }
        if (out.isBlank()) {
            return "Keine Logzeilen.";
        }
        List<String> lines = out.lines().toList();
        return Text.tailLines(lines, env.maxLines());
    }

    @Tool(name = "stats", description = "Momentaufnahme der Ressourcennutzung (CPU, Speicher, Netz, Block-I/O, Prozesse). "
            + "Ohne Angabe alle laufenden freigegebenen Container."
            + " Statt `podman stats --no-stream` verwenden." + ShellHints.CONTAINER)
    public String stats(
            @ToolParam(required = false, description = "Container, kommagetrennt. Leer = alle laufenden") String containers,
            @ToolParam(required = false, description = RUNTIME) String runtime) {
        ContainerRuntime rt = env.runtime(runtime);
        List<String> names = new ArrayList<>();
        if (containers == null || containers.isBlank()) {
            rt.list(false).stream().map(ContainerRuntime.ContainerSummary::name).filter(env::containerAllowed).forEach(names::add);
            if (names.isEmpty()) {
                return "Keine laufenden (freigegebenen) Container.";
            }
        } else {
            for (String c : containers.split(",")) {
                env.checkContainer(c.trim());
                names.add(c.trim());
            }
        }
        StringBuilder sb = new StringBuilder(String.format("%-30s %8s %22s %7s %22s %22s %5s\n",
                "NAME", "CPU", "SPEICHER", "MEM%", "NETZ I/O", "BLOCK I/O", "PIDS"));
        for (var s : rt.stats(names)) {
            sb.append(String.format("%-30s %8s %22s %7s %22s %22s %5s\n", s.name(), s.cpu(), s.memUsage(), s.memPercent(),
                    s.netIo(), s.blockIo(), s.pids()));
        }
        return sb.toString().strip();
    }

    @Tool(name = "top", description = "Prozesse in einem laufenden Container."
            + " Statt `podman top` verwenden." + ShellHints.CONTAINER)
    public String top(@ToolParam(description = CONTAINER) String container,
                      @ToolParam(required = false, description = RUNTIME) String runtime) {
        env.checkContainer(container);
        return Text.limitLines(env.runtime(runtime).top(container).strip(), env.maxLines());
    }

    @Tool(name = "diff", description = "Änderungen im Dateisystem des Containers gegenüber dem Image (A=hinzugefügt, C=geändert, D=gelöscht)."
            + " Statt `podman diff` verwenden." + ShellHints.CONTAINER)
    public String diff(@ToolParam(description = CONTAINER) String container,
                       @ToolParam(required = false, description = RUNTIME) String runtime) {
        env.checkContainer(container);
        List<String> d = env.runtime(runtime).diff(container);
        return d.isEmpty() ? "Keine Änderungen." : Text.limitLines(String.join("\n", d), env.maxLines());
    }

    @Tool(name = "images", description = "Listet lokale Images (Repository, Tag, ID, Größe, Alter)."
            + " Statt `podman images` verwenden." + ShellHints.CONTAINER)
    public String images(@ToolParam(required = false, description = "Filter: Teil des Repository-Namens") String filter,
                         @ToolParam(required = false, description = RUNTIME) String runtime) {
        ContainerRuntime rt = env.runtime(runtime);
        String f = filter == null ? null : filter.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        for (var i : rt.images()) {
            if (f == null || i.repository().toLowerCase(Locale.ROOT).contains(f)) {
                sb.append("- ").append(i.reference()).append("  ").append(i.id()).append("  ").append(i.size())
                        .append("  ").append(i.created()).append('\n');
            }
        }
        return sb.isEmpty() ? "Keine Images gefunden." : Text.limitLines(sb.toString().strip(), env.maxLines());
    }

    @Tool(name = "networks", description = "Listet Container-Netzwerke."
            + " Statt `podman network ls` verwenden." + ShellHints.CONTAINER)
    public String networks(@ToolParam(required = false, description = RUNTIME) String runtime) {
        StringBuilder sb = new StringBuilder();
        env.runtime(runtime).networks().forEach(n -> sb.append("- ").append(n.name()).append(" (").append(n.driver()).append(")\n"));
        return sb.isEmpty() ? "Keine Netzwerke." : sb.toString().strip();
    }

    @Tool(name = "volumes", description = "Listet benannte Volumes."
            + " Statt `podman volume ls` verwenden." + ShellHints.CONTAINER)
    public String volumes(@ToolParam(required = false, description = RUNTIME) String runtime) {
        StringBuilder sb = new StringBuilder();
        env.runtime(runtime).volumes().forEach(n -> sb.append("- ").append(n.name()).append(" (").append(n.driver()).append(")\n"));
        return sb.isEmpty() ? "Keine Volumes." : Text.limitLines(sb.toString().strip(), env.maxLines());
    }
}
