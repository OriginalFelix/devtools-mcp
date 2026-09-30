package systems.grebe.devtools.mcp.modules.java;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import one.convert.Arguments;
import one.convert.Main;

/**
 * Stack-Profile aus JFR-Dateien (JDK oder async-profiler): Flame Graph (HTML) und textuelle Auswertung
 * (Self-/Total-Anteile, heißeste Aufrufpfade). Basis ist der jfr-converter von async-profiler.
 */
public final class StackProfile {

    /** Art des Profils. */
    public enum Kind {
        CPU("CPU", null, "Samples"),
        WALL("Wall-Clock", "--wall", "Samples"),
        ALLOC("Allokationen", "--alloc", "Bytes"),
        LOCK("Lock-Konkurrenz", "--lock", "ns");

        final String label;
        final String flag;
        final String unit;

        Kind(String label, String flag, String unit) {
            this.label = label;
            this.flag = flag;
            this.unit = unit;
        }

        public static Kind parse(String s) {
            if (s == null || s.isBlank()) {
                return CPU;
            }
            return switch (s.trim().toLowerCase(Locale.ROOT)) {
                case "cpu", "itimer", "ctimer" -> CPU;
                case "wall" -> WALL;
                case "alloc", "allocation", "allocations", "memory" -> ALLOC;
                case "lock", "locks", "monitor" -> LOCK;
                default -> throw new IllegalArgumentException("Unbekannte Profilart '" + s + "' (cpu, wall, alloc, lock)");
            };
        }
    }

    public record Frame(String name, long self, long total) { }

    public record Stack(List<String> frames, long weight) { }

    private final Kind kind;
    private final long totalWeight;
    private final List<Stack> stacks;

    private StackProfile(Kind kind, List<Stack> stacks) {
        this.kind = kind;
        this.stacks = stacks;
        this.totalWeight = stacks.stream().mapToLong(Stack::weight).sum();
    }

    /** Erzeugt Flame Graph (HTML) im Ablageordner und liest das Profil ein. */
    public static Result fromJfr(Path jfr, Kind kind, String include, String exclude, String title, ArtifactStore store) {
        List<String> common = new ArrayList<>(List.of("--dot"));
        if (kind.flag != null) {
            common.add(kind.flag);
        }
        if (include != null && !include.isBlank()) {
            common.addAll(List.of("-I", include));
        }
        // Wartende native Methoden (accept, poll …) gelten für die JVM als RUNNABLE – für CPU-Profile ausblenden
        String ex = kind == Kind.CPU
                ? (exclude == null || exclude.isBlank() ? IdleFrames.REGEX : "(" + exclude + ")|" + IdleFrames.REGEX)
                : exclude;
        if (ex != null && !ex.isBlank()) {
            common.addAll(List.of("-X", ex));
        }
        String base = jfr.getFileName().toString().replaceFirst("\\.jfr$", "").replaceFirst("^\\d{8}-\\d{6}-\\d{3}-", "");
        if (base.length() > 40) {
            base = base.substring(0, 40);
        }
        Path html = store.newFile(base + "-" + kind.name().toLowerCase(Locale.ROOT) + "-flamegraph", "html");
        Path collapsed;
        try {
            collapsed = Files.createTempFile("devtools-mcp-", ".collapsed");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            List<String> htmlArgs = new ArrayList<>(common);
            htmlArgs.addAll(List.of("-o", "html", "--title", title == null ? kind.label + " – " + base : title));
            convert(jfr, html, htmlArgs);
            List<String> colArgs = new ArrayList<>(common);
            colArgs.addAll(List.of("-o", "collapsed"));
            convert(jfr, collapsed, colArgs);
            StackProfile p = parseCollapsed(kind, Files.readAllLines(collapsed, StandardCharsets.UTF_8));
            if (kind == Kind.CPU) {
                p = p.withoutIdle();
            }
            return new Result(p, store.commit(html));
        } catch (IOException e) {
            throw new UncheckedIOException("JFR-Datei nicht lesbar: " + e.getMessage(), e);
        } finally {
            try {
                Files.deleteIfExists(collapsed);
            } catch (IOException ignored) {
                // egal
            }
        }
    }

    public record Result(StackProfile profile, Path flameGraph) { }

    private static void convert(Path in, Path out, List<String> args) throws IOException {
        Main.convert(in.toString(), out.toString(), new Arguments(args.toArray(String[]::new)));
    }

    private static final Pattern TYPE_SUFFIX = Pattern.compile("_\\[[a-z0-9]\\]$");

    static StackProfile parseCollapsed(Kind kind, List<String> lines) {
        List<Stack> stacks = new ArrayList<>();
        for (String line : lines) {
            int sp = line.lastIndexOf(' ');
            if (sp <= 0) {
                continue;
            }
            long w;
            try {
                w = Long.parseLong(line.substring(sp + 1).trim());
            } catch (NumberFormatException e) {
                continue;
            }
            List<String> frames = new ArrayList<>();
            for (String f : line.substring(0, sp).split(";")) {
                frames.add(TYPE_SUFFIX.matcher(f).replaceFirst(""));
            }
            stacks.add(new Stack(frames, w));
        }
        return new StackProfile(kind, stacks);
    }

    public Kind kind() {
        return kind;
    }

    /** Entfernt Stacks, deren oberster Frame eine wartende native Methode ist (Sicherheitsnetz zu {@code -X}). */
    StackProfile withoutIdle() {
        return new StackProfile(kind, stacks.stream().filter(s -> !IdleFrames.isIdle(s.frames().getLast())).toList());
    }

    public long totalWeight() {
        return totalWeight;
    }

    public boolean isEmpty() {
        return totalWeight == 0;
    }

    /** Methoden nach Eigenanteil (oberster Frame). */
    public List<Frame> topSelf(int n) {
        return frames().stream().filter(f -> f.self() > 0).sorted(Comparator.comparingLong(Frame::self).reversed()).limit(n).toList();
    }

    /** Methoden nach Gesamtanteil (irgendwo im Stack). */
    public List<Frame> topTotal(int n) {
        return frames().stream().sorted(Comparator.comparingLong(Frame::total).reversed()).limit(n).toList();
    }

    private List<Frame> frames() {
        Map<String, long[]> m = new HashMap<>();
        for (Stack s : stacks) {
            m.computeIfAbsent(s.frames().getLast(), k -> new long[2])[0] += s.weight();
            Set<String> seen = new HashSet<>();
            for (String f : s.frames()) {
                if (seen.add(f)) {
                    m.computeIfAbsent(f, k -> new long[2])[1] += s.weight();
                }
            }
        }
        List<Frame> out = new ArrayList<>();
        m.forEach((k, v) -> out.add(new Frame(k, v[0], v[1])));
        return out;
    }

    /** Die schwersten vollständigen Aufrufpfade (gekürzt auf die obersten Frames). */
    public List<Stack> hottestStacks(int n) {
        return stacks.stream().sorted(Comparator.comparingLong(Stack::weight).reversed()).limit(n).toList();
    }

    /** LLM-freundliche Zusammenfassung. */
    public String summary(int topN, int stackCount, int stackDepth) {
        if (isEmpty()) {
            return "Keine " + kind.label + "-Samples in der Aufzeichnung (Profilart passend? JFR-Profil 'profile' verwenden, "
                    + "Messdauer verlängern oder Last erzeugen).";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(kind.label).append("-Profil: ").append(totalWeight).append(' ').append(kind.unit)
                .append(", ").append(stacks.size()).append(" verschiedene Stacks\n\n");
        sb.append("Top ").append(topN).append(" nach Eigenanteil (self):\n");
        for (Frame f : topSelf(topN)) {
            sb.append(String.format("  %6.2f%%  %s\n", pct(f.self()), f.name()));
        }
        sb.append("\nTop ").append(topN).append(" nach Gesamtanteil (inkl. Aufgerufener):\n");
        for (Frame f : topTotal(topN)) {
            sb.append(String.format("  %6.2f%%  %s\n", pct(f.total()), f.name()));
        }
        sb.append("\nHeißeste Aufrufpfade (oberster Frame zuerst):\n");
        for (Stack s : hottestStacks(stackCount)) {
            sb.append(String.format("  %6.2f%%\n", pct(s.weight())));
            List<String> fr = s.frames();
            for (int i = fr.size() - 1, d = 0; i >= 0 && d < stackDepth; i--, d++) {
                sb.append("      ").append(fr.get(i)).append('\n');
            }
            if (fr.size() > stackDepth) {
                sb.append("      … ").append(fr.size() - stackDepth).append(" weitere Frames\n");
            }
        }
        return sb.toString().stripTrailing();
    }

    private double pct(long v) {
        return totalWeight == 0 ? 0 : 100.0 * v / totalWeight;
    }
}
