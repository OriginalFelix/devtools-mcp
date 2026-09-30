package systems.grebe.devtools.mcp.core;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Ringpuffer der letzten Tool-Aufrufe; die UI hängt sich als Listener an. */
@Component
public class ToolInvocationLog {

    private static final Logger LOG = LoggerFactory.getLogger(ToolInvocationLog.class);
    private static final int CAPACITY = 500;
    private static final int MAX_TEXT = 20_000;

    private final Deque<ToolInvocation> entries = new ArrayDeque<>();
    private final List<Consumer<ToolInvocation>> listeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> clearListeners = new CopyOnWriteArrayList<>();
    private final AtomicLong ids = new AtomicLong();

    public ToolInvocation record(String moduleId, String tool, String args, String result,
                                 Duration duration, boolean success) {
        ToolScope scope = ToolScope.current();
        ToolInvocation inv = new ToolInvocation(ids.incrementAndGet(), Instant.now(), moduleId, tool,
                truncate(args), truncate(result), duration, success, scope.userId().orElse(null),
                scope.userName().orElse("lokal"));
        synchronized (entries) {
            entries.addFirst(inv);
            while (entries.size() > CAPACITY) {
                entries.removeLast();
            }
        }
        LOG.info("Tool {} {} in {} ms ({})", tool, success ? "OK" : "FEHLER", duration.toMillis(), inv.user());
        for (Consumer<ToolInvocation> l : listeners) {
            try {
                l.accept(inv);
            } catch (RuntimeException e) {
                LOG.warn("Listener-Fehler", e);
            }
        }
        return inv;
    }

    /** Neueste zuerst. */
    public List<ToolInvocation> snapshot() {
        synchronized (entries) {
            return new ArrayList<>(entries);
        }
    }

    public void clear() {
        synchronized (entries) {
            entries.clear();
        }
        clearListeners.forEach(Runnable::run);
    }

    public void addListener(Consumer<ToolInvocation> listener) {
        listeners.add(listener);
    }

    public void addClearListener(Runnable listener) {
        clearListeners.add(listener);
    }

    private static String truncate(String s) {
        if (s == null || s.length() <= MAX_TEXT) {
            return s;
        }
        return s.substring(0, MAX_TEXT) + "\n… (für das Protokoll gekürzt, " + s.length() + " Zeichen)";
    }
}
