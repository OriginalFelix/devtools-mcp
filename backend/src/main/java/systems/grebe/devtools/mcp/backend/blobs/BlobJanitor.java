package systems.grebe.devtools.mcp.backend.blobs;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Räumt die {@link BlobStore Dateiablage} auf – kurz nach dem Start und dann täglich: abgebrochene Uploads und Inhalte,
 * auf die nichts mehr verweist (z.B. hochgeladen, aber nie angehängt), sobald sie einen Tag alt sind.
 */
@Component
public class BlobJanitor implements AutoCloseable {

    static final Duration MIN_AGE = Duration.ofDays(1);
    private static final Logger LOG = LoggerFactory.getLogger(BlobJanitor.class);

    private final BlobStore store;
    private final BlobReferences references;
    private ScheduledExecutorService timer;

    public BlobJanitor(BlobStore store, BlobReferences references) {
        this.store = store;
        this.references = references;
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (timer != null) {
            return;
        }
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "blob-janitor");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleWithFixedDelay(this::sweepQuietly, 1, Duration.ofDays(1).toMinutes(), TimeUnit.MINUTES);
    }

    /** Entfernt verwaiste Inhalte, die älter als einen Tag sind. */
    public int sweep() {
        int deleted = store.sweep(references.all(), Instant.now().minus(MIN_AGE));
        if (deleted > 0) {
            LOG.info("Dateiablage aufgeräumt: {} verwaiste Datei(en) gelöscht", deleted);
        }
        return deleted;
    }

    private void sweepQuietly() {
        try {
            sweep();
        } catch (RuntimeException e) {
            LOG.warn("Dateiablage nicht aufgeräumt: {}", e.getMessage());
        }
    }

    @Override
    public synchronized void close() {
        if (timer != null) {
            timer.shutdownNow();
            timer = null;
        }
    }
}
