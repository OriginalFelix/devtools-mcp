package systems.grebe.devtools.mcp.modules.ssh;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

/**
 * Offene SSH-Sitzungen je Verbindungsname, damit aufeinanderfolgende Tool-Aufrufe nicht jedes Mal neu verbinden und
 * sich anmelden. Eine Sitzung wird verworfen, wenn sie abgebrochen ist, sich die Verbindungsdaten geändert haben oder sie
 * länger als {@link #IDLE_MILLIS} unbenutzt war. Über alle Konfigurationsänderungen hinweg dieselbe Instanz; das Modul
 * schließt beim Neuaufbau der Tools alle Sitzungen.
 *
 * <p>Aufrufe leihen die Sitzung aus ({@link #lease}): Solange sie läuft, trennt weder die Leerlauf-Prüfung noch ein
 * Fehler eines anderen Aufrufs die Verbindung unter ihr - sie wird erst beim Zurückgeben getrennt. Der Verbindungsaufbau
 * läuft ohne die Pool-Sperre (nur je Name serialisiert), damit ein langsamer Server die übrigen nicht aufhält.
 */
final class SshSessions {

    static final long IDLE_MILLIS = 10 * 60_000L;

    /** Eine gepoolte Sitzung samt Zustand; Felder nur unter der Sperre des Pools ändern. */
    private static final class Pooled {
        final String name;
        final SshConnection connection;
        final Session session;
        long lastUsed;
        int leases;
        /** Aus dem Pool genommen: trennen, sobald die letzte Ausleihe zurückgegeben ist. */
        boolean doomed;

        Pooled(SshConnection connection, Session session, long lastUsed) {
            this.name = connection.name();
            this.connection = connection;
            this.session = session;
            this.lastUsed = lastUsed;
        }
    }

    /** Baut eine neue, verbundene Sitzung auf. */
    @FunctionalInterface
    interface Opener {
        Session open(SshConnection connection) throws JSchException;
    }

    /** Eine ausgeliehene Sitzung; {@link #close()} gibt sie zurück. */
    final class Lease implements AutoCloseable {
        private final Pooled pooled;
        private boolean returned;

        private Lease(Pooled pooled) {
            this.pooled = pooled;
        }

        Session session() {
            return pooled.session;
        }

        /** Die Sitzung hat einen Fehler gemeldet: aus dem Pool nehmen (nur wenn es noch dieselbe ist) und danach trennen. */
        void fail() {
            synchronized (SshSessions.this) {
                if (pool.get(pooled.name) == pooled) {
                    pool.remove(pooled.name);
                }
                pooled.doomed = true;
            }
        }

        @Override
        public void close() {
            synchronized (SshSessions.this) {
                if (returned) {
                    return;
                }
                returned = true;
                pooled.leases--;
                pooled.lastUsed = System.currentTimeMillis();
                if (pooled.doomed && pooled.leases == 0) {
                    pooled.session.disconnect();
                }
            }
        }
    }

    private final long idleMillis;
    private final Map<String, Pooled> pool = new HashMap<>();
    private final Map<String, Object> connectLocks = new ConcurrentHashMap<>();

    SshSessions() {
        this(IDLE_MILLIS);
    }

    /** Für Tests: kürzere Leerlaufzeit. */
    SshSessions(long idleMillis) {
        this.idleMillis = idleMillis;
    }

    /** Leiht die offene Sitzung der Verbindung aus - vorhandene wiederverwenden oder neu aufbauen. */
    Lease lease(SshConnection c, Opener opener) throws JSchException {
        synchronized (connectLocks.computeIfAbsent(c.name(), k -> new Object())) {
            synchronized (this) {
                long now = System.currentTimeMillis();
                closeIdle(now);
                Pooled p = pool.get(c.name());
                if (p != null && p.connection.equals(c) && p.session.isConnected()) {
                    p.lastUsed = now;
                    p.leases++;
                    return new Lease(p);
                }
                if (p != null) {
                    pool.remove(c.name());
                    retire(p);
                }
            }
            Session s = opener.open(c); // ohne die Pool-Sperre: andere Verbindungen laufen währenddessen weiter
            synchronized (this) {
                Pooled fresh = new Pooled(c, s, System.currentTimeMillis());
                fresh.leases = 1;
                pool.put(c.name(), fresh);
                return new Lease(fresh);
            }
        }
    }

    /**
     * Verwirft die Sitzung einer Verbindung (auf Wunsch). Läuft gerade ein Aufruf darauf, wird sie erst danach getrennt.
     *
     * @return {@code true}, wenn dabei eine verbundene Sitzung betroffen war
     */
    synchronized boolean evict(String name) {
        Pooled p = pool.remove(name);
        if (p == null) {
            return false;
        }
        boolean connected = p.session.isConnected();
        retire(p);
        return connected;
    }

    synchronized boolean isOpen(String name) {
        Pooled p = pool.get(name);
        return p != null && p.session.isConnected();
    }

    synchronized void closeAll() {
        pool.values().forEach(p -> p.session.disconnect());
        pool.clear();
    }

    /** Trennt sofort, wenn niemand sie benutzt; sonst nach der letzten Rückgabe. */
    private static void retire(Pooled p) {
        if (p.leases > 0) {
            p.doomed = true;
        } else {
            p.session.disconnect();
        }
    }

    private void closeIdle(long now) {
        pool.values().removeIf(p -> {
            boolean dead = !p.session.isConnected();
            boolean idle = p.leases == 0 && now - p.lastUsed > idleMillis;
            if (dead || idle) {
                retire(p);
            }
            return dead || idle;
        });
    }
}
