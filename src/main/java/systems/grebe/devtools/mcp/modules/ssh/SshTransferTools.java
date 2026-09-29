package systems.grebe.devtools.mcp.modules.ssh;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.SftpProgressMonitor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.ToolHints;
import systems.grebe.devtools.mcp.core.ToolProgress;

/**
 * Dateien zwischen lokalem Rechner und Server übertragen (SFTP, nur wenn im Modul erlaubt). Der Inhalt geht nie durch
 * den Kontext des LLM – auch Binär- und große Dateien. Lokal nur innerhalb der freigegebenen Verzeichnisse.
 */
@ToolHints(destructive = true)
public class SshTransferTools {

    private static final String OVERWRITE = "Vorhandene Zieldatei überschreiben (Standard false: dann Fehler, wenn es sie gibt)";

    private final SshEnvironment env;

    SshTransferTools(SshEnvironment env) {
        this.env = env;
    }

    @Tool(name = "upload", description = "Überträgt eine lokale Datei per SFTP auf einen SSH-Server (auch Binär- und große "
            + "Dateien, ohne sie in den Kontext zu laden). Lokal nur aus den in der DevTools-App freigegebenen Verzeichnissen."
            + ShellHints.SSH)
    public String upload(
            @ToolParam(required = false, description = SshTools.CONNECTION) String connection,
            @ToolParam(description = "Lokale Datei (absolut, oder relativ zum einzigen freigegebenen Verzeichnis)") String localPath,
            @ToolParam(description = "Ziel auf dem Server: Datei oder vorhandenes Verzeichnis") String remotePath,
            @ToolParam(required = false, description = OVERWRITE) Boolean overwrite) {
        SshConnection c = env.resolve(connection);
        Path local = env.localPath(localPath);
        if (!Files.isRegularFile(local)) {
            throw new IllegalArgumentException("Lokale Datei nicht gefunden: " + local);
        }
        long start = System.currentTimeMillis();
        return env.sftp(c, sftp -> {
            String target = remotePath == null || remotePath.isBlank() ? local.getFileName().toString() : remotePath.trim();
            SftpATTRS existing = stat(sftp, target);
            if (existing != null && existing.isDir()) {
                target = target.replaceAll("/+$", "") + "/" + local.getFileName();
                existing = stat(sftp, target);
            }
            if (existing != null && !Boolean.TRUE.equals(overwrite)) {
                throw new IllegalStateException(c.name() + ":" + target + " existiert bereits – mit overwrite=true ersetzen.");
            }
            sftp.put(local.toString(), target, new Progress("Upload " + local.getFileName()), ChannelSftp.OVERWRITE);
            return size(Files.size(local)) + " hochgeladen: " + local + " → " + c.name() + ":" + sftp.realpath(target)
                    + " (" + (System.currentTimeMillis() - start) + " ms)";
        });
    }

    @Tool(name = "download", description = "Überträgt eine Datei per SFTP von einem SSH-Server auf den lokalen Rechner (auch "
            + "Binär- und große Dateien, ohne sie in den Kontext zu laden). Lokal nur in die in der DevTools-App freigegebenen "
            + "Verzeichnisse; fehlende Unterverzeichnisse werden angelegt." + ShellHints.SSH)
    public String download(
            @ToolParam(required = false, description = SshTools.CONNECTION) String connection,
            @ToolParam(description = "Datei auf dem Server") String remotePath,
            @ToolParam(description = "Lokales Ziel: Datei oder vorhandenes Verzeichnis (absolut, oder relativ zum einzigen "
                    + "freigegebenen Verzeichnis)") String localPath,
            @ToolParam(required = false, description = OVERWRITE) Boolean overwrite) {
        if (remotePath == null || remotePath.isBlank()) {
            throw new IllegalArgumentException("'remotePath' fehlt.");
        }
        SshConnection c = env.resolve(connection);
        Path requested = env.localPath(localPath);
        long start = System.currentTimeMillis();
        return env.sftp(c, sftp -> {
            SftpATTRS attrs = sftp.stat(remotePath.trim());
            if (attrs.isDir()) {
                throw new IllegalArgumentException(remotePath + " ist ein Verzeichnis – nur einzelne Dateien.");
            }
            String name = remotePath.trim().replaceAll(".*/", "");
            Path local = Files.isDirectory(requested) ? env.localPath(requested.resolve(name).toString()) : requested;
            if (Files.exists(local) && !Boolean.TRUE.equals(overwrite)) {
                throw new IllegalStateException(local + " existiert bereits – mit overwrite=true ersetzen.");
            }
            Files.createDirectories(local.getParent());
            sftp.get(remotePath.trim(), local.toString(), new Progress("Download " + name));
            return size(Files.size(local)) + " heruntergeladen: " + c.name() + ":" + remotePath.trim() + " → " + local
                    + " (" + (System.currentTimeMillis() - start) + " ms)";
        });
    }

    private static SftpATTRS stat(ChannelSftp sftp, String path) throws SftpException {
        try {
            return sftp.stat(path);
        } catch (SftpException e) {
            if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                return null;
            }
            throw e;
        }
    }

    static String size(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.GERMAN, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.GERMAN, "%.1f MB", bytes / (1024.0 * 1024));
    }

    /** Meldet den Fortschritt der Übertragung an den Client (läuft im Thread des Tool-Aufrufs). */
    private static final class Progress implements SftpProgressMonitor {
        private final String label;
        private long max;
        private long done;

        Progress(String label) {
            this.label = label;
        }

        @Override
        public void init(int op, String src, String dest, long max) {
            this.max = max;
        }

        @Override
        public boolean count(long count) {
            done += count;
            if (ToolProgress.due()) {
                ToolProgress.report(label + ": " + size(done) + (max > 0 ? " von " + size(max)
                        + " (" + (100 * done / max) + " %)" : ""));
            }
            return !Thread.currentThread().isInterrupted();
        }

        @Override
        public void end() {
        }
    }
}
