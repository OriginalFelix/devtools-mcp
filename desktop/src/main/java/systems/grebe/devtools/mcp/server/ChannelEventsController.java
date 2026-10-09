package systems.grebe.devtools.mcp.server;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import jakarta.annotation.PreDestroy;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import systems.grebe.devtools.mcp.core.ChannelEvents;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /mcp/channel/events}: die {@link ChannelEvents} als Server-Sent Events für die Channel-Brücke. Liegt unter
 * {@code /mcp} und ist damit wie der MCP-Endpunkt durch das Zugriffstoken geschützt ({@link BearerTokenFilter}).
 *
 * <p>Ohne {@code Last-Event-ID} kommen nur Ereignisse ab dem Verbinden; mit (nach einer Unterbrechung) auch die
 * verpassten, soweit sie noch im Puffer sind. Ein Kommentar alle {@link #HEARTBEAT_SECONDS} Sekunden hält die Verbindung
 * offen und lässt tote Verbindungen auffallen.
 */
@RestController
public class ChannelEventsController {

    public static final String PATH = "/mcp/channel/events";
    static final int HEARTBEAT_SECONDS = 20;
    private static final JsonMapper JSON = JsonMapper.shared();

    private final ChannelEvents events;
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("channel-heartbeat").factory());

    public ChannelEventsController(ChannelEvents events) {
        this.events = events;
    }

    @GetMapping(path = PATH, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
        SseEmitter emitter = new SseEmitter(0L);
        Stream stream = new Stream(emitter);
        long after = parse(lastEventId);
        try {
            events.subscribe(stream, after); // spielt Verpasstes zuerst und lückenlos nach
        } catch (RuntimeException e) {
            return emitter; // Verbindung schon wieder weg (subscribe hat den Listener abgemeldet)
        }
        ScheduledFuture<?> beat = heartbeat.scheduleAtFixedRate(stream::ping, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS,
                TimeUnit.SECONDS);
        Runnable cleanup = () -> {
            events.unsubscribe(stream);
            beat.cancel(false);
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(t -> cleanup.run());
        stream.ping(); // Antwort sofort beginnen: die Brücke sieht, dass die Verbindung steht
        return emitter;
    }

    @PreDestroy
    void stop() {
        heartbeat.shutdownNow();
    }

    private static long parse(String id) {
        try {
            return id == null || id.isBlank() ? -1 : Long.parseLong(id.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Ein verbundener Client; sendet jedes Ereignis höchstens einmal und in Reihenfolge. Scheitert das Senden, wirft
     * {@link #accept} – so zählt {@link ChannelEvents#deliver} nur, was die Brücke erreicht hat.
     */
    private static final class Stream implements Consumer<ChannelEvents.Event> {
        private final SseEmitter emitter;
        private long lastSent;

        Stream(SseEmitter emitter) {
            this.emitter = emitter;
        }

        @Override
        public synchronized void accept(ChannelEvents.Event e) {
            if (e.id() <= lastSent) {
                return;
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("source", e.source());
            data.put("content", e.content());
            data.put("meta", e.meta());
            data.put("time", e.time().toString());
            try {
                emitter.send(SseEmitter.event().id(Long.toString(e.id())).name("channel")
                        .data(JSON.writeValueAsString(data))); // JSON in einer Zeile, als Text gesendet
                lastSent = e.id();
            } catch (IOException | IllegalStateException ex) {
                emitter.completeWithError(ex);
                throw new IllegalStateException("Brücke nicht erreichbar: " + ex.getMessage(), ex);
            }
        }

        synchronized void ping() {
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (IOException | IllegalStateException ex) {
                emitter.completeWithError(ex);
            }
        }
    }
}
