package systems.grebe.devtools.mcp.modules.visualvm;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import javax.management.MBeanServerConnection;

import org.graalvm.visualvm.lib.jfluid.results.cpu.CPUResultsSnapshot;
import org.graalvm.visualvm.lib.jfluid.results.cpu.FlatProfileContainer;
import org.graalvm.visualvm.lib.jfluid.results.cpu.StackTraceSnapshotBuilder;
import systems.grebe.devtools.mcp.modules.java.ArtifactStore;
import systems.grebe.devtools.mcp.modules.java.IdleFrames;

/**
 * CPU-Sampler nach dem Vorbild von VisualVM: holt periodisch Thread-Dumps über JMX und baut daraus mit der
 * VisualVM-Engine ({@link StackTraceSnapshotBuilder}) einen CPU-Snapshot. Funktioniert für lokale und
 * entfernte JVMs gleichermaßen. Der Snapshot wird als .nps gespeichert und lässt sich in VisualVM öffnen.
 */
public final class JmxCpuSampler {

    private static final Set<String> SAMPLER_THREADS = Set.of("RMI TCP Connection", "JMX server connection timeout",
            "RMI Scheduler", "Attach Listener", "Signal Dispatcher", "Common-Cleaner", "Notification Thread");

    public record Result(String summary, Path snapshot) { }

    private JmxCpuSampler() {
    }

    public static Result sample(MBeanServerConnection mbs, Duration duration, Duration interval, String includeRegex,
                                String label, ArtifactStore store) throws IOException, InterruptedException {
        ThreadMXBean threads = ManagementFactory.newPlatformMXBeanProxy(mbs, ManagementFactory.THREAD_MXBEAN_NAME, ThreadMXBean.class);
        StackTraceSnapshotBuilder builder = new StackTraceSnapshotBuilder();
        Pattern include = includeRegex == null || includeRegex.isBlank() ? null : Pattern.compile(includeRegex);
        long end = System.nanoTime() + duration.toNanos();
        int samples = 0;
        long busy = 0;
        while (System.nanoTime() < end) {
            long t0 = System.nanoTime();
            ThreadInfo[] all = threads.dumpAllThreads(false, false);
            ThreadInfo[] running = Arrays.stream(all).filter(t -> t != null && isBusy(t, include)).toArray(ThreadInfo[]::new);
            builder.addStacktrace(running, System.nanoTime());
            samples++;
            busy += running.length;
            long sleep = interval.toMillis() - Duration.ofNanos(System.nanoTime() - t0).toMillis();
            if (sleep > 0) {
                Thread.sleep(sleep);
            }
        }
        CPUResultsSnapshot snapshot;
        try {
            snapshot = builder.createSnapshot(System.currentTimeMillis());
        } catch (CPUResultsSnapshot.NoDataAvailableException e) {
            return new Result("Keine aktiven Threads beobachtet (" + samples + " Samples) – lief während der Messung Last?", null);
        }
        Path nps = store.newFile(label + "-cpu-sampler", "nps");
        writeNps(snapshot, nps);
        store.commit(nps);
        return new Result(format(snapshot, samples, busy, interval), nps);
    }

    static boolean isBusy(ThreadInfo t, Pattern include) {
        if (t.getThreadState() != Thread.State.RUNNABLE) {
            return false;
        }
        String name = t.getThreadName();
        for (String s : SAMPLER_THREADS) {
            if (name.startsWith(s)) {
                return false;
            }
        }
        StackTraceElement[] st = t.getStackTrace();
        if (st.length == 0) {
            return false;
        }
        StackTraceElement top = st[0];
        if (top.isNativeMethod() && IdleFrames.isIdle(top.getClassName() + "." + top.getMethodName())) {
            return false;
        }
        if (include == null) {
            return true;
        }
        for (StackTraceElement e : st) {
            if (include.matcher(e.getClassName() + "." + e.getMethodName()).find()) {
                return true;
            }
        }
        return false;
    }

    private static String format(CPUResultsSnapshot s, int samples, long busy, Duration interval) {
        FlatProfileContainer flat = s.getFlatProfile(-1, CPUResultsSnapshot.METHOD_LEVEL_VIEW);
        List<Integer> rows = new ArrayList<>();
        long self = 0;
        for (int r = 0; r < flat.getNRows(); r++) {
            rows.add(r);
            self += flat.getTimeInMcs0AtRow(r);
        }
        final long total = Math.max(1, self);
        StringBuilder sb = new StringBuilder();
        sb.append("VisualVM-Sampler: ").append(samples).append(" Samples à ").append(interval.toMillis()).append(" ms, Ø ")
                .append(String.format("%.1f", samples == 0 ? 0.0 : (double) busy / samples)).append(" aktive Threads\n\n");
        sb.append("Hotspots nach Eigenzeit (self):\n");
        rows.stream().sorted(Comparator.comparingLong((Integer r) -> flat.getTimeInMcs0AtRow(r)).reversed()).limit(20)
                .filter(r -> flat.getTimeInMcs0AtRow(r) > 0)
                .forEach(r -> sb.append(String.format("  %6.2f%%  %8d ms  %s\n", 100.0 * flat.getTimeInMcs0AtRow(r) / total,
                        flat.getTimeInMcs0AtRow(r) / 1000, flat.getMethodNameAtRow(r))));
        sb.append("\nNach Gesamtzeit (inkl. Aufgerufener):\n");
        rows.stream().sorted(Comparator.comparingLong((Integer r) -> flat.getTotalTimeInMcs0AtRow(r)).reversed()).limit(15)
                .forEach(r -> sb.append(String.format("  %6.2f%%  %s\n", Math.min(100.0, 100.0 * flat.getTotalTimeInMcs0AtRow(r) / total),
                        flat.getMethodNameAtRow(r))));
        sb.append("\nThreads: ").append(String.join(", ", s.getThreadNames()));
        return sb.toString();
    }

    /** Speichert den Snapshot im VisualVM/NetBeans-Profiler-Format (.nps). */
    static void writeNps(CPUResultsSnapshot s, Path file) throws IOException {
        try (OutputStream os = Files.newOutputStream(file);
             DataOutputStream out = new DataOutputStream(os)) {
            out.write("nBpRoFiLeR".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            out.writeByte(1);  // Hauptversion
            out.writeByte(2);  // Nebenversion
            out.writeInt(1);   // Typ: CPU-Snapshot (LoadedSnapshot.SNAPSHOT_TYPE_CPU)
            java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
            try (DataOutputStream bo = new DataOutputStream(body)) {
                s.writeToStream(bo);
            }
            byte[] compressed = deflate(body.toByteArray());
            out.writeInt(compressed.length);
            out.writeInt(body.size());
            out.write(compressed);
            // Einstellungen im .properties-Format (leer = Standardwerte) + Kommentar (VisualVM LoadedSnapshot.save)
            java.io.ByteArrayOutputStream settings = new java.io.ByteArrayOutputStream();
            new java.util.Properties().store(settings, "");
            out.writeInt(settings.size());
            out.write(settings.toByteArray());
            out.writeUTF("Erstellt von DevTools MCP");
        }
    }

    private static byte[] deflate(byte[] in) {
        java.util.zip.Deflater d = new java.util.zip.Deflater();
        d.setInput(in);
        d.finish();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        while (!d.finished()) {
            out.write(buf, 0, d.deflate(buf));
        }
        d.end();
        return out.toByteArray();
    }
}
