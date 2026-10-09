package systems.grebe.devtools.mcp.core;

import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fortschrittsmeldungen ({@code notifications/progress}) für den laufenden Tool-Aufruf. Der Client zeigt sie während
 * des Wartens an (z.B. die letzte Zeile eines laufenden Befehls); das LLM sieht sie nicht – was es wissen muss, gehört
 * ins Ergebnis des Tools.
 *
 * <pre>{@code
 * for (Path file : files) {
 *     ToolProgress.report("Lese " + file.getFileName());
 *     …
 * }
 * }</pre>
 *
 * <p>Gemeldet wird nur, wenn der Client im Aufruf ein {@code _meta.progressToken} mitschickt. Die App legt dafür für
 * die Dauer des Aufrufs ein Ziel ({@link Sink}) in einen {@link ScopedValue} – das Tool läuft im selben Thread wie der
 * Handler. Außerhalb eines Tool-Aufrufs (oder in einem anderen Thread) sind alle Methoden wirkungslos.
 */
public final class ToolProgress {

    private static final Logger LOG = LoggerFactory.getLogger(ToolProgress.class);
    private static final long MIN_INTERVAL_MILLIS = 500;
    private static final int MAX_MESSAGE = 300;

    /** Wohin Meldungen gehen; die App verbindet es mit dem MCP-Client. */
    @FunctionalInterface
    public interface Sink {
        /**
         * @param message gekürzter Text
         * @param count   laufende Nummer der Meldung in diesem Aufruf (ab 1)
         */
        void send(String message, int count);
    }

    private static final class Target {
        final Sink sink;
        int count;
        long last;

        Target(Sink sink) {
            this.sink = sink;
        }
    }

    private static final ScopedValue<Target> CURRENT = ScopedValue.newInstance();

    private ToolProgress() {
    }

    /** Das Ziel des laufenden Aufrufs oder {@code null}. */
    private static Target current() {
        return CURRENT.isBound() ? CURRENT.get() : null;
    }

    /** Für die App: führt {@code body} so aus, dass {@link #report} an {@code sink} meldet. */
    public static <T> T callWith(Sink sink, Supplier<T> body) {
        return ScopedValue.where(CURRENT, new Target(sink)).call(body::get);
    }

    /** Ob der laufende Aufruf Fortschritt empfangen kann. */
    public static boolean active() {
        return CURRENT.isBound();
    }

    /** Ob jetzt eine Meldung gesendet würde – um teure Meldungstexte nur dann zu bauen. */
    public static boolean due() {
        Target t = current();
        return t != null && System.currentTimeMillis() - t.last >= MIN_INTERVAL_MILLIS;
    }

    /**
     * Meldet einen Zwischenstand. Höchstens alle {@value #MIN_INTERVAL_MILLIS} ms, sonst verworfen; Fehler beim Senden
     * werden ignoriert. Ohne Token oder außerhalb eines Tool-Aufrufs wirkungslos.
     */
    public static void report(String message) {
        Target t = current();
        if (t == null || message == null || message.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - t.last < MIN_INTERVAL_MILLIS) {
            return;
        }
        t.last = now;
        String text = message.strip();
        if (text.length() > MAX_MESSAGE) {
            text = text.substring(0, MAX_MESSAGE) + " …";
        }
        try {
            t.sink.send(text, ++t.count);
        } catch (RuntimeException e) {
            LOG.debug("Fortschritt nicht gesendet", e);
        }
    }
}
