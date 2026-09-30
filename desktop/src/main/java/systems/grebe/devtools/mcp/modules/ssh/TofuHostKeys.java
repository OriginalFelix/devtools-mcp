package systems.grebe.devtools.mcp.modules.ssh;

import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.UserInfo;

/**
 * Host-Key-Prüfung gegen eine known_hosts-Datei. Mit {@code acceptNew} wird der Schlüssel eines noch unbekannten Hosts
 * beim ersten Verbinden gespeichert (wie {@code StrictHostKeyChecking=accept-new} bei OpenSSH); ein <em>geänderter</em>
 * Schlüssel wird immer abgelehnt. Eine Instanz wird von allen Verbindungen geteilt, damit gleichzeitige Verbindungen die
 * Datei nicht gegenseitig überschreiben.
 */
final class TofuHostKeys implements HostKeyRepository {

    private final HostKeyRepository known;
    private final boolean acceptNew;

    TofuHostKeys(HostKeyRepository known, boolean acceptNew) {
        this.known = known;
        this.acceptNew = acceptNew;
    }

    @Override
    public synchronized int check(String host, byte[] key) {
        int result = known.check(host, key);
        if (result == NOT_INCLUDED && acceptNew) {
            try {
                known.add(new HostKey(host, key), null);
                return OK;
            } catch (JSchException e) {
                return result;
            }
        }
        return result;
    }

    @Override
    public synchronized void add(HostKey hostkey, UserInfo ui) {
        known.add(hostkey, ui);
    }

    @Override
    public synchronized void remove(String host, String type) {
        known.remove(host, type);
    }

    @Override
    public synchronized void remove(String host, String type, byte[] key) {
        known.remove(host, type, key);
    }

    @Override
    public String getKnownHostsRepositoryID() {
        return known.getKnownHostsRepositoryID();
    }

    @Override
    public synchronized HostKey[] getHostKey() {
        return known.getHostKey();
    }

    @Override
    public synchronized HostKey[] getHostKey(String host, String type) {
        return known.getHostKey(host, type);
    }
}
