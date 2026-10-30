package systems.grebe.devtools.mcp.intellij;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;

/** Führt etwas im UI-Thread aus und wartet aus dem HTTP-Thread auf das Ergebnis. */
final class Edt {

    private Edt() {
    }

    static <T> T call(Supplier<T> action) {
        CompletableFuture<T> f = new CompletableFuture<>();
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                f.complete(action.get());
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        }, ModalityState.any());
        try {
            return f.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof RuntimeException r ? r : new IllegalStateException(e.getCause());
        } catch (TimeoutException e) {
            throw new IllegalStateException("Die IDE ist gerade beschäftigt – bitte gleich noch einmal.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("abgebrochen");
        }
    }
}
