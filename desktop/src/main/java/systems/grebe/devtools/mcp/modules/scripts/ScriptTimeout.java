package systems.grebe.devtools.mcp.modules.scripts;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Zeitlimit für Skript-Code im Thread des Aufrufers: Nach Ablauf wird der Thread unterbrochen. Damit das auch
 * Endlosschleifen beendet, übersetzt {@link ScriptCompiler} jedes Skript mit {@code @ThreadInterrupt} (Prüfung am
 * Anfang jeder Schleife und Methode). Blockierende Aufrufe ohne Interrupt-Unterstützung (z.B. manches Socket-I/O)
 * bricht das nicht ab.
 */
final class ScriptTimeout {

    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "script-timeout");
        t.setDaemon(true);
        return t;
    });

    private ScriptTimeout() {
    }

    /** Ausnahme, wenn das Zeitlimit abgelaufen ist. */
    static final class Exceeded extends IllegalStateException {
        Exceeded(String what, Duration limit) {
            super(what + " hat das Zeitlimit von " + limit.toSeconds() + " s überschritten und wurde abgebrochen.");
        }
    }

    /**
     * Führt {@code body} aus und unterbricht den Thread nach {@code limit}. Ein Interrupt-Flag, das das Zeitlimit
     * gesetzt hat, bleibt nicht am Thread hängen.
     */
    static <T> T run(String what, Duration limit, Supplier<T> body) {
        Thread caller = Thread.currentThread();
        Object guard = new Object();
        boolean[] state = new boolean[2]; // [0] fertig, [1] abgebrochen
        ScheduledFuture<?> timer = TIMER.schedule(() -> {
            synchronized (guard) {
                if (!state[0]) {
                    state[1] = true;
                    caller.interrupt();
                }
            }
        }, Math.max(1, limit.toMillis()), TimeUnit.MILLISECONDS);
        try {
            return body.get();
        } catch (RuntimeException e) {
            if (timedOut(guard, state)) {
                throw new Exceeded(what, limit);
            }
            throw e;
        } catch (Error e) {
            throw e;
        } catch (Throwable e) { // InterruptedException aus @ThreadInterrupt kommt ungeprüft durch
            if (timedOut(guard, state)) {
                throw new Exceeded(what, limit);
            }
            throw new IllegalStateException(e.getMessage(), e);
        } finally {
            synchronized (guard) {
                state[0] = true;
            }
            timer.cancel(false);
            if (state[1]) {
                Thread.interrupted(); // nur das eigene Flag zurücknehmen
            }
        }
    }

    private static boolean timedOut(Object guard, boolean[] state) {
        synchronized (guard) {
            return state[1];
        }
    }
}
