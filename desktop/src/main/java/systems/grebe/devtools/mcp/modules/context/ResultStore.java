package systems.grebe.devtools.mcp.modules.context;

import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

/**
 * Vollständige Tool-Ergebnisse unter einem kurzen Handle ({@code r12}), damit das LLM aus einem gekürzten Ergebnis
 * gezielt nachlesen kann ({@code context_slice}), statt das Tool erneut mit größerem Limit aufzurufen.
 *
 * <p>Nur im Speicher und begrenzt (Anzahl und Gesamtgröße); die ältesten fallen zuerst heraus. Ein Neustart der App
 * leert die Ablage – das LLM bekommt dann eine Meldung und ruft das Tool neu auf.
 */
@Component
public class ResultStore {

    static final int MAX_ENTRIES = 300;
    /** ≈ 32 MB Heap (UTF-16) im ungünstigsten Fall. */
    static final long MAX_CHARS = 16_000_000L;

    /** Ein abgelegtes Ergebnis. */
    public record Stored(String handle, String tool, String input, String sessionId, String text, Instant at) {

        public int lines() {
            return ContextText.lines(text).size();
        }
    }

    private final AtomicLong ids = new AtomicLong();
    private final Map<String, Stored> entries = new LinkedHashMap<>(64, 0.75f, true);
    private long chars;

    /** Legt ab und liefert das Handle. */
    public Stored put(String tool, String input, String sessionId, String text) {
        Stored s = new Stored("r" + ids.incrementAndGet(), tool, input, sessionId, text == null ? "" : text,
                Instant.now());
        synchronized (entries) {
            entries.put(s.handle(), s);
            chars += s.text().length();
            Iterator<Stored> it = entries.values().iterator();
            while ((entries.size() > MAX_ENTRIES || chars > MAX_CHARS) && it.hasNext()) {
                Stored old = it.next();
                if (old == s) {
                    break;
                }
                chars -= old.text().length();
                it.remove();
            }
        }
        return s;
    }

    public Optional<Stored> get(String handle) {
        if (handle == null) {
            return Optional.empty();
        }
        String h = handle.strip();
        if (!h.isEmpty() && Character.isDigit(h.charAt(0))) {
            h = "r" + h;
        }
        synchronized (entries) {
            return Optional.ofNullable(entries.get(h));
        }
    }

    public int size() {
        synchronized (entries) {
            return entries.size();
        }
    }
}
