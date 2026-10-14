package systems.grebe.devtools.mcp.modules.browser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Offene Browser eines Scopes. Browser ohne Nutzung schließt ein Hintergrund-Thread nach der eingestellten Leerlaufzeit;
 * {@link #closeAll()} beendet alle (Konfigurationsänderung, Profilwechsel, App-Ende).
 */
final class BrowserSessions {

    private final Map<String, BrowserSession> sessions = new LinkedHashMap<>();
    private final AtomicInteger counter = new AtomicInteger();
    private ScheduledExecutorService reaper;
    private volatile long idleMillis = TimeUnit.MINUTES.toMillis(30);
    /** Zuletzt verwendete Sitzung – Standard, wenn ein Tool keine angibt. */
    private String current;

    synchronized String nextId() {
        return "b" + counter.incrementAndGet();
    }

    void idleMinutes(int minutes) {
        idleMillis = TimeUnit.MINUTES.toMillis(Math.max(1, minutes));
    }

    synchronized void add(BrowserSession s) {
        sessions.put(s.id, s);
        current = s.id;
        if (reaper == null) {
            reaper = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "browser-idle");
                t.setDaemon(true);
                return t;
            });
            reaper.scheduleWithFixedDelay(this::closeIdle, 1, 1, TimeUnit.MINUTES);
        }
    }

    synchronized BrowserSession get(String id) {
        BrowserSession s = sessions.get(id);
        if (s != null) {
            current = id;
        }
        return s;
    }

    /** Zuletzt verwendete Sitzung oder {@code null}. */
    synchronized BrowserSession current() {
        return current == null ? null : sessions.get(current);
    }

    synchronized List<BrowserSession> list() {
        return new ArrayList<>(sessions.values());
    }

    synchronized int size() {
        return sessions.size();
    }

    BrowserSession remove(String id) {
        BrowserSession s;
        synchronized (this) {
            s = sessions.remove(id);
            if (id.equals(current)) {
                current = sessions.isEmpty() ? null : List.copyOf(sessions.keySet()).getLast();
            }
        }
        if (s != null) {
            synchronized (s) {
                s.close();
            }
        }
        return s;
    }

    private void closeIdle() {
        long limit = System.currentTimeMillis() - idleMillis;
        for (BrowserSession s : list()) {
            if (s.lastUsed < limit) {
                remove(s.id);
            }
        }
    }

    void closeAll() {
        List<BrowserSession> all;
        synchronized (this) {
            all = new ArrayList<>(sessions.values());
            sessions.clear();
            current = null;
            if (reaper != null) {
                reaper.shutdownNow();
                reaper = null;
            }
        }
        all.parallelStream().forEach(s -> {
            synchronized (s) {
                s.close();
            }
        });
    }
}
