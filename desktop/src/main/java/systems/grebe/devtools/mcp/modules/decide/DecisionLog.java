package systems.grebe.devtools.mcp.modules.decide;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Die letzten Entscheidungen eines Benutzers (im Speicher), damit {@code decide_feedback} die Roh-Logits einer
 * Entscheidung der richtigen Antwort zuordnen kann.
 */
final class DecisionLog {

    static final int CAPACITY = 500;

    /** Eine Entscheidung mit Eingabe, Feldern und Roh-Logits je Einzelfrage und Modell. */
    record Entry(String id, String task, String input, List<DecisionSchema.Field> fields,
                 Map<String, Map<String, float[]>> raw, Instant at) {
    }

    private final AtomicLong counter = new AtomicLong();
    private final Map<String, Entry> entries = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
            return size() > CAPACITY;
        }
    };

    synchronized String add(String task, String input, List<DecisionSchema.Field> fields,
                            Map<String, Map<String, float[]>> raw) {
        String id = "d" + Long.toString(System.currentTimeMillis() % 1_000_000_000L, 36) + "-" + counter.incrementAndGet();
        entries.put(id, new Entry(id, task, input, fields, raw, Instant.now()));
        return id;
    }

    synchronized Optional<Entry> get(String id) {
        return Optional.ofNullable(id == null ? null : entries.get(id.strip()));
    }
}
