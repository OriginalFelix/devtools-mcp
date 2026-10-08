package systems.grebe.devtools.mcp.core;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Anmeldung im Browser (Device-Code) für die Aktionen der App und die {@code *_login}-Tools von Mail und Chat. */
public final class BrowserLogin {

    private static final Pattern URL = Pattern.compile("https://\\S+[^\\s.,;)]");
    private static final long START_SECONDS = 60;

    private BrowserLogin() {
    }

    /** Die erste Adresse der Anweisung oder {@code null}. */
    static String firstUrl(String prompt) {
        Matcher m = URL.matcher(prompt);
        return m.find() ? m.group() : null;
    }

    /** Öffnet die erste Adresse der Anweisung im Browser, wenn das geht (nicht im Headless-Betrieb). */
    public static void open(String prompt) {
        String url = firstUrl(prompt);
        if (url == null || GraphicsEnvironment.isHeadless()) {
            return;
        }
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
            }
        } catch (Exception ignored) {
            // Adresse steht in der Anzeige
        }
    }

    /**
     * Startet die Anmeldung im Hintergrund und liefert die Anweisung für den Nutzer (Adresse und Code), sobald sie da
     * ist; die Anmeldung läuft danach weiter. Endet sie vorher (Fehler oder schon angemeldet), kommt deren Ergebnis.
     *
     * @param login nimmt die Anweisung entgegen und blockiert bis zum Ende der Anmeldung; liefert das Ergebnis
     */
    public static String start(String threadName, Function<Consumer<String>, String> login) {
        CompletableFuture<String> prompt = new CompletableFuture<>();
        Thread.ofVirtual().name(threadName).start(() -> {
            try {
                prompt.complete(login.apply(prompt::complete));
            } catch (RuntimeException ex) {
                prompt.completeExceptionally(ex);
            }
        });
        try {
            return prompt.get(START_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException ex) {
            throw ex.getCause() instanceof RuntimeException r ? r : new IllegalStateException(ex.getCause());
        } catch (TimeoutException ex) {
            throw new IllegalStateException("Anmeldung konnte nicht gestartet werden (keine Antwort in 60 s).");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Abgebrochen", ex);
        }
    }
}
