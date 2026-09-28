package systems.grebe.devtools.mcp.modules.visualvm;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

import javax.management.remote.JMXConnector;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.Text;
import systems.grebe.devtools.mcp.modules.java.ArtifactStore;
import systems.grebe.devtools.mcp.modules.java.JavaEnvironment;
import systems.grebe.devtools.mcp.modules.java.JvmTarget;

/** VisualVM-Tools. */
public class VisualVmTools {

    private static final String TARGET = "Ziel-JVM: PID, Name-Teil oder jmx:<alias> (Container: Port per JMX freigeben)";

    private final Supplier<JavaEnvironment> env;
    private final ModuleConfig config;

    VisualVmTools(Supplier<JavaEnvironment> env, ModuleConfig config) {
        this.env = env;
        this.config = config;
    }

    @Tool(name = "heap_analyze", description = "Analysiert einen Heap-Dump (.hprof) mit der VisualVM-Heap-Engine: Klassen nach Speicher, "
            + "größte Objekte nach zurückgehaltenem Speicher mit Pfad zur GC-Wurzel (zeigt, wer ein Leck festhält). "
            + "Mit instance=<Klasse#Nr> die Felder eines Objekts ansehen.")
    public String heapAnalyze(
            @ToolParam(required = false, description = "Heap-Dump: Dateiname im Ablageordner, absoluter Pfad oder leer = neuester") String file,
            @ToolParam(required = false, description = "Regulärer Ausdruck für Klassennamen, z.B. com\\.acme") String classFilter,
            @ToolParam(required = false, description = "Anzahl Klassen (Standard 25)") Integer topClasses,
            @ToolParam(required = false, description = "Anzahl größter Objekte (Standard 8, 0 = überspringen; Berechnung kann bei großen Dumps dauern)") Integer topObjects,
            @ToolParam(required = false, description = "Einzelnes Objekt im Format Klasse#Nummer") String instance) {
        JavaEnvironment e = env.get();
        ArtifactStore s = e.artifacts();
        Path f = file == null || file.isBlank() ? s.latest("hprof", null) : s.resolve(file);
        HeapAnalyzer a = new HeapAnalyzer(f);
        String out = instance != null && !instance.isBlank()
                ? a.instance(instance)
                : a.overview(topClasses == null ? 25 : Math.max(1, topClasses), topObjects == null ? 8 : Math.max(0, topObjects), classFilter);
        return Text.limitLines(out, e.maxLines());
    }

    @Tool(name = "sample_cpu", description = "CPU-Sampling wie in VisualVM: holt für N Sekunden Thread-Dumps über JMX und ermittelt Hotspots "
            + "(ohne JFR, funktioniert auch bei entfernten JVMs über jmx:<alias>). Speichert einen .nps-Snapshot, den VisualVM öffnen kann.")
    public String sampleCpu(
            @ToolParam(required = false, description = TARGET) String target,
            @ToolParam(required = false, description = "Dauer in Sekunden (Standard 20)") Integer seconds,
            @ToolParam(required = false, description = "Nur Threads, deren Stack auf diesen regulären Ausdruck passt, z.B. com\\.acme") String include) {
        JavaEnvironment e = env.get();
        JvmTarget t = e.target(target);
        int secs = seconds == null ? 20 : Math.max(1, Math.min(600, seconds));
        Duration interval = Duration.ofMillis(Math.max(5, config.getInt(VisualVmModule.SAMPLE_INTERVAL, 20)));
        try (JMXConnector c = switch (t) {
            case JvmTarget.Local l -> l.connectJmx();
            case JvmTarget.Remote r -> r.connect();
            case JvmTarget.InContainer ic -> throw new IllegalArgumentException(
                    "Für Container JMX-Port freigeben und als jmx:<alias> konfigurieren – oder asprof_profile verwenden.");
        }) {
            JmxCpuSampler.Result r = JmxCpuSampler.sample(c.getMBeanServerConnection(), Duration.ofSeconds(secs), interval,
                    include, t.label(), e.artifacts());
            return Text.limitLines(t.describe() + " – " + secs + " s\n" + (r.snapshot() == null ? "" : "Snapshot: " + r.snapshot() + "\n")
                    + "\n" + r.summary(), e.maxLines());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Abgebrochen", ex);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("JMX-Fehler: " + ex.getMessage(), ex);
        }
    }

    @Tool(name = "open", description = "Öffnet eine JVM in der VisualVM-Oberfläche beim Benutzer (Monitor, Threads, Sampler). "
            + "Optional direkt den CPU- oder Memory-Sampler starten.")
    public String open(
            @ToolParam(required = false, description = "PID/Name einer lokalen JVM oder jmx:<alias>") String target,
            @ToolParam(required = false, description = "none (Standard), cpu oder memory") String sampler) {
        JavaEnvironment e = env.get();
        JvmTarget t = e.target(target);
        List<String> opts = switch (t) {
            case JvmTarget.Local l -> {
                String pid = String.valueOf(l.pid());
                if ("cpu".equalsIgnoreCase(sampler)) {
                    yield List.of("--openpid", pid, "--start-cpu-sampler", pid);
                }
                if ("memory".equalsIgnoreCase(sampler)) {
                    yield List.of("--openpid", pid, "--start-memory-sampler", pid);
                }
                yield List.of("--openpid", pid);
            }
            case JvmTarget.Remote r -> List.of("--openjmx", r.jmxTarget().address());
            case JvmTarget.InContainer c -> throw new IllegalArgumentException(
                    "Container-JVMs kann VisualVM nur über JMX öffnen (Port freigeben, als jmx:<alias> konfigurieren).");
        };
        return launcher().launch(opts) + " (" + t.describe() + ")";
    }

    @Tool(name = "open_file", description = "Öffnet eine Datei in der VisualVM-Oberfläche beim Benutzer: Heap-Dump (.hprof), "
            + "JFR-Aufzeichnung (.jfr), Sampler-Snapshot (.nps) oder Thread-Dump (.tdump).")
    public String openFile(@ToolParam(description = "Dateiname im Ablageordner oder absoluter Pfad") String file) {
        Path f = env.get().artifacts().resolve(file);
        String name = f.getFileName().toString().toLowerCase();
        if (!(name.endsWith(".hprof") || name.endsWith(".jfr") || name.endsWith(".nps") || name.endsWith(".tdump"))) {
            throw new IllegalArgumentException("VisualVM öffnet .hprof, .jfr, .nps und .tdump – nicht " + f.getFileName());
        }
        if (!Files.isRegularFile(f)) {
            throw new IllegalArgumentException("Datei nicht gefunden: " + f);
        }
        return launcher().launch(List.of("--openfile", f.toString()));
    }

    private VisualVmLauncher launcher() {
        return new VisualVmLauncher(config, env.get().jdkHome());
    }
}
