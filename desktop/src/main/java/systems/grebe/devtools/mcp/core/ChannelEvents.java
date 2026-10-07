package systems.grebe.devtools.mcp.core;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Ereignisse, mit denen die App eine laufende LLM-Sitzung von sich aus anstößt (z.B. „neue E-Mail“).
 *
 * <p>MCP über Streamable HTTP kennt keinen Weg, dem Modell ungefragt etwas zu sagen. Claude Code bietet dafür
 * <em>Channels</em> ({@code notifications/claude/channel}) – aber nur für Server, die es selbst per stdio startet. Der
 * stdio-Proxy {@code java -jar devtools-mcp.jar stdio} ({@link systems.grebe.devtools.mcp.channel.ChannelBridge}) ist so
 * ein Server: Er holt die Ereignisse über {@code GET /mcp/channel/events} (Server-Sent Events) von hier ab und reicht
 * sie an Claude Code weiter.
 *
 * <p>Die letzten {@link #BUFFER} Ereignisse bleiben im Speicher, damit eine kurz unterbrochene Brücke mit
 * {@code Last-Event-ID} nachholen kann, was sie verpasst hat.
 */
@Component
public class ChannelEvents {

    private static final Logger LOG = LoggerFactory.getLogger(ChannelEvents.class);

    static final int BUFFER = 200;
    /** Claude Code verwirft Attribute, deren Name kein Bezeichner ist. */
    private static final Pattern META_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final int MAX_META_VALUE = 300;

    /**
     * @param id      fortlaufend, beginnt bei 1 – auch über alle Quellen
     * @param source  Herkunft, z.B. {@code mail}
     * @param content Text für das Modell
     * @param meta    Attribute des {@code <channel>}-Tags (Schlüssel nur Buchstaben, Ziffern, Unterstrich)
     */
    public record Event(long id, String source, String content, Map<String, String> meta, Instant time) {
    }

    private final Deque<Event> recent = new ArrayDeque<>();
    private final List<Consumer<Event>> listeners = new CopyOnWriteArrayList<>();
    private long lastId;

    /** Veröffentlicht ein Ereignis an alle verbundenen Brücken; liefert seine ID. */
    public long publish(String source, String content, Map<String, String> meta) {
        Map<String, String> clean = new LinkedHashMap<>();
        clean.put("event_source", source);
        if (meta != null) {
            meta.forEach((k, v) -> {
                if (k != null && v != null && META_KEY.matcher(k).matches()) {
                    clean.put(k, oneLine(v));
                }
            });
        }
        Event e;
        synchronized (this) {
            e = new Event(++lastId, source, content, Map.copyOf(clean), Instant.now());
            recent.addLast(e);
            while (recent.size() > BUFFER) {
                recent.removeFirst();
            }
        }
        for (Consumer<Event> l : listeners) {
            try {
                l.accept(e);
            } catch (RuntimeException ex) {
                LOG.debug("Ereignis {} nicht zustellbar: {}", e.id(), ex.toString());
            }
        }
        return e.id();
    }

    /** Ereignisse nach {@code id} (für das Nachholen nach einer Unterbrechung). */
    public synchronized List<Event> since(long id) {
        return recent.stream().filter(e -> e.id() > id).toList();
    }

    /**
     * Meldet {@code listener} für neue Ereignisse an und liefert dabei atomar, was nach {@code afterId} schon da war
     * ({@code afterId < 0}: nichts nachholen). Abmelden über {@link #unsubscribe}.
     */
    public List<Event> subscribe(Consumer<Event> listener, long afterId) {
        synchronized (this) {
            listeners.add(listener);
            return afterId < 0 ? List.of() : new ArrayList<>(since(afterId));
        }
    }

    public void unsubscribe(Consumer<Event> listener) {
        listeners.remove(listener);
    }

    /** Zahl der verbundenen Brücken. */
    public int subscribers() {
        return listeners.size();
    }

    private static String oneLine(String v) {
        String s = v.replaceAll("[\\p{Cntrl}\\p{Zl}\\p{Zp}]+", " ").strip();
        return s.length() > MAX_META_VALUE ? s.substring(0, MAX_META_VALUE - 1) + "…" : s;
    }
}
