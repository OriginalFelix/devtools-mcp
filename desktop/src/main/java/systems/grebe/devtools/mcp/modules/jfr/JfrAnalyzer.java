package systems.grebe.devtools.mcp.modules.jfr;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;
import systems.grebe.devtools.mcp.modules.java.ArtifactStore;
import systems.grebe.devtools.mcp.modules.java.IdleFrames;

/** Wertet JFR-Dateien über die JDK-API {@link RecordingFile} aus. */
public final class JfrAnalyzer {

    public enum Aspect { SUMMARY, CPU, ALLOCATION, GC, LOCKS, EXCEPTIONS, IO, THREADS }

    private final Path file;
    private final String packageFilter;
    private final int top;

    public JfrAnalyzer(Path file, String packageFilter, int top) {
        this.file = file;
        this.packageFilter = packageFilter == null || packageFilter.isBlank() ? null : packageFilter.trim();
        this.top = Math.max(3, Math.min(100, top));
    }

    public static Aspect aspect(String s) {
        if (s == null || s.isBlank()) {
            return Aspect.SUMMARY;
        }
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "summary", "overview", "übersicht" -> Aspect.SUMMARY;
            case "cpu", "hotspots", "methods" -> Aspect.CPU;
            case "alloc", "allocation", "allocations", "memory" -> Aspect.ALLOCATION;
            case "gc", "garbage" -> Aspect.GC;
            case "lock", "locks", "contention" -> Aspect.LOCKS;
            case "exception", "exceptions", "errors" -> Aspect.EXCEPTIONS;
            case "io", "i/o", "file", "socket" -> Aspect.IO;
            case "thread", "threads" -> Aspect.THREADS;
            default -> throw new IllegalArgumentException("Unbekannter Aspekt '" + s
                    + "'. Möglich: summary, cpu, allocation, gc, locks, exceptions, io, threads");
        };
    }

    public String analyze(Aspect aspect) {
        return switch (aspect) {
            case SUMMARY -> summary();
            case CPU -> cpu();
            case ALLOCATION -> allocation();
            case GC -> gc();
            case LOCKS -> locks();
            case EXCEPTIONS -> exceptions();
            case IO -> io();
            case THREADS -> threads();
        };
    }

    // ------------------------------------------------------------------ Aspekte

    private String summary() {
        Map<String, Integer> counts = new TreeMap<>();
        Instant[] range = {null, null};
        Map<String, String> meta = new LinkedHashMap<>();
        double[] cpu = {0, 0, 0};
        long[] heap = {0, 0};
        forEach(e -> true, e -> {
            counts.merge(e.getEventType().getName(), 1, Integer::sum);
            Instant s = e.getStartTime();
            if (range[0] == null || s.isBefore(range[0])) {
                range[0] = s;
            }
            if (range[1] == null || e.getEndTime().isAfter(range[1])) {
                range[1] = e.getEndTime();
            }
            switch (e.getEventType().getName()) {
                case "jdk.JVMInformation" -> {
                    meta.put("JVM", e.getString("jvmName") + " " + e.getString("jvmVersion"));
                    meta.put("Start", e.getString("javaArguments") == null ? "" : e.getString("javaArguments"));
                }
                case "jdk.CPULoad" -> {
                    cpu[0] = Math.max(cpu[0], e.getFloat("jvmUser") + e.getFloat("jvmSystem"));
                    cpu[1] += e.getFloat("jvmUser") + e.getFloat("jvmSystem");
                    cpu[2]++;
                }
                case "jdk.GCHeapSummary" -> heap[0] = Math.max(heap[0], e.getLong("heapUsed"));
                case "jdk.GarbageCollection" -> heap[1] += e.getDuration().toNanos();
                default -> { }
            }
        });
        StringBuilder sb = new StringBuilder("JFR-Aufzeichnung ").append(file.getFileName()).append('\n');
        if (range[0] != null) {
            sb.append("Zeitraum: ").append(range[0]).append(" – ").append(range[1]).append(" (")
                    .append(Duration.between(range[0], range[1]).toSeconds()).append(" s)\n");
        }
        meta.forEach((k, v) -> sb.append(k).append(": ").append(v).append('\n'));
        if (cpu[2] > 0) {
            sb.append(String.format("CPU (JVM): Ø %.1f %%, max %.1f %%\n", 100 * cpu[1] / cpu[2], 100 * cpu[0]));
        }
        if (heap[0] > 0) {
            sb.append("Heap max. belegt: ").append(ArtifactStore.humanSize(heap[0])).append('\n');
        }
        sb.append("GC-Pausen gesamt: ").append(heap[1] / 1_000_000).append(" ms\n");
        sb.append("\nEreignisse (Anzahl):\n");
        counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(25)
                .forEach(en -> sb.append(String.format("  %8d  %s\n", en.getValue(), en.getKey())));
        sb.append("\nWeitere Aspekte: cpu, allocation, gc, locks, exceptions, io, threads · Flame Graph: jfr_flamegraph");
        return sb.toString();
    }

    private String cpu() {
        Map<String, Long> self = new HashMap<>();
        Map<String, Long> total = new HashMap<>();
        long[] n = {0};
        forEach(e -> e.getEventType().getName().equals("jdk.ExecutionSample"), e -> {
            List<String> frames = frames(e.getStackTrace());
            if (frames.isEmpty() || !matchesFilter(frames) || IdleFrames.isIdle(stripLine(frames.getFirst()))) {
                return;
            }
            n[0]++;
            self.merge(frames.getFirst(), 1L, Long::sum);
            frames.stream().distinct().forEach(f -> total.merge(f, 1L, Long::sum));
        });
        if (n[0] == 0) {
            return "Keine CPU-Samples (jdk.ExecutionSample) – mit settings=profile aufzeichnen oder Filter prüfen.";
        }
        return "CPU-Samples: " + n[0] + filterNote() + "\n\nTop nach Eigenanteil:\n" + ranking(self, n[0], "")
                + "\nTop nach Gesamtanteil:\n" + ranking(total, n[0], "");
    }

    private String allocation() {
        Map<String, Long> bySite = new HashMap<>();
        Map<String, Long> byClass = new HashMap<>();
        long[] sum = {0};
        forEach(e -> e.getEventType().getName().equals("jdk.ObjectAllocationSample"), e -> {
            long w = e.getLong("weight");
            RecordedClass c = e.getClass("objectClass");
            List<String> frames = frames(e.getStackTrace());
            if (!matchesFilter(frames)) {
                return;
            }
            sum[0] += w;
            byClass.merge(c == null ? "?" : c.getName(), w, Long::sum);
            String site = frames.stream().filter(f -> packageFilter == null || f.startsWith(packageFilter))
                    .findFirst().orElse(frames.isEmpty() ? "?" : frames.getFirst());
            bySite.merge(site, w, Long::sum);
        });
        if (sum[0] == 0) {
            return "Keine Allokationsereignisse (jdk.ObjectAllocationSample) in der Aufzeichnung.";
        }
        return "Allokiert (hochgerechnet): " + ArtifactStore.humanSize(sum[0]) + filterNote()
                + "\n\nNach Klasse:\n" + rankingBytes(byClass, sum[0])
                + "\nNach Allokationsstelle:\n" + rankingBytes(bySite, sum[0]);
    }

    private String gc() {
        List<String> pauses = new ArrayList<>();
        List<long[]> heapSeries = new ArrayList<>();
        Map<String, long[]> byName = new TreeMap<>();
        long[] longest = {0};
        forEach(e -> e.getEventType().getName().equals("jdk.GarbageCollection"), e -> {
            long ms = e.getDuration().toMillis();
            String name = e.getString("name") + " (" + e.getString("cause") + ")";
            long[] v = byName.computeIfAbsent(name, k -> new long[3]);
            v[0]++;
            v[1] += e.getDuration().toNanos();
            v[2] = Math.max(v[2], e.getDuration().toNanos());
            if (ms >= longest[0]) {
                longest[0] = ms;
            }
            if (e.getDuration().toMillis() >= 20) {
                pauses.add(e.getStartTime() + "  " + ms + " ms  " + name);
            }
        });
        forEach(e -> e.getEventType().getName().equals("jdk.GCHeapSummary"), e -> {
            if ("After GC".equals(e.getString("when"))) {
                heapSeries.add(new long[]{e.getStartTime().toEpochMilli(), e.getLong("heapUsed")});
            }
        });
        if (byName.isEmpty()) {
            return "Keine GC-Ereignisse in der Aufzeichnung.";
        }
        StringBuilder sb = new StringBuilder("Garbage Collections:\n");
        byName.forEach((k, v) -> sb.append(String.format("  %5d×  gesamt %6d ms  max %5d ms  %s\n",
                v[0], v[1] / 1_000_000, v[2] / 1_000_000, k)));
        if (!pauses.isEmpty()) {
            sb.append("\nPausen ≥ 20 ms (").append(pauses.size()).append("):\n");
            pauses.stream().limit(top).forEach(p -> sb.append("  ").append(p).append('\n'));
        }
        if (heapSeries.size() >= 2) {
            heapSeries.sort(Comparator.comparingLong(a -> a[0]));
            long first = heapSeries.getFirst()[1];
            long last = heapSeries.getLast()[1];
            sb.append("\nHeap nach GC: Anfang ").append(ArtifactStore.humanSize(first)).append(" → Ende ")
                    .append(ArtifactStore.humanSize(last));
            if (last > first * 1.3 && last - first > 10 * 1024 * 1024) {
                sb.append("  ⚠ steigt – möglicher Speicherleck-Hinweis (Heap-Dump + visualvm_heap_analyze)");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private String locks() {
        Map<String, long[]> byMonitor = new HashMap<>();
        forEach(e -> e.getEventType().getName().equals("jdk.JavaMonitorEnter")
                || e.getEventType().getName().equals("jdk.ThreadPark"), e -> {
            List<String> frames = frames(e.getStackTrace());
            if (!matchesFilter(frames)) {
                return;
            }
            String what;
            if (e.getEventType().getName().equals("jdk.JavaMonitorEnter")) {
                RecordedClass c = e.getClass("monitorClass");
                what = "synchronized " + (c == null ? "?" : c.getName());
            } else {
                RecordedClass c = e.hasField("parkedClass") ? e.getClass("parkedClass") : null;
                what = "park " + (c == null ? "?" : c.getName());
            }
            String site = frames.stream().filter(f -> !f.startsWith("java.") && !f.startsWith("jdk.") && !f.startsWith("sun."))
                    .findFirst().orElse(frames.isEmpty() ? "?" : frames.getFirst());
            long[] v = byMonitor.computeIfAbsent(what + "  @ " + site, k -> new long[2]);
            v[0]++;
            v[1] += e.getDuration().toNanos();
        });
        if (byMonitor.isEmpty()) {
            return "Keine Lock-Wartezeiten aufgezeichnet (jdk.JavaMonitorEnter/ThreadPark; Schwelle im Profil 'profile' 10 ms).";
        }
        StringBuilder sb = new StringBuilder("Lock-Konkurrenz (Anzahl, Wartezeit gesamt):\n");
        byMonitor.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1])).limit(top)
                .forEach(en -> sb.append(String.format("  %5d×  %7d ms  %s\n", en.getValue()[0], en.getValue()[1] / 1_000_000, en.getKey())));
        return sb.toString();
    }

    private String exceptions() {
        Map<String, Long> byType = new HashMap<>();
        Map<String, String> example = new HashMap<>();
        long[] stats = {-1};
        forEach(e -> e.getEventType().getName().equals("jdk.JavaErrorThrow")
                || e.getEventType().getName().equals("jdk.JavaExceptionThrow")
                || e.getEventType().getName().equals("jdk.ExceptionStatistics"), e -> {
            if (e.getEventType().getName().equals("jdk.ExceptionStatistics")) {
                stats[0] = Math.max(stats[0], e.getLong("throwables"));
                return;
            }
            RecordedClass c = e.getClass("thrownClass");
            String type = c == null ? "?" : c.getName();
            byType.merge(type, 1L, Long::sum);
            example.putIfAbsent(type, e.getString("message") + "  @ " + frames(e.getStackTrace()).stream()
                    .filter(f -> !f.startsWith("java.lang.Throwable") && !f.contains(".<init>")).findFirst().orElse("?"));
        });
        StringBuilder sb = new StringBuilder();
        if (stats[0] >= 0) {
            sb.append("Geworfene Throwables seit JVM-Start: ").append(stats[0]).append('\n');
        }
        if (byType.isEmpty()) {
            return sb.append("Keine einzelnen Exception-Ereignisse aufgezeichnet (nur Errors werden standardmäßig erfasst; "
                    + "für alle: jdk.JavaExceptionThrow aktivieren).").toString();
        }
        sb.append("Nach Typ:\n");
        byType.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(top)
                .forEach(en -> sb.append(String.format("  %6d×  %s\n          z.B. %s\n", en.getValue(), en.getKey(), example.get(en.getKey()))));
        return sb.toString();
    }

    private String io() {
        Map<String, long[]> byTarget = new HashMap<>();
        forEach(e -> e.getEventType().getName().matches("jdk\\.(FileRead|FileWrite|SocketRead|SocketWrite)"), e -> {
            String kind = e.getEventType().getName().substring(4);
            String target = e.hasField("path") ? e.getString("path")
                    : e.hasField("host") ? e.getString("host") + ":" + e.getInt("port") : "?";
            long bytes = e.hasField("bytesRead") ? e.getLong("bytesRead") : e.hasField("bytesWritten") ? e.getLong("bytesWritten") : 0;
            long[] v = byTarget.computeIfAbsent(kind + "  " + target, k -> new long[3]);
            v[0]++;
            v[1] += e.getDuration().toNanos();
            v[2] += bytes;
        });
        if (byTarget.isEmpty()) {
            return "Keine I/O-Ereignisse (Schwelle im Profil 'profile': 10 ms – nur langsame Zugriffe werden erfasst).";
        }
        StringBuilder sb = new StringBuilder("Langsame I/O (Anzahl, Dauer gesamt, Bytes):\n");
        byTarget.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1])).limit(top)
                .forEach(en -> sb.append(String.format("  %5d×  %7d ms  %10s  %s\n", en.getValue()[0],
                        en.getValue()[1] / 1_000_000, ArtifactStore.humanSize(en.getValue()[2]), en.getKey())));
        return sb.toString();
    }

    private String threads() {
        Map<String, long[]> cpuByThread = new HashMap<>();
        Map<String, Long> samples = new HashMap<>();
        forEach(e -> e.getEventType().getName().equals("jdk.ThreadCPULoad")
                || e.getEventType().getName().equals("jdk.ExecutionSample"), e -> {
            if (e.getEventType().getName().equals("jdk.ExecutionSample")) {
                RecordedThread t = e.getThread("sampledThread");
                samples.merge(t == null ? "?" : t.getJavaName(), 1L, Long::sum);
                return;
            }
            RecordedThread t = e.getThread("eventThread");
            long[] v = cpuByThread.computeIfAbsent(t == null ? "?" : String.valueOf(t.getJavaName()), k -> new long[2]);
            v[0] += (long) ((e.getFloat("user") + e.getFloat("system")) * 10000);
            v[1]++;
        });
        StringBuilder sb = new StringBuilder();
        if (!samples.isEmpty()) {
            long total = samples.values().stream().mapToLong(Long::longValue).sum();
            sb.append("Threads nach CPU-Samples:\n").append(ranking(samples, total, ""));
        }
        if (!cpuByThread.isEmpty()) {
            sb.append("\nThreads nach Ø CPU-Last:\n");
            cpuByThread.entrySet().stream().sorted((a, b) -> Double.compare(avg(b.getValue()), avg(a.getValue()))).limit(top)
                    .forEach(en -> sb.append(String.format("  %6.2f %%  %s\n", avg(en.getValue()), en.getKey())));
        }
        return sb.isEmpty() ? "Keine Thread-bezogenen Ereignisse." : sb.toString();
    }

    private static double avg(long[] v) {
        return v[1] == 0 ? 0 : v[0] / 100.0 / v[1];
    }

    // ------------------------------------------------------------------ Hilfen

    private void forEach(Predicate<RecordedEvent> filter, java.util.function.Consumer<RecordedEvent> action) {
        try (RecordingFile rf = new RecordingFile(file)) {
            while (rf.hasMoreEvents()) {
                RecordedEvent e = rf.readEvent();
                if (filter.test(e)) {
                    action.accept(e);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("JFR-Datei nicht lesbar (" + file.getFileName() + "): " + e.getMessage(), e);
        }
    }

    private static List<String> frames(RecordedStackTrace st) {
        if (st == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (RecordedFrame f : st.getFrames()) {
            RecordedMethod m = f.getMethod();
            if (m == null) {
                continue;
            }
            String cls = m.getType() == null ? "?" : m.getType().getName();
            out.add(cls + "." + m.getName() + (f.getLineNumber() > 0 ? ":" + f.getLineNumber() : ""));
        }
        return out;
    }

    private static String stripLine(String frame) {
        int colon = frame.lastIndexOf(':');
        return colon > 0 ? frame.substring(0, colon) : frame;
    }

    private boolean matchesFilter(List<String> frames) {
        return packageFilter == null || frames.stream().anyMatch(f -> f.startsWith(packageFilter));
    }

    private String filterNote() {
        return packageFilter == null ? "" : " (nur Stacks mit " + packageFilter + ")";
    }

    private String ranking(Map<String, Long> m, long total, String unit) {
        StringBuilder sb = new StringBuilder();
        m.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(top)
                .forEach(en -> sb.append(String.format("  %6.2f%%  %s%s\n", 100.0 * en.getValue() / total, en.getKey(), unit)));
        return sb.toString();
    }

    private String rankingBytes(Map<String, Long> m, long total) {
        StringBuilder sb = new StringBuilder();
        m.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(top)
                .forEach(en -> sb.append(String.format("  %6.2f%%  %10s  %s\n", 100.0 * en.getValue() / total,
                        ArtifactStore.humanSize(en.getValue()), en.getKey())));
        return sb.toString();
    }
}
