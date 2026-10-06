package systems.grebe.devtools.mcp.modules.jdbc;

import java.sql.Connection;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Freie Datenbankverbindungen je Verbindungsname, damit aufeinanderfolgende Tool-Aufrufe nicht jedes Mal neu verbinden
 * und sich anmelden. Ein Aufruf nimmt sich eine Verbindung ({@link #take}) und gibt sie danach zurück ({@link #give}) –
 * gleichzeitige Aufrufe arbeiten so nie auf derselben {@link Connection}. Verworfen wird, was länger als
 * {@link #IDLE_MILLIS} frei war, zu geänderten Verbindungsdaten gehört oder über {@link #MAX_IDLE} hinausgeht.
 *
 * <p>Nach {@link #close()} (geänderte Konfiguration, Profilwechsel) nimmt die Instanz nichts mehr an: Verbindungen, die
 * ein noch laufender Aufruf zurückgibt, werden geschlossen.
 */
final class JdbcSessions implements AutoCloseable {

    static final long IDLE_MILLIS = 10 * 60_000L;
    static final int MAX_IDLE = 2;

    private record Idle(JdbcConnection config, Connection connection, long since) {
    }

    private final Map<String, Deque<Idle>> idle = new HashMap<>();
    private final Map<String, String> products = new HashMap<>();
    private boolean closed;

    /** Eine freie Verbindung mit denselben Verbindungsdaten oder {@code null}. */
    synchronized Connection take(JdbcConnection c) {
        closeIdle(System.currentTimeMillis());
        Deque<Idle> q = idle.get(c.name());
        while (q != null && !q.isEmpty()) {
            Idle i = q.pollFirst();
            if (i.config().equals(c)) {
                return i.connection();
            }
            JdbcEnvironment.closeQuietly(i.connection());
        }
        return null;
    }

    /** Gibt eine Verbindung zurück (im Autocommit-Modus, ohne offene Transaktion). */
    synchronized void give(JdbcConnection c, Connection con) {
        Deque<Idle> q = idle.computeIfAbsent(c.name(), k -> new ArrayDeque<>());
        if (closed || q.size() >= MAX_IDLE) {
            JdbcEnvironment.closeQuietly(con);
            return;
        }
        q.addFirst(new Idle(c, con, System.currentTimeMillis()));
    }

    /**
     * Schließt die freien Verbindungen einer Verbindung (Verbindungen laufender Aufrufe danach).
     *
     * @return {@code true}, wenn eine offen war
     */
    synchronized boolean evict(String name) {
        Deque<Idle> q = idle.remove(name);
        if (q == null || q.isEmpty()) {
            return false;
        }
        q.forEach(i -> JdbcEnvironment.closeQuietly(i.connection()));
        return true;
    }

    /** Schließt die freien Verbindungen aller Verbindungen, auf die {@code filter} passt; Anzahl der geschlossenen. */
    synchronized int evictWhere(java.util.function.Predicate<JdbcConnection> filter) {
        int closed = 0;
        for (Deque<Idle> q : idle.values()) {
            for (java.util.Iterator<Idle> it = q.iterator(); it.hasNext(); ) {
                Idle i = it.next();
                if (filter.test(i.config())) {
                    JdbcEnvironment.closeQuietly(i.connection());
                    it.remove();
                    closed++;
                }
            }
        }
        return closed;
    }

    synchronized boolean isOpen(String name) {
        Deque<Idle> q = idle.get(name);
        return q != null && !q.isEmpty();
    }

    /** Merkt sich Datenbankprodukt und Version (für {@code jdbc_connections}). */
    synchronized void product(String name, String product) {
        products.put(name, product);
    }

    synchronized String product(String name) {
        return products.get(name);
    }

    @Override
    public synchronized void close() {
        closed = true;
        idle.values().forEach(q -> q.forEach(i -> JdbcEnvironment.closeQuietly(i.connection())));
        idle.clear();
    }

    private void closeIdle(long now) {
        for (Deque<Idle> q : idle.values()) {
            q.removeIf(i -> {
                boolean stale = now - i.since() > IDLE_MILLIS;
                if (stale) {
                    JdbcEnvironment.closeQuietly(i.connection());
                }
                return stale;
            });
        }
    }
}
