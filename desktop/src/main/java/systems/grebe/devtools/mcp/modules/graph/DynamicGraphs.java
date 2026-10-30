package systems.grebe.devtools.mcp.modules.graph;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import systems.grebe.devtools.mcp.modules.graph.CodeGraph.GraphFile;

/**
 * Dynamische (virtuelle) Graphen: nur im Speicher der App, nie gespeichert. Jeder bekommt eine ID {@code dyn:<n>}, die
 * die {@code graph_*}-Tools statt eines Projekts annehmen, und verfällt, wenn er {@code ttl} lang nicht benutzt wurde
 * (spätestens beim Neustart der App). Höchstens {@value #MAX} gleichzeitig – der am längsten unbenutzte weicht.
 */
final class DynamicGraphs {

    static final int MAX = 5;
    private static final Pattern ID = Pattern.compile("dyn:\\d+");
    private static final AtomicInteger NEXT = new AtomicInteger();
    private static final Map<String, Entry> GRAPHS = new LinkedHashMap<>(8, 0.75f, true);
    private static ScheduledExecutorService cleaner;

    /**
     * Ein dynamischer Graph.
     *
     * @param root        Projektwurzel (für {@code graph_read}), bei reinen Bibliotheksgraphen {@code null}
     * @param description Inhalt für Ausgaben, z.B. „Projekt shop + org.springframework.web.*“
     */
    record Entry(String id, MemoryGraphReader reader, String project, String root, String description, Instant created,
                 Duration ttl, Instant[] lastUse) {

        Instant expires() {
            return lastUse[0].plus(ttl);
        }
    }

    private DynamicGraphs() {
    }

    static boolean isId(String project) {
        return project != null && ID.matcher(project.strip()).matches();
    }

    static synchronized Entry put(GraphFile data, String project, String root, String description, Duration ttl) {
        purge();
        while (GRAPHS.size() >= MAX) {
            GRAPHS.remove(GRAPHS.keySet().iterator().next());
        }
        String id = "dyn:" + NEXT.incrementAndGet();
        Instant now = Instant.now();
        MemoryGraphReader reader = new MemoryGraphReader(new CodeGraph(data), null, null,
                "temporär im Speicher (" + id + ")");
        Entry e = new Entry(id, reader, project, root, description, now, ttl, new Instant[] {now});
        GRAPHS.put(id, e);
        if (cleaner == null) {
            cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "graph-dynamic-cleaner");
                t.setDaemon(true);
                return t;
            });
            cleaner.scheduleWithFixedDelay(DynamicGraphs::cleanup, 1, 1, TimeUnit.MINUTES);
        }
        return e;
    }

    /** Graph zur ID; verlängert seine Lebensdauer. */
    static synchronized Entry get(String id) {
        purge();
        Entry e = GRAPHS.get(id.strip());
        if (e == null) {
            throw new IllegalArgumentException("Dynamischer Graph " + id.strip() + " existiert nicht (mehr) – "
                    + "verfallen, entfernt oder nach Neustart weg. Mit graph_dynamic neu anlegen."
                    + (GRAPHS.isEmpty() ? "" : " Vorhanden: " + String.join(", ", GRAPHS.keySet())));
        }
        e.lastUse()[0] = Instant.now();
        return e;
    }

    static synchronized boolean drop(String id) {
        return GRAPHS.remove(id.strip()) != null;
    }

    static synchronized List<Entry> list() {
        purge();
        return new ArrayList<>(GRAPHS.values());
    }

    private static synchronized void cleanup() {
        purge();
    }

    private static void purge() {
        Instant now = Instant.now();
        GRAPHS.values().removeIf(e -> e.expires().isBefore(now));
    }

    /** Für Tests. */
    static synchronized void clear() {
        GRAPHS.clear();
    }
}
