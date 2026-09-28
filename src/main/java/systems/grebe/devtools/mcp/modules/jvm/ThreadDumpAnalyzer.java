package systems.grebe.devtools.mcp.modules.jvm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Wertet die Ausgabe von {@code Thread.print} aus: Zustände, Deadlocks, gruppierte identische Stacks. */
public final class ThreadDumpAnalyzer {

    public record ThreadEntry(String name, boolean daemon, String state, List<String> frames, List<String> locks) { }

    private static final Pattern HEADER = Pattern.compile("^\"(.+?)\"\\s.*$");
    private static final Pattern STATE = Pattern.compile("^\\s+java\\.lang\\.Thread\\.State: (\\S+)");

    private final List<ThreadEntry> threads = new ArrayList<>();
    private final String deadlockSection;

    public ThreadDumpAnalyzer(String dump) {
        String[] lines = dump.split("\\R");
        String name = null;
        boolean daemon = false;
        String state = "UNKNOWN";
        List<String> frames = new ArrayList<>();
        List<String> locks = new ArrayList<>();
        StringBuilder deadlock = new StringBuilder();
        boolean inDeadlock = false;
        for (String line : lines) {
            if (line.startsWith("Found ") && line.contains("deadlock")) {
                inDeadlock = true;
            }
            if (inDeadlock) {
                deadlock.append(line).append('\n');
                continue;
            }
            Matcher h = HEADER.matcher(line);
            if (h.matches()) {
                if (name != null) {
                    threads.add(new ThreadEntry(name, daemon, state, frames, locks));
                }
                name = h.group(1);
                daemon = line.contains(" daemon ");
                state = line.contains("nid=") && !line.contains("java.lang.Thread") ? "NATIVE/VM" : "UNKNOWN";
                frames = new ArrayList<>();
                locks = new ArrayList<>();
                continue;
            }
            if (name == null) {
                continue;
            }
            Matcher s = STATE.matcher(line);
            if (s.find()) {
                state = s.group(1);
            } else if (line.trim().startsWith("at ")) {
                frames.add(line.trim().substring(3));
            } else if (line.trim().startsWith("- ")) {
                locks.add(line.trim().substring(2));
            }
        }
        if (name != null) {
            threads.add(new ThreadEntry(name, daemon, state, frames, locks));
        }
        this.deadlockSection = deadlock.toString().strip();
    }

    public List<ThreadEntry> threads() {
        return threads;
    }

    public boolean hasDeadlock() {
        return !deadlockSection.isEmpty();
    }

    /** Zusammenfassung für das LLM. */
    public String summary(String nameFilter, String stateFilter, int maxGroups, int depth) {
        List<ThreadEntry> selected = threads.stream()
                .filter(t -> nameFilter == null || nameFilter.isBlank()
                        || t.name().toLowerCase(Locale.ROOT).contains(nameFilter.toLowerCase(Locale.ROOT)))
                .filter(t -> stateFilter == null || stateFilter.isBlank() || t.state().equalsIgnoreCase(stateFilter))
                .toList();
        StringBuilder sb = new StringBuilder();
        Map<String, Integer> states = new TreeMap<>();
        threads.forEach(t -> states.merge(t.state(), 1, Integer::sum));
        sb.append("Threads: ").append(threads.size()).append(" (").append(threads.stream().filter(ThreadEntry::daemon).count())
                .append(" Daemon)  Zustände: ").append(states).append('\n');
        if (hasDeadlock()) {
            sb.append("\n!!! DEADLOCK GEFUNDEN !!!\n").append(deadlockSection.lines().limit(60)
                    .reduce((a, b) -> a + "\n" + b).orElse("")).append("\n");
        } else {
            sb.append("Kein Deadlock erkannt.\n");
        }
        // Gruppen identischer Stacks (nur Threads mit Java-Frames)
        Map<String, List<ThreadEntry>> groups = new LinkedHashMap<>();
        for (ThreadEntry t : selected) {
            if (t.frames().isEmpty()) {
                continue;
            }
            String key = t.state() + "|" + String.join("|", t.frames().subList(0, Math.min(depth, t.frames().size())));
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(t);
        }
        List<List<ThreadEntry>> sorted = new ArrayList<>(groups.values());
        sorted.sort((a, b) -> {
            int c = Integer.compare(b.size(), a.size());
            return c != 0 ? c : Integer.compare(rank(a.getFirst().state()), rank(b.getFirst().state()));
        });
        sb.append("\n").append(selected.size()).append(" ausgewählte Threads in ").append(groups.size())
                .append(" Gruppen gleicher Stacks (größte zuerst):\n");
        int shown = 0;
        for (List<ThreadEntry> g : sorted) {
            if (shown++ >= maxGroups) {
                sb.append("… ").append(sorted.size() - maxGroups).append(" weitere Gruppen (Filter name/state nutzen)\n");
                break;
            }
            ThreadEntry first = g.getFirst();
            sb.append("\n[").append(g.size()).append("x ").append(first.state()).append("] ")
                    .append(names(g)).append('\n');
            first.frames().stream().limit(depth).forEach(f -> sb.append("    at ").append(f).append('\n'));
            if (first.frames().size() > depth) {
                sb.append("    … ").append(first.frames().size() - depth).append(" weitere Frames\n");
            }
            first.locks().stream().limit(4).forEach(l -> sb.append("    - ").append(l).append('\n'));
        }
        return sb.toString().stripTrailing();
    }

    private static int rank(String state) {
        return switch (state) {
            case "BLOCKED" -> 0;
            case "RUNNABLE" -> 1;
            default -> 2;
        };
    }

    private static String names(List<ThreadEntry> g) {
        List<String> n = g.stream().map(ThreadEntry::name).limit(5).toList();
        return String.join(", ", n) + (g.size() > 5 ? ", …" : "");
    }
}
