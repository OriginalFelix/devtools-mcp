package systems.grebe.devtools.mcp.modules.container.spi;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Eine Container-Laufzeit. Alle Methoden sind blockierend und werfen {@link IllegalStateException} mit einer
 * verständlichen Meldung, wenn die Laufzeit den Befehl nicht ausführen kann.
 *
 * <p>Zugriffsprüfungen (erlaubte Container, Images, Verzeichnisse) sind <em>nicht</em> Aufgabe der
 * Laufzeit – das übernimmt das Container-Modul, bevor es eine Methode aufruft.
 */
public interface ContainerRuntime {

    /** ID des Providers, der diese Laufzeit erzeugt hat. */
    String id();

    /** Prüft, ob die Laufzeit erreichbar ist (CLI vorhanden, Daemon/Maschine läuft). */
    Availability probe();

    // ------------------------------------------------------------------ Lesen

    List<ContainerSummary> list(boolean all);

    /** Docker-kompatibles Inspect-JSON (ein Objekt, kein Array). */
    String inspect(String container);

    String logs(String container, int tail, String since, boolean timestamps);

    List<ContainerStats> stats(List<String> containers);

    String top(String container);

    List<String> diff(String container);

    List<ImageInfo> images();

    List<NamedResource> networks();

    List<NamedResource> volumes();

    // ------------------------------------------------------------------ Aktionen

    ExecResult exec(String container, List<String> command, String workDir, String user, Duration timeout);

    void start(String container);

    void stop(String container, int timeoutSeconds);

    void restart(String container, int timeoutSeconds);

    void copyFrom(String container, String containerPath, Path localPath);

    void copyTo(String container, Path localPath, String containerPath);

    /** Startet einen Container im Hintergrund und liefert seine ID. */
    String run(RunSpec spec);

    void remove(String container, boolean force, boolean volumes);

    /** Lädt ein Image und liefert die (gekürzte) Ausgabe. */
    String pull(String image, Duration timeout);

    void removeImage(String image, boolean force);

    // ------------------------------------------------------------------ Compose

    default boolean supportsCompose() {
        return false;
    }

    /** Führt {@code compose <args>} im Projektverzeichnis aus. */
    default ExecResult compose(Path projectDir, List<String> args, Duration timeout) {
        throw new UnsupportedOperationException("Die Laufzeit '" + id() + "' unterstützt kein Compose.");
    }

    // ------------------------------------------------------------------ Modell

    record Availability(boolean available, String version, String message) {
        public static Availability ok(String version) {
            return new Availability(true, version, "verfügbar");
        }

        public static Availability unavailable(String message) {
            return new Availability(false, null, message);
        }
    }

    record ContainerSummary(String id, String name, String image, String state, String status, String ports) { }

    record ContainerStats(String name, String cpu, String memUsage, String memPercent, String netIo, String blockIo, String pids) { }

    record ImageInfo(String repository, String tag, String id, String size, String created) {
        public String reference() {
            return tag == null || tag.isBlank() || "<none>".equals(tag) ? repository : repository + ":" + tag;
        }
    }

    record NamedResource(String name, String driver) { }

    record ExecResult(int exitCode, boolean timedOut, String output) {
        public boolean ok() {
            return !timedOut && exitCode == 0;
        }
    }

    /**
     * Parameter für {@code run}. Ports im Format {@code [ip:]host:container[/proto]}, Volumes als
     * {@code quelle:/ziel[:ro]} (Quelle = Hostpfad oder Volumename).
     */
    record RunSpec(String image, String name, List<String> env, List<String> ports, List<String> volumes,
                   String network, List<String> labels, List<String> command) {
        public RunSpec {
            env = env == null ? List.of() : List.copyOf(env);
            ports = ports == null ? List.of() : List.copyOf(ports);
            volumes = volumes == null ? List.of() : List.copyOf(volumes);
            labels = labels == null ? List.of() : List.copyOf(labels);
            command = command == null ? List.of() : List.copyOf(command);
        }
    }
}
