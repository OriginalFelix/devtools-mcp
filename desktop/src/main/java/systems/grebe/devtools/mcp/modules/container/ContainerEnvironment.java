package systems.grebe.devtools.mcp.modules.container;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import systems.grebe.container4j.ContainerRuntime;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.Workspaces;
import systems.grebe.devtools.mcp.modules.container.spi.ContainerRuntimeProvider;
import systems.grebe.devtools.mcp.modules.container.spi.RuntimeSettings;

/**
 * Ausgewertete Konfiguration des Container-Moduls: aktive Laufzeiten, Freigaben und Sicherheitsprüfungen.
 * Wird von den Container-Tools und den Java-Diagnosemodulen (container:-Ziele) gemeinsam genutzt.
 */
public final class ContainerEnvironment {

    public record Entry(ContainerRuntimeProvider provider, ContainerRuntime runtime) { }

    private static final Duration PROBE_TTL = Duration.ofSeconds(30);
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]*");
    private static final Pattern SECRET_ENV = Pattern.compile(
            "(?i).*(PASSWORD|PASSWD|PWD|SECRET|TOKEN|API_?KEY|PRIVATE_?KEY|CREDENTIAL|ACCESS_?KEY).*");

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, ProbeResult> probes = new ConcurrentHashMap<>();
    private final String defaultRuntime;
    private final Pattern allowedContainers;
    private final Pattern allowedImages;
    private final Set<String> execAllowlist;
    private final int execTimeout;
    private final List<Path> hostDirs = new ArrayList<>();
    private final Workspaces composeProjects;
    private final boolean localPortsOnly;
    private final boolean onlyOwn;
    private final boolean maskSecrets;
    private final int logTail;
    private final int maxLines;

    private record ProbeResult(ContainerRuntime.Availability availability, long at) { }

    /** Standardeinstellungen mit allen Laufzeiten vom Classpath (für Tests und Verbindungsprüfungen). */
    public static ContainerEnvironment defaults() {
        return withValues(Map.of());
    }

    /** Einstellungen aus Rohwerten (Schlüssel wie im Container-Modul). */
    public static ContainerEnvironment withValues(Map<String, String> values) {
        ContainerRuntimes r = new ContainerRuntimes();
        return new ContainerEnvironment(r, ModuleConfig.of(new ContainerModule(r).configSchema(), values));
    }

    public ContainerEnvironment(ContainerRuntimes runtimes, ModuleConfig c) {
        for (ContainerRuntimeProvider p : runtimes.providers()) {
            if (!c.get(ContainerModule.enabledKey(p.id())).map(Boolean::parseBoolean).orElse(true)) {
                continue;
            }
            RuntimeSettings rs = new RuntimeSettings(k -> c.get(ContainerModule.key(p.id(), k)));
            entries.put(p.id(), new Entry(p, p.create(rs)));
        }
        this.defaultRuntime = c.getString(ContainerModule.DEFAULT_RUNTIME, "auto");
        this.allowedContainers = regex(c.getString(ContainerModule.ALLOWED_CONTAINERS, ".*"), "Erlaubte Container");
        this.allowedImages = regex(c.getString(ContainerModule.ALLOWED_IMAGES, ".*"), "Erlaubte Images");
        this.execAllowlist = Set.copyOf(c.getList(ContainerModule.EXEC_ALLOWLIST));
        this.execTimeout = Math.max(1, c.getInt(ContainerModule.EXEC_TIMEOUT, 60));
        for (String d : c.getList(ContainerModule.HOST_DIRS)) {
            try {
                hostDirs.add(Path.of(d).toAbsolutePath().normalize());
            } catch (InvalidPathException ignored) {
                // wird von validate() gemeldet
            }
        }
        this.composeProjects = new Workspaces(c.getList(ContainerModule.COMPOSE_PROJECTS),
                ContainerEnvironment::isComposeProject, "Compose-Projekte");
        this.localPortsOnly = c.getBoolean(ContainerModule.LOCAL_PORTS_ONLY);
        this.onlyOwn = c.getBoolean(ContainerModule.ONLY_OWN);
        this.maskSecrets = c.getBoolean(ContainerModule.MASK_SECRETS);
        this.logTail = Math.max(1, c.getInt(ContainerModule.LOG_TAIL, 200));
        this.maxLines = Math.max(50, c.getInt(ContainerModule.MAX_LINES, 400));
    }

    private static Pattern regex(String s, String label) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Pattern.compile(s);
        } catch (PatternSyntaxException e) {
            throw new IllegalStateException("Ungültiger regulärer Ausdruck bei '" + label + "': " + s);
        }
    }

    static boolean isComposeProject(Path dir) {
        return List.of("compose.yaml", "compose.yml", "docker-compose.yml", "docker-compose.yaml").stream()
                .anyMatch(f -> Files.isRegularFile(dir.resolve(f)));
    }

    // ------------------------------------------------------------------ Laufzeiten

    public List<Entry> entries() {
        return List.copyOf(entries.values());
    }

    public ContainerRuntime.Availability availability(String id, boolean fresh) {
        Entry e = entries.get(id);
        if (e == null) {
            return ContainerRuntime.Availability.unavailable("nicht aktiviert");
        }
        ProbeResult p = probes.get(id);
        long now = System.currentTimeMillis();
        if (fresh || p == null || now - p.at() > PROBE_TTL.toMillis()) {
            p = new ProbeResult(e.runtime().probe(), now);
            probes.put(id, p);
        }
        return p.availability();
    }

    /** Laufzeit per ID oder die Standard-Laufzeit ({@code auto} = erste erreichbare). */
    public ContainerRuntime runtime(String id) {
        String wanted = id == null || id.isBlank() ? defaultRuntime : id.trim().toLowerCase(Locale.ROOT);
        if (!"auto".equals(wanted)) {
            Entry e = entries.get(wanted);
            if (e == null) {
                throw new IllegalArgumentException("Container-Laufzeit '" + wanted + "' ist nicht aktiviert. Verfügbar: "
                        + entries.keySet());
            }
            return e.runtime();
        }
        List<String> tried = new ArrayList<>();
        for (Entry e : entries.values()) {
            ContainerRuntime.Availability a = availability(e.provider().id(), false);
            if (a.available()) {
                return e.runtime();
            }
            tried.add(e.provider().id() + ": " + a.message());
        }
        throw new IllegalStateException("Keine Container-Laufzeit erreichbar"
                + (tried.isEmpty() ? " (keine aktiviert)." : ": " + String.join("; ", tried)));
    }

    /** Wie {@link #runtime(String)}, aber {@code null} statt Ausnahme. */
    public ContainerRuntime runtimeOrNull(String id) {
        try {
            return runtime(id);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ Prüfungen

    public void checkContainer(String container) {
        if (container == null || !NAME.matcher(container).matches()) {
            throw new IllegalArgumentException("Ungültiger Containername: " + container);
        }
        if (allowedContainers != null && !allowedContainers.matcher(container).matches()) {
            throw new IllegalArgumentException("Container '" + container + "' ist im Container-Modul nicht freigegeben.");
        }
    }

    public boolean containerAllowed(String container) {
        return allowedContainers == null || allowedContainers.matcher(container).matches();
    }

    public void checkImage(String image) {
        if (image == null || !image.matches("[A-Za-z0-9][A-Za-z0-9._/:@-]*")) {
            throw new IllegalArgumentException("Ungültige Image-Angabe: " + image);
        }
        if (allowedImages != null && !allowedImages.matcher(image).matches()) {
            throw new IllegalArgumentException("Image '" + image + "' ist im Container-Modul nicht freigegeben.");
        }
    }

    public void checkExec(List<String> command) {
        if (command.isEmpty()) {
            throw new IllegalArgumentException("Kein Befehl angegeben.");
        }
        if (!execAllowlist.isEmpty()) {
            String program = command.getFirst();
            String base = program.substring(program.lastIndexOf('/') + 1);
            if (!execAllowlist.contains(program) && !execAllowlist.contains(base)) {
                throw new IllegalArgumentException("Programm '" + program + "' ist für exec nicht freigegeben. Erlaubt: "
                        + execAllowlist);
            }
        }
    }

    /**
     * Host-Pfad muss in einem freigegebenen Verzeichnis liegen. Geprüft wird auch der echte Pfad des nächsten
     * vorhandenen Vorfahren: Ein Symlink in der Freigabe (docker/podman cp behält Symlinks) darf nicht aus ihr
     * herausführen – sonst könnte {@code run -v <Freigabe>/link:/x} z.B. {@code /} oder {@code C:\} einhängen.
     */
    public Path checkHostPath(String path) {
        if (hostDirs.isEmpty()) {
            throw new IllegalStateException("Keine Host-Verzeichnisse freigegeben (Container-Modul → Freigegebene Host-Verzeichnisse).");
        }
        Path p;
        try {
            p = Path.of(path).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("Ungültiger Pfad: " + path);
        }
        for (Path d : hostDirs) {
            if (p.startsWith(d) && realPathInside(p, d)) {
                return p;
            }
        }
        throw new IllegalArgumentException("Pfad '" + p + "' liegt nicht in einem freigegebenen Host-Verzeichnis: " + hostDirs);
    }

    /**
     * Liegt der echte Pfad des nächsten vorhandenen Vorfahren von {@code target} im echten Pfad von {@code root}? Ein
     * ins Leere zeigender Symlink zählt als vorhanden (und scheitert), damit die Laufzeit kein Ziel außerhalb anlegt.
     * Existiert die Freigabe selbst noch nicht, kann in ihr auch kein Symlink liegen.
     */
    private static boolean realPathInside(Path target, Path root) {
        if (!Files.exists(root)) {
            return true;
        }
        try {
            Path existing = target;
            while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
                existing = existing.getParent();
            }
            return existing != null && existing.toRealPath().startsWith(root.toRealPath());
        } catch (IOException e) {
            return false;
        }
    }

    /** Portangabe prüfen und ggf. an 127.0.0.1 binden. */
    public String normalizePort(String spec) {
        String s = spec.trim();
        if (!s.matches("([0-9.]+:)?(\\d{1,5}:)?\\d{1,5}(/(tcp|udp))?")) {
            throw new IllegalArgumentException("Ungültige Portangabe: " + spec + " (erwartet [ip:]host:container[/tcp|udp])");
        }
        long colons = s.chars().filter(ch -> ch == ':').count();
        if (!localPortsOnly) {
            return s;
        }
        if (colons == 2) {
            if (!s.startsWith("127.0.0.1:")) {
                throw new IllegalArgumentException("Ports dürfen nur auf 127.0.0.1 veröffentlicht werden: " + spec);
            }
            return s;
        }
        if (colons == 0) {
            // nur Container-Port -> zufälliger Host-Port auf localhost
            return "127.0.0.1::" + s;
        }
        return "127.0.0.1:" + s;
    }

    /** Volume-Angabe prüfen: Hostpfade nur aus freigegebenen Verzeichnissen, benannte Volumes erlaubt. */
    public String normalizeVolume(String spec) {
        String s = spec.trim();
        // Windows-Laufwerk (C:\...) berücksichtigen: Ziel beginnt mit ':/'
        int sep = s.indexOf(":/", s.length() > 2 && s.charAt(1) == ':' ? 2 : 0);
        if (sep <= 0) {
            throw new IllegalArgumentException("Ungültige Volume-Angabe: " + spec + " (erwartet quelle:/ziel[:ro])");
        }
        String source = s.substring(0, sep);
        String rest = s.substring(sep);
        if (source.matches("[A-Za-z0-9][A-Za-z0-9_.-]*")) {
            return s; // benanntes Volume
        }
        return checkHostPath(source) + rest;
    }

    public boolean onlyOwn() {
        return onlyOwn;
    }

    // ------------------------------------------------------------------ Ausgabe

    /** Inspect-JSON gekürzt und ggf. mit maskierten Geheimnissen. */
    public JsonNode sanitizeInspect(String json) {
        JsonNode node = JsonMapper.shared().readTree(json);
        if (maskSecrets) {
            JsonNode env = node.path("Config").path("Env");
            if (env instanceof ArrayNode arr) {
                for (int i = 0; i < arr.size(); i++) {
                    String v = arr.get(i).asString("");
                    int eq = v.indexOf('=');
                    if (eq > 0 && SECRET_ENV.matcher(v.substring(0, eq)).matches()) {
                        arr.set(i, arr.stringNode(v.substring(0, eq) + "=***"));
                    }
                }
            }
        }
        if (node instanceof ObjectNode o) {
            // für das LLM irrelevante, lange Felder
            for (String k : List.of("GraphDriver", "AppArmorProfile", "BoundingCaps", "EffectiveCaps", "ExecIDs",
                    "MountLabel", "ProcessLabel", "OCIConfigPath", "ConmonPidFile", "PidFile", "StaticDir", "lockNumber")) {
                o.remove(k);
            }
        }
        return node;
    }

    public Map<String, Path> composeProjects() {
        return composeProjects.all();
    }

    public Path composeProject(String name) {
        return composeProjects.resolve(name, null);
    }

    public int execTimeout() {
        return execTimeout;
    }

    public int logTail() {
        return logTail;
    }

    public int maxLines() {
        return maxLines;
    }
}
