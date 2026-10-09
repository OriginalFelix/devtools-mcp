package systems.grebe.devtools.mcp.modules.window;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
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
 * (siehe {@link AiColors}), eigener Bindung, eigenem Rahmen und Zeiger. Ein Prozess gehört immer nur einer KI – hat
 * sie aber {@link #TAKEOVER} lang kein Fenster-Tool aufgerufen (z.B. weil der Client beendet oder neu gestartet wurde,
 * ohne die Session zu schließen), darf eine andere KI ihn übernehmen. Nach {@link #IDLE} ohne Fenster-Tool-Aufruf wird
 * die Session aufgeräumt und ihre Farbe frei.
 */
final class WindowSessions implements AutoCloseable {

    static final Duration IDLE = Duration.ofMinutes(30);
    /** So lange ohne Fenster-Tool-Aufruf, bis eine andere KI die Bindung übernehmen darf. */
    static final Duration TAKEOVER = Duration.ofMinutes(2);

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

    /**
     * Der Client hat die MCP-Session beendet: Bindung, Rahmen und Zeiger sofort freigeben und die Farbe zurückgeben –
     * sonst sperrte die alte Session nach einem Neuverbinden den Prozess noch {@link #TAKEOVER} lang, auch für den
     * Client selbst. Unbekannte IDs (Session ohne Fenster-Tool) ändern nichts.
     */
    void end(String sessionId) {
        Entry e = sessions.remove(sessionId);
        if (e != null) {
            e.session.close();
            colors.release(sessionId);
        }
    }

    int size() {
        return sessions.size();
    }

    /** Die anderen KIs aus Sicht der Session {@code self}. */
    private WindowSession.Peers peers(String self) {
        return new WindowSession.Peers() {
            @Override
            public Optional<String> bind(WindowSession.Binding wanted, boolean siblings, Runnable commit) {
                return WindowSessions.this.bind(self, wanted, siblings, commit);
            }

            @Override
            public Set<Long> claimed() {
                Set<Long> out = new HashSet<>();
                others(self).forEach(o -> out.addAll(o.binding().pids(false)));
                return out;
            }
        };
    }

    /** Bindung einer anderen KI und wann sie zuletzt ein Fenster-Tool aufgerufen hat. */
    private record Other(WindowSession session, WindowSession.Binding binding, long lastUsed) {
    }

    /**
     * Bindet, wenn sich die gewünschte Bindung mit keiner aktiven einer anderen KI überschneidet. Geschwister zählen auf
     * beiden Seiten mit: wer über Geschwister an einen fremden Prozess käme, darf nicht binden – und auch nicht den
     * Geschwisterprozess einer fremden Bindung. Überschneidungen mit KIs, die seit {@link #TAKEOVER} nichts getan haben,
     * werden übernommen: deren Bindung fällt weg. Prüfen und Binden sind atomar, damit zwei KIs, die gleichzeitig
     * binden, nicht beide denselben Prozess bekommen.
     */
    private Optional<String> bind(String self, WindowSession.Binding wanted, boolean siblings, Runnable commit) {
        List<WindowSession> takenOver = new ArrayList<>();
        synchronized (this) {
            long now = clock.getAsLong();
            Set<Long> mine = wanted.pids(false);
            Set<Long> mineReach = wanted.pids(siblings);
            for (Other o : others(self)) {
                Set<Long> theirs = o.binding().pids(false);
                if (Collections.disjoint(mineReach, theirs) && Collections.disjoint(mine, o.binding().pids(siblings))) {
                    continue;
                }
                if (now - o.lastUsed() >= TAKEOVER.toMillis()) {
                    takenOver.add(o.session());
                    continue;
                }
                return Optional.of(wanted.name() + " wird gerade von " + o.session().client() + " gesteuert"
                        + (Collections.disjoint(mine, theirs) ? " (Geschwisterprozess)" : "") + ". Ruft sie "
                        + TAKEOVER.toMinutes() + " Minuten lang kein Fenster-Tool auf, wird der Prozess frei.");
            }
            commit.run();
            Entry me = sessions.get(self);
            String by = me == null ? "eine andere KI" : me.session().client();
            takenOver.forEach(s -> s.takenOver("Die Bindung an " + wanted.name() + " hat " + by + " übernommen, weil "
                    + "diese KI " + TAKEOVER.toMinutes() + " Minuten lang kein Fenster-Tool aufgerufen hat. Mit "
                    + "window_list und window_bind neu binden."));
        }
        takenOver.forEach(WindowSession::releaseDevices); // außerhalb der Sperre: blendet Rahmen und Zeiger aus
        return Optional.empty();
    }

    /** Lebende Bindungen der anderen KIs. */
    private List<Other> others(String self) {
        List<Other> out = new ArrayList<>();
        for (Map.Entry<String, Entry> e : sessions.entrySet()) {
            WindowSession.Binding theirs = e.getValue().session.current();
            if (!e.getKey().equals(self) && theirs != null && theirs.process().isAlive()) {
                long last;
                synchronized (e.getValue().lastUsed) {
                    last = e.getValue().lastUsed[0];
                }
                out.add(new Other(e.getValue().session, theirs, last));
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
