package systems.grebe.devtools.mcp.modules.jfr;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.Text;
import systems.grebe.devtools.mcp.modules.java.ArtifactStore;
import systems.grebe.devtools.mcp.modules.java.JavaEnvironment;
import systems.grebe.devtools.mcp.modules.java.JvmTarget;
import systems.grebe.devtools.mcp.modules.java.StackProfile;
import systems.grebe.devtools.mcp.core.ShellHints;

/** JFR-Tools. */
public class JfrTools {

    private static final String TARGET = "Ziel-JVM: PID, Name-Teil, container:<name>[:<pid>] oder jmx:<alias>";
    private static final String FILE = "JFR-Datei: Dateiname im Ablageordner, absoluter Pfad oder leer = neueste .jfr";
    private static final String NAME_PREFIX = "devtools-mcp";

    private final Supplier<JavaEnvironment> env;
    private final String defaultSettings;
    private final int maxSeconds;

    JfrTools(Supplier<JavaEnvironment> env, String defaultSettings, int maxSeconds) {
        this.env = env;
        this.defaultSettings = defaultSettings;
        this.maxSeconds = maxSeconds;
    }

    @Tool(name = "record", description = "Zeichnet eine JVM für N Sekunden mit JFR auf, wartet, speichert die .jfr-Datei "
            + "und liefert direkt Überblick und CPU-Hotspots. Während der Aufzeichnung sollte die zu messende Last laufen."
            + " Statt `jcmd JFR.start duration=…` verwenden." + ShellHints.JFR)
    public String record(
            @ToolParam(required = false, description = TARGET) String target,
            @ToolParam(required = false, description = "Dauer in Sekunden (Standard 30)") Integer seconds,
            @ToolParam(required = false, description = "JFR-Profil: profile oder default") String settings) {
        JavaEnvironment e = env.get();
        JvmTarget t = e.target(target);
        int secs = seconds == null ? 30 : Math.max(1, Math.min(maxSeconds, seconds));
        String name = NAME_PREFIX + "-" + System.currentTimeMillis();
        t.dcmd("JFR.start", "name=" + name, "settings=" + settings(settings));
        try {
            Thread.sleep(secs * 1000L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        Path file;
        try {
            file = t.fetchRecording(name, e.artifacts());
        } finally {
            safeStop(t, name);
        }
        String summary = new JfrAnalyzer(file, null, 15).analyze(JfrAnalyzer.Aspect.SUMMARY);
        String cpu = new JfrAnalyzer(file, null, 15).analyze(JfrAnalyzer.Aspect.CPU);
        return Text.limitLines("Aufzeichnung von " + t.describe() + " (" + secs + " s) gespeichert:\n" + file + "\n\n"
                + summary + "\n\n" + cpu, e.maxLines());
    }

    @Tool(name = "start", description = "Startet eine JFR-Aufzeichnung ohne festes Ende (für längere Szenarien). "
            + "Später mit jfr_dump sichern und mit jfr_stop beenden."
            + " Statt `jcmd JFR.start` verwenden." + ShellHints.JFR)
    public String start(
            @ToolParam(required = false, description = TARGET) String target,
            @ToolParam(required = false, description = "Name der Aufzeichnung (Standard devtools-mcp)") String name,
            @ToolParam(required = false, description = "JFR-Profil: profile oder default") String settings,
            @ToolParam(required = false, description = "Max. Alter der Daten im Ringpuffer, z.B. 10m (Standard 30m)") String maxAge) {
        JvmTarget t = env.get().target(target);
        String n = name(name);
        String out = t.dcmd("JFR.start", "name=" + n, "settings=" + settings(settings),
                "maxage=" + (maxAge == null || maxAge.isBlank() ? "30m" : maxAge.replaceAll("[^0-9smhd]", "")));
        return t.describe() + ": " + out.strip();
    }

    @Tool(name = "status", description = "Zeigt laufende JFR-Aufzeichnungen einer JVM."
            + " Statt `jcmd JFR.check` verwenden." + ShellHints.JFR)
    public String status(@ToolParam(required = false, description = TARGET) String target) {
        JvmTarget t = env.get().target(target);
        return t.describe() + "\n" + t.dcmd("JFR.check").strip();
    }

    @Tool(name = "dump", description = "Sichert den aktuellen Stand einer laufenden Aufzeichnung als .jfr-Datei (läuft weiter) "
            + "und liefert den Überblick."
            + " Statt `jcmd JFR.dump` verwenden." + ShellHints.JFR)
    public String dump(
            @ToolParam(required = false, description = TARGET) String target,
            @ToolParam(required = false, description = "Name der Aufzeichnung (Standard devtools-mcp)") String name) {
        JavaEnvironment e = env.get();
        JvmTarget t = e.target(target);
        Path file = t.fetchRecording(name(name), e.artifacts());
        return Text.limitLines("Gespeichert: " + file + "\n\n" + new JfrAnalyzer(file, null, 15).analyze(JfrAnalyzer.Aspect.SUMMARY), e.maxLines());
    }

    @Tool(name = "stop", description = "Beendet eine laufende JFR-Aufzeichnung (ohne zu speichern – vorher jfr_dump)."
            + " Statt `jcmd JFR.stop` verwenden." + ShellHints.JFR)
    public String stop(
            @ToolParam(required = false, description = TARGET) String target,
            @ToolParam(required = false, description = "Name der Aufzeichnung (Standard devtools-mcp)") String name) {
        JvmTarget t = env.get().target(target);
        return t.describe() + ": " + t.dcmd("JFR.stop", "name=" + name(name)).strip();
    }

    @Tool(name = "analyze", description = "Wertet eine .jfr-Datei aus. Aspekte: summary, cpu, allocation, gc, locks, "
            + "exceptions, io, threads. Mit packageFilter nur Stacks mit eigenem Code betrachten."
            + " Statt `jfr print`/`jfr summary` verwenden." + ShellHints.JFR)
    public String analyze(
            @ToolParam(required = false, description = FILE) String file,
            @ToolParam(required = false, description = "Aspekt (Standard summary)") String aspect,
            @ToolParam(required = false, description = "Paketpräfix, z.B. com.acme") String packageFilter,
            @ToolParam(required = false, description = "Anzahl Einträge je Liste (Standard 20)") Integer top) {
        JavaEnvironment e = env.get();
        Path f = resolve(e, file);
        return Text.limitLines(f.getFileName() + "\n" + new JfrAnalyzer(f, packageFilter, top == null ? 20 : top)
                .analyze(JfrAnalyzer.aspect(aspect)), e.maxLines());
    }

    @Tool(name = "flamegraph", description = "Erzeugt aus einer .jfr-Datei (JDK- oder async-profiler-Aufzeichnung) einen "
            + "interaktiven Flame Graph (HTML, im Artefakte-Tab öffnen) und liefert die heißesten Methoden und Aufrufpfade als Text."
            + " Statt `jfrconv` verwenden." + ShellHints.JFR)
    public String flamegraph(
            @ToolParam(required = false, description = FILE) String file,
            @ToolParam(required = false, description = "cpu (Standard), wall, alloc oder lock") String kind,
            @ToolParam(required = false, description = "Nur Stacks mit Frames, die auf diesen regulären Ausdruck passen") String include,
            @ToolParam(required = false, description = "Stacks mit passenden Frames ausschließen") String exclude) {
        JavaEnvironment e = env.get();
        Path f = resolve(e, file);
        StackProfile.Result r = StackProfile.fromJfr(f, StackProfile.Kind.parse(kind), include, exclude, null, e.artifacts());
        return Text.limitLines("Flame Graph: " + r.flameGraph() + "\n\n" + r.profile().summary(20, 5, 12), e.maxLines());
    }

    // ------------------------------------------------------------------

    private String settings(String s) {
        String v = s == null || s.isBlank() ? defaultSettings : s.trim();
        if (!v.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("Ungültiges JFR-Profil: " + v);
        }
        return v;
    }

    private static String name(String n) {
        String v = n == null || n.isBlank() ? NAME_PREFIX : n.trim();
        if (!v.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("Ungültiger Aufzeichnungsname: " + v);
        }
        return v;
    }

    static Path resolve(JavaEnvironment e, String file) {
        ArtifactStore s = e.artifacts();
        Path p = file == null || file.isBlank() ? s.latest("jfr", null) : s.resolve(file);
        if (!Files.isRegularFile(p)) {
            throw new IllegalArgumentException("Datei nicht gefunden: " + p);
        }
        return p;
    }

    private static void safeStop(JvmTarget t, String name) {
        try {
            t.dcmd("JFR.stop", "name=" + name);
        } catch (RuntimeException ignored) {
            // bereits beendet
        }
    }
}
