package systems.grebe.devtools.mcp.modules.ssh;

import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.UserInfo;

/**
 * Host-Key-Prüfung einer SSH-Umgebung gegen die gemeinsame known_hosts-Datei ({@link KnownHostsStore}). Mit
 * {@code acceptNew} wird der Schlüssel eines noch unbekannten Hosts beim ersten Verbinden gespeichert (wie
 * {@code StrictHostKeyChecking=accept-new} bei OpenSSH); ein <em>geänderter</em> Schlüssel wird immer abgelehnt. Geteilt
 * wird der Store, nicht dieser Adapter: Er trägt nur die Richtlinie der jeweiligen Umgebung.
 */
final class TofuHostKeys implements HostKeyRepository {

    private final KnownHostsStore store;
    private final boolean acceptNew;

    TofuHostKeys(KnownHostsStore store, boolean acceptNew) {
        this.store = store;
        this.acceptNew = acceptNew;
    }

    @Override
    public int check(String host, byte[] key) {
        return store.check(host, key, acceptNew);
    }

    @Override
    public void add(HostKey hostkey, UserInfo ui) {
        store.add(hostkey, ui);
    }

    @Override
    public void remove(String host, String type) {
        store.remove(host, type);
    }

    @Override
    public void remove(String host, String type, byte[] key) {
        store.remove(host, type, key);
    }

    @Override
    public String getKnownHostsRepositoryID() {
        return store.id();
    }

    @Override
    public HostKey[] getHostKey() {
        return store.hostKeys();
    }

    @Override
    public HostKey[] getHostKey(String host, String type) {
        return store.hostKeys(host, type);
    }
}
