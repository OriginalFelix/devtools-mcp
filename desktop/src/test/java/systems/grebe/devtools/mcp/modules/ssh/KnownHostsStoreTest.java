package systems.grebe.devtools.mcp.modules.ssh;

import java.nio.file.Files;
import java.nio.file.Path;

import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.KeyPair;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/** Gemeinsame known_hosts-Datei: keine verlorenen Einträge, manuelle Korrekturen gelten sofort. */
class KnownHostsStoreTest {

    @TempDir
    Path tmp;

    private static byte[] newKey() throws Exception {
        return KeyPair.genKeyPair(new JSch(), KeyPair.ECDSA, 256).getPublicKeyBlob();
    }

    @Test
    void entriesFromOtherInstancesSurviveAnAdd() throws Exception {
        Path file = tmp.resolve("known_hosts");
        KnownHostsStore a = new KnownHostsStore(file);
        KnownHostsStore b = new KnownHostsStore(file); // z.B. ein früher geladener Stand
        byte[] x = newKey();
        byte[] y = newKey();

        assertThat(a.check("host-x", x, true)).isEqualTo(HostKeyRepository.OK);
        assertThat(b.check("host-y", y, true)).isEqualTo(HostKeyRepository.OK);

        // a kennt host-y nicht aus seinem Speicher, schreibt die Datei aber nicht ohne host-y zurück
        assertThat(a.check("host-z", newKey(), true)).isEqualTo(HostKeyRepository.OK);
        assertThat(Files.readString(file)).contains("host-x", "host-y", "host-z");
        assertThat(new KnownHostsStore(file).check("host-y", y, false)).isEqualTo(HostKeyRepository.OK);
    }

    @Test
    void manualFixOfAChangedKeyIsPickedUpWithoutRestart() throws Exception {
        Path file = tmp.resolve("known_hosts");
        KnownHostsStore store = new KnownHostsStore(file);
        byte[] oldKey = newKey();
        byte[] newKey = newKey();
        assertThat(store.check("host", oldKey, true)).isEqualTo(HostKeyRepository.OK);

        assertThat(store.check("host", newKey, true)).isEqualTo(HostKeyRepository.CHANGED); // nie still übernehmen

        // der Nutzer entfernt den alten Eintrag von Hand und trägt den neuen Schlüssel ein
        Files.writeString(file, "");
        KnownHostsStore other = new KnownHostsStore(file);
        assertThat(other.check("host", newKey, true)).isEqualTo(HostKeyRepository.OK);

        assertThat(store.check("host", newKey, false)).isEqualTo(HostKeyRepository.OK);
        assertThat(store.check("host", oldKey, false)).isEqualTo(HostKeyRepository.CHANGED);
    }

    @Test
    void entryRemovedFromTheFileIsNotTrustedAnymore() throws Exception {
        Path file = tmp.resolve("known_hosts");
        KnownHostsStore store = new KnownHostsStore(file);
        byte[] key = newKey();
        assertThat(store.check("host", key, true)).isEqualTo(HostKeyRepository.OK);
        assertThat(store.check("host", key, false)).isEqualTo(HostKeyRepository.OK);

        Files.writeString(file, ""); // der Nutzer widerruft das Vertrauen von Hand
        assertThat(store.check("host", key, false)).isEqualTo(HostKeyRepository.NOT_INCLUDED);
    }

    @Test
    void strictPolicyDoesNotLearnUnknownHosts() throws Exception {
        Path file = tmp.resolve("known_hosts");
        KnownHostsStore store = new KnownHostsStore(file);
        assertThat(store.check("host", newKey(), false)).isEqualTo(HostKeyRepository.NOT_INCLUDED);
        assertThat(Files.readString(file)).isEmpty();
    }

    @Test
    void moduleSharesOneStorePerFile() throws Exception {
        SshModule module = new SshModule(tmp.resolve("known_hosts"));
        KnownHostsStore first = module.knownHosts(tmp.resolve("known_hosts"));
        assertThat(module.knownHosts(tmp.resolve("sub").resolve("..").resolve("known_hosts"))).isSameAs(first);
        assertThat(module.knownHosts(tmp.resolve("other_hosts"))).isNotSameAs(first);
    }
}
