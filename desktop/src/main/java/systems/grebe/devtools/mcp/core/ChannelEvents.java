package systems.grebe.devtools.mcp.core;

import java.time.Instant;
import java.util.ArrayDeque;
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

    /**
     * Ergebnis einer Veröffentlichung.
     *
     * @param listeners verbundene Brücken beim Senden
     * @param delivered davon ohne Fehler erreicht
     */
    public record Delivery(long id, int listeners, int delivered) {
    }

    private final Deque<Event> recent = new ArrayDeque<>();
    private final List<Consumer<Event>> listeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> subscribeListeners = new CopyOnWriteArrayList<>();
    /**
     * Ordnet Veröffentlichen und Anmelden: ID-Vergabe, Puffer und Zustellung an die Brücken laufen zusammen darunter,
     * damit kein Ereignis ein älteres überholt (die Brücke verwirft Ereignisse mit kleinerer ID als das zuletzt
     * gesendete) und ein neu angemeldeter Listener verpasste und neue Ereignisse lückenlos in Reihenfolge bekommt.
     * Reihenfolge der Sperren: {@code order}, dann {@code this}.
     */
    private final Object order = new Object();
    private long lastId;

    /** Veröffentlicht ein Ereignis an alle verbundenen Brücken; liefert seine ID. */
    public long publish(String source, String content, Map<String, String> meta) {
        return deliver(source, content, meta).id();
    }

    /**
     * Veröffentlicht ein Ereignis an alle verbundenen Brücken und zählt, wie viele es erreicht hat (eine Brücke, deren
     * Verbindung abgerissen ist, wirft beim Senden).
     */
    public Delivery deliver(String source, String content, Map<String, String> meta) {
        Map<String, String> clean = new LinkedHashMap<>();
        clean.put("event_source", source);
        if (meta != null) {
            meta.forEach((k, v) -> {
                if (k != null && v != null && META_KEY.matcher(k).matches()) {
                    clean.put(k, oneLine(v));
                }
            });
        }
        synchronized (order) {
            Event e;
            synchronized (this) {
                e = new Event(++lastId, source, content, Map.copyOf(clean), Instant.now());
                recent.addLast(e);
                while (recent.size() > BUFFER) {
                    recent.removeFirst();
                }
            }
            int count = 0;
            int delivered = 0;
            for (Consumer<Event> l : listeners) {
                count++;
                try {
                    l.accept(e);
                    delivered++;
                } catch (RuntimeException ex) {
                    LOG.debug("Ereignis {} nicht zustellbar: {}", e.id(), ex.toString());
                }
            }
            return new Delivery(e.id(), count, delivered);
        }
    }

    /**
     * Nimmt ein Ereignis wieder aus dem Puffer – für eines, das niemanden erreicht hat und über anderen Weg (z.B. den
     * {@link InvocationService}) erneut zugestellt wird: Sonst holte eine neu verbundene Brücke es per
     * {@code Last-Event-ID} nach, und es käme ein zweites Mal an.
     */
    public synchronized void forget(long id) {
        recent.removeIf(e -> e.id() == id);
    }

    /** Ereignisse nach {@code id} (für das Nachholen nach einer Unterbrechung). */
    public synchronized List<Event> since(long id) {
        return recent.stream().filter(e -> e.id() > id).toList();
    }

    /**
     * Meldet {@code listener} für neue Ereignisse an und gibt ihm dabei zuerst, was nach {@code afterId} schon da war
     * ({@code afterId < 0}: nichts nachholen) – lückenlos und in Reihenfolge vor allem Neuen. Wirft der Listener dabei,
     * wird er wieder abgemeldet und die Ausnahme weitergereicht. Abmelden sonst über {@link #unsubscribe}.
     */
    public void subscribe(Consumer<Event> listener, long afterId) {
        synchronized (order) {
            listeners.add(listener);
            if (afterId >= 0) {
                try {
                    for (Event e : since(afterId)) {
                        listener.accept(e);
                    }
                } catch (RuntimeException ex) {
                    listeners.remove(listener);
                    throw ex;
                }
            }
        }
        if (!subscribeListeners.isEmpty()) {
            // nicht im Thread des Aufrufers: der richtet die Verbindung erst noch fertig ein
            Thread.ofVirtual().name("channel-subscribed").start(() -> subscribeListeners.forEach(r -> {
                try {
                    r.run();
                } catch (RuntimeException ex) {
                    LOG.warn("Reaktion auf neue Brücke fehlgeschlagen", ex);
                }
            }));
        }
    }

    /** Wird nach jeder neu verbundenen Brücke aufgerufen (z.B. um Liegengebliebenes zuzustellen). */
    public void addSubscribeListener(Runnable listener) {
        subscribeListeners.add(listener);
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
