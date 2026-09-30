package systems.grebe.devtools.mcp.modules.ssh;

import java.util.HashMap;
import java.util.Map;

import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

/**
 * Offene SSH-Sitzungen je Verbindungsname, damit aufeinanderfolgende Tool-Aufrufe nicht jedes Mal neu verbinden und
 * sich anmelden. Eine Sitzung wird verworfen, wenn sie abgebrochen ist, sich die Verbindungsdaten geändert haben oder sie
 * länger als {@link #IDLE_MILLIS} unbenutzt war. Über alle Konfigurationsänderungen hinweg dieselbe Instanz; das Modul
 * schließt beim Neuaufbau der Tools alle Sitzungen.
 */
final class SshSessions {

    static final long IDLE_MILLIS = 10 * 60_000L;

    private record Pooled(SshConnection connection, Session session, long lastUsed) {
    }

    /** Baut eine neue, verbundene Sitzung auf. */
    @FunctionalInterface
    interface Opener {
        Session open(SshConnection connection) throws JSchException;
    }

    private final Map<String, Pooled> pool = new HashMap<>();

    /** Offene Sitzung für die Verbindung – vorhandene wiederverwenden oder neu aufbauen. */
    synchronized Session get(SshConnection c, Opener opener) throws JSchException {
        long now = System.currentTimeMillis();
        closeIdle(now);
        Pooled p = pool.get(c.name());
        if (p != null && p.connection().equals(c) && p.session().isConnected()) {
            pool.put(c.name(), new Pooled(c, p.session(), now));
            return p.session();
        }
        if (p != null) {
            pool.remove(c.name()).session().disconnect();
        }
        Session s = opener.open(c);
        pool.put(c.name(), new Pooled(c, s, now));
        return s;
    }

    /**
     * Verwirft die Sitzung einer Verbindung (nach einem Verbindungsabbruch oder auf Wunsch).
     *
     * @return {@code true}, wenn dabei eine verbundene Sitzung getrennt wurde
     */
    synchronized boolean evict(String name) {
        Pooled p = pool.remove(name);
        if (p == null) {
            return false;
        }
        boolean connected = p.session().isConnected();
        p.session().disconnect();
        return connected;
    }

    synchronized boolean isOpen(String name) {
        Pooled p = pool.get(name);
        return p != null && p.session().isConnected();
    }

    synchronized void closeAll() {
        pool.values().forEach(p -> p.session().disconnect());
        pool.clear();
    }

    private void closeIdle(long now) {
        pool.values().removeIf(p -> {
            boolean stale = now - p.lastUsed() > IDLE_MILLIS || !p.session().isConnected();
            if (stale) {
                p.session().disconnect();
            }
            return stale;
        });
    }
}
