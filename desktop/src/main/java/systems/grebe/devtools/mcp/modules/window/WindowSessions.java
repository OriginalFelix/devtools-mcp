package systems.grebe.devtools.mcp.modules.window;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import systems.grebe.devtools.mcp.core.ToolSession;

/**
 * Fensterzustand je KI: jede MCP-Session ({@link ToolSession}) hat ihre eigene {@link WindowSession} mit eigener Farbe
 * (siehe {@link AiColors}), eigener Bindung, eigenem Rahmen und Zeiger. Ein Prozess gehört immer nur einer KI. Nach
 * {@link #IDLE} ohne Fenster-Tool-Aufruf wird die Session aufgeräumt und ihre Farbe frei.
 */
final class WindowSessions implements AutoCloseable {

    static final Duration IDLE = Duration.ofMinutes(30);

    private record Entry(WindowSession session, long[] lastUsed) {
    }

    private final AiColors colors;
    private final Duration idle;
    private final LongSupplier clock;
    private final Map<String, Entry> sessions = new ConcurrentHashMap<>();
    private ScheduledExecutorService reaper;

    WindowSessions(AiColors colors, Duration idle, LongSupplier clock) {
        this.colors = colors;
        this.idle = idle;
        this.clock = clock;
    }

    /** Mit Uhr der App und Aufräumen einmal je Minute auf einem Hintergrund-Thread. */
    static WindowSessions start(AiColors colors) {
        WindowSessions s = new WindowSessions(colors, IDLE, System::currentTimeMillis);
        s.reaper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "window-sessions-reaper");
            t.setDaemon(true);
            return t;
        });
        s.reaper.scheduleWithFixedDelay(s::reap, 1, 1, TimeUnit.MINUTES);
        return s;
    }

    /** Die Session der KI, die gerade ein Tool aufruft – beim ersten Mal angelegt; zählt als Nutzung. */
    WindowSession current() {
        ToolSession ts = ToolSession.current();
        Entry e = sessions.computeIfAbsent(ts.id(), id -> new Entry(new WindowSession(id, ts.client(),
                AiColors.color(colors.assign(id)), b -> conflict(id, b)), new long[1]));
        synchronized (e.lastUsed) {
            e.lastUsed[0] = clock.getAsLong();
        }
        return e.session;
    }

    /** Schließt Sessions, die länger als {@link #IDLE} kein Fenster-Tool aufgerufen haben, und gibt ihre Farben frei. */
    void reap() {
        long now = clock.getAsLong();
        for (Map.Entry<String, Entry> e : List.copyOf(sessions.entrySet())) {
            long last;
            synchronized (e.getValue().lastUsed) {
                last = e.getValue().lastUsed[0];
            }
            if (now - last >= idle.toMillis() && sessions.remove(e.getKey(), e.getValue())) {
                e.getValue().session.close();
                colors.release(e.getKey());
            }
        }
    }

    int size() {
        return sessions.size();
    }

    /** Steuert eine andere KI den Prozess – oder einen aus seinem Baum bzw. er einen aus ihrem? */
    private Optional<String> conflict(String self, WindowSession.Binding wanted) {
        for (Map.Entry<String, Entry> e : sessions.entrySet()) {
            WindowSession other = e.getValue().session;
            WindowSession.Binding theirs = other.current();
            if (e.getKey().equals(self) || theirs == null || !theirs.process().isAlive()) {
                continue;
            }
            if (theirs.pids(false).contains(wanted.process().pid()) || wanted.pids(false).contains(theirs.process().pid())) {
                return Optional.of(wanted.name() + " wird gerade von " + other.client() + " gesteuert.");
            }
        }
        return Optional.empty();
    }

    @Override
    public void close() {
        if (reaper != null) {
            reaper.shutdownNow();
        }
        sessions.values().forEach(e -> e.session.close());
        sessions.clear();
    }
}
