package systems.grebe.devtools.mcp.modules.java;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import systems.grebe.devtools.mcp.config.SettingsStore;
import systems.grebe.devtools.mcp.core.CommandRunner;
import systems.grebe.devtools.mcp.core.ModuleConfig;

/** Ausgewertete Grundeinstellungen + Zugriff auf Prozesse, Container, JMX und Artefakte. */
public final class JavaEnvironment {

    public record JmxTarget(String alias, String host, int port) {
        public String address() {
            return host + ":" + port;
        }
    }

    private final Path jdkHome;
    private final ArtifactStore artifacts;
    private final LocalJvms processes;
    private final Containers containers;
    private final Map<String, JmxTarget> jmxTargets = new LinkedHashMap<>();
    private final String jmxUser;
    private final String jmxPassword;
    private final boolean invasive;
    private final Pattern debugHosts;
    private final int maxLines;

    public JavaEnvironment(ModuleConfig c) {
        this.jdkHome = c.get(JavaSettingsModule.JDK_HOME).map(Path::of).orElse(Path.of(System.getProperty("java.home")));
        Path dir = c.get(JavaSettingsModule.ARTIFACT_DIR).map(Path::of)
                .orElse(SettingsStore.defaultHome().resolve("diagnostics"));
        this.artifacts = ArtifactStore.forDirectory(dir, c.getInt(JavaSettingsModule.MAX_ARTIFACTS, 50));
        this.processes = new LocalJvms(regex(c.getString(JavaSettingsModule.INCLUDE, null)),
                regex(c.getString(JavaSettingsModule.EXCLUDE, null)), jcmd());
        this.containers = new Containers(c.getString(JavaSettingsModule.CONTAINER_CLI, "auto"),
                regex(c.getString(JavaSettingsModule.CONTAINERS, ".*")));
        for (String line : c.getList(JavaSettingsModule.JMX_TARGETS)) {
            int eq = line.indexOf('=');
            int colon = line.lastIndexOf(':');
            if (eq > 0 && colon > eq) {
                try {
                    String alias = line.substring(0, eq).trim();
                    jmxTargets.put(alias, new JmxTarget(alias, line.substring(eq + 1, colon).trim(),
                            Integer.parseInt(line.substring(colon + 1).trim())));
                } catch (NumberFormatException ignored) {
                    // ungültige Zeile
                }
            }
        }
        this.jmxUser = c.getString(JavaSettingsModule.JMX_USER, null);
        this.jmxPassword = c.getString(JavaSettingsModule.JMX_PASSWORD, null);
        this.invasive = c.getBoolean(JavaSettingsModule.ALLOW_INVASIVE);
        this.debugHosts = regex(c.getString(JavaSettingsModule.DEBUG_HOSTS, "localhost|127\\.0\\.0\\.1"));
        this.maxLines = Math.max(50, c.getInt(JavaSettingsModule.MAX_LINES, 400));
    }

    private static Pattern regex(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Pattern.compile(s);
        } catch (PatternSyntaxException e) {
            throw new IllegalStateException("Ungültiger regulärer Ausdruck in den Java-Grundeinstellungen: " + s);
        }
    }

    public Path jdkHome() {
        return jdkHome;
    }

    public Path jdkTool(String name) {
        return jdkHome.resolve("bin").resolve(CommandRunner.WINDOWS ? name + ".exe" : name);
    }

    public Path jcmd() {
        return jdkTool("jcmd");
    }

    public boolean jcmdAvailable() {
        return Files.isRegularFile(jcmd());
    }

    public ArtifactStore artifacts() {
        return artifacts;
    }

    public LocalJvms processes() {
        return processes;
    }

    public Containers containers() {
        return containers;
    }

    public Map<String, JmxTarget> jmxTargets() {
        return jmxTargets;
    }

    public String jmxUser() {
        return jmxUser;
    }

    public String jmxPassword() {
        return jmxPassword;
    }

    public boolean invasiveAllowed() {
        return invasive;
    }

    public void requireInvasive(String what) {
        if (!invasive) {
            throw new IllegalStateException(what + " ist eine invasive Operation und in den Java-Grundeinstellungen deaktiviert.");
        }
    }

    public boolean debugHostAllowed(String host) {
        return debugHosts == null || debugHosts.matcher(host).matches();
    }

    public int maxLines() {
        return maxLines;
    }

    /** Löst eine Zieladresse ({@code pid}, Namensteil, {@code container:…}, {@code jmx:…}) auf. */
    public JvmTarget target(String target) {
        return JvmTarget.resolve(this, target);
    }
}
