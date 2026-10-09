package systems.grebe.devtools.mcp.ui;

import java.util.function.Consumer;
import java.util.function.Supplier;

import javafx.application.Platform;

/** Arbeit außerhalb des FX-Threads, Ergebnis im FX-Thread. */
final class FxTasks {

    private FxTasks() {
    }

    /**
     * Führt {@code work} in einem virtuellen Thread aus (blockierende Aufrufe belegen so nicht den gemeinsamen
     * ForkJoin-Pool) und übergibt das Ergebnis im FX-Thread an {@code onFx}, einen Fehler an {@code onError}.
     */
    static <T> void background(Supplier<T> work, Consumer<? super T> onFx, Consumer<? super Throwable> onError) {
        Thread.ofVirtual().start(() -> {
            T result;
            try {
                result = work.get();
            } catch (Throwable e) {
                Platform.runLater(() -> onError.accept(e));
                return;
            }
            Platform.runLater(() -> onFx.accept(result));
        });
    }
}
