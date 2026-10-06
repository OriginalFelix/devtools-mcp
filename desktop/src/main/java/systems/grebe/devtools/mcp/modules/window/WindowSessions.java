package systems.grebe.devtools.mcp.modules.window;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
                AiColors.color(colors.assign(id)), peers(id)), new long[1]));
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

    /** Die anderen KIs aus Sicht der Session {@code self}. */
    private WindowSession.Peers peers(String self) {
        return new WindowSession.Peers() {
            @Override
            public Optional<String> conflict(WindowSession.Binding wanted, boolean siblings) {
                return WindowSessions.this.conflict(self, wanted, siblings);
            }

            @Override
            public Set<Long> claimed() {
                Set<Long> out = new HashSet<>();
                others(self).forEach((other, theirs) -> out.addAll(theirs.pids(false)));
                return out;
            }
        };
    }

    /**
     * Überschneidet sich die gewünschte Bindung mit der einer anderen KI? Geschwister zählen auf beiden Seiten mit: wer
     * über Geschwister an einen fremden Prozess käme, darf nicht binden – und auch nicht den Geschwisterprozess einer
     * fremden Bindung.
     */
    private Optional<String> conflict(String self, WindowSession.Binding wanted, boolean siblings) {
        Set<Long> mine = wanted.pids(false);
        Set<Long> mineReach = wanted.pids(siblings);
        for (Map.Entry<WindowSession, WindowSession.Binding> e : others(self).entrySet()) {
            Set<Long> theirs = e.getValue().pids(false);
            if (!Collections.disjoint(mineReach, theirs) || !Collections.disjoint(mine, e.getValue().pids(siblings))) {
                return Optional.of(wanted.name() + " wird gerade von " + e.getKey().client() + " gesteuert"
                        + (Collections.disjoint(mine, theirs) ? " (Geschwisterprozess)" : "") + ".");
            }
        }
        return Optional.empty();
    }

    /** Lebende Bindungen der anderen KIs. */
    private Map<WindowSession, WindowSession.Binding> others(String self) {
        Map<WindowSession, WindowSession.Binding> out = new HashMap<>();
        for (Map.Entry<String, Entry> e : sessions.entrySet()) {
            WindowSession.Binding theirs = e.getValue().session.current();
            if (!e.getKey().equals(self) && theirs != null && theirs.process().isAlive()) {
                out.put(e.getValue().session, theirs);
            }
        }
        return out;
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
