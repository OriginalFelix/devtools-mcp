package systems.grebe.devtools.mcp.modules.ssh;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.UserInfo;

/**
 * Die known_hosts-Datei, die alle SSH-Umgebungen eines Moduls gemeinsam nutzen (eine Instanz je Datei, siehe
 * {@link SshModule}). JSch schreibt beim Hinzufügen die ganze Datei aus seinem Speicher neu – mehrere unabhängige
 * Instanzen auf derselben Datei überschrieben sich daher gegenseitig, und ein von Hand korrigierter Eintrag (nach einem
 * geänderten Host-Key) wurde nie bemerkt oder gar zurückgeschrieben.
 *
 * <p>Deshalb wird neu geladen, sobald sich die Datei verändert hat (Zeitstempel oder Größe), vor jedem Hinzufügen und
 * bei jedem Ergebnis außer {@code OK} noch einmal („reload on miss“): Eine Änderung an der Datei von außen – auch das
 * Entfernen eines Eintrags – gilt so ohne Neustart.
 */
final class KnownHostsStore {

    private final Path file;
    private HostKeyRepository known;
    private FileTime loadedModified;
    private long loadedSize = -1;

    KnownHostsStore(Path file) throws JSchException {
        this.file = file;
        reload();
    }

    private void reload() throws JSchException {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            if (!Files.exists(file)) {
                Files.createFile(file);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("known_hosts-Datei nicht anlegbar: " + file, e);
        }
        JSch loader = new JSch();
        loader.setKnownHosts(file.toString());
        known = loader.getHostKeyRepository();
        remember();
    }

    /** Merkt sich den Stand der Datei, wie er geladen oder von uns geschrieben wurde. */
    private void remember() {
        try {
            loadedModified = Files.getLastModifiedTime(file);
            loadedSize = Files.size(file);
        } catch (IOException e) {
            loadedModified = null;
            loadedSize = -1;
        }
    }

    private boolean changedOnDisk() {
        try {
            return !Files.getLastModifiedTime(file).equals(loadedModified) || Files.size(file) != loadedSize;
        } catch (IOException e) {
            return true;
        }
    }

    /**
     * Prüft den Schlüssel von {@code host}. Mit {@code acceptNew} wird der eines noch unbekannten Hosts gespeichert
     * (wie {@code StrictHostKeyChecking=accept-new}); ein geänderter Schlüssel wird nie übernommen.
     */
    synchronized int check(String host, byte[] key, boolean acceptNew) {
        if (changedOnDisk()) {
            try {
                reload();
            } catch (JSchException ignored) {
                // dann mit dem bisherigen Stand
            }
        }
        int result = known.check(host, key);
        if (result != HostKeyRepository.OK) {
            try {
                reload();
            } catch (JSchException e) {
                return result;
            }
            result = known.check(host, key);
        }
        if (result == HostKeyRepository.NOT_INCLUDED && acceptNew) {
            try {
                known.add(new HostKey(host, key), null);
                remember();
                return HostKeyRepository.OK;
            } catch (JSchException e) {
                return result;
            }
        }
        return result;
    }

    synchronized void add(HostKey hostKey, UserInfo ui) {
        try {
            reload(); // nicht den veralteten Stand zurückschreiben
        } catch (JSchException ignored) {
            // dann eben mit dem bisherigen Stand
        }
        known.add(hostKey, ui);
        remember();
    }

    synchronized void remove(String host, String type) {
        known.remove(host, type);
    }

    synchronized void remove(String host, String type, byte[] key) {
        known.remove(host, type, key);
    }

    String id() {
        return file.toString();
    }

    synchronized HostKey[] hostKeys() {
        return known.getHostKey();
    }

    synchronized HostKey[] hostKeys(String host, String type) {
        return known.getHostKey(host, type);
    }
}
