package systems.grebe.devtools.mcp.ui;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javafx.application.Platform;

/**
 * Hintergrundabfragen einer Ansicht, von denen nur die zuletzt gestartete zählt: Klickt der Nutzer schnell auf A und
 * dann auf B und antwortet A später, würde sein Ergebnis sonst die Auswahl B überschreiben (Details, Editorinhalt).
 * Ergebnisse und Fehler überholter Abfragen werden verworfen.
 *
 * <p>Pro Ansicht und Art von Abfrage (Details, Suche, Laden) eine Instanz; die Prüfung geschieht im FX-Thread, in dem
 * auch neue Abfragen gestartet werden - so gibt es keine Lücke zwischen Prüfen und Anzeigen.
 */
final class LatestOnly {

    private final AtomicLong generation = new AtomicLong();
    private final Executor background;
    private final Executor fx;

    LatestOnly() {
        this(task -> Thread.ofVirtual().start(task), Platform::runLater);
    }

    /** Für Tests: Ausführer für die Arbeit und für die Anzeige. */
    LatestOnly(Executor background, Executor fx) {
        this.background = background;
        this.fx = fx;
    }

    /**
     * Führt {@code work} im Hintergrund aus und zeigt das Ergebnis im FX-Thread - sofern inzwischen keine neuere
     * Abfrage gestartet oder {@link #cancel()} aufgerufen wurde.
     */
    <T> void submit(Supplier<T> work, Consumer<? super T> onFx, Consumer<? super RuntimeException> onError) {
        long mine = generation.incrementAndGet();
        background.execute(() -> {
            try {
                T result = work.get();
                fx.execute(() -> {
                    if (generation.get() == mine) {
                        onFx.accept(result);
                    }
                });
            } catch (RuntimeException e) {
                fx.execute(() -> {
                    if (generation.get() == mine) {
                        onError.accept(e);
                    }
                });
            }
        });
    }

    /** Verwirft laufende Abfragen, z.B. wenn die Auswahl aufgehoben wurde. */
    void cancel() {
        generation.incrementAndGet();
    }
}
