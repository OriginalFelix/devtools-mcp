package systems.grebe.devtools.mcp.modules.ssh;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import com.jcraft.jsch.ChannelSftp;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;

/** Dateien auf dem Server schreiben (nur wenn im Modul erlaubt). */
public class SshWriteTools {

    private final SshEnvironment env;

    SshWriteTools(SshEnvironment env) {
        this.env = env;
    }

    @Tool(name = "write_file", description = "Schreibt eine Textdatei (UTF-8) auf den SSH-Server per SFTP – neu, "
            + "überschreibend oder anhängend. Das Verzeichnis muss existieren. Nur auf ausdrückliche Anweisung des Nutzers."
            + ShellHints.SSH)
    public String writeFile(
            @ToolParam(required = false, description = SshTools.CONNECTION) String connection,
            @ToolParam(description = "Pfad der Datei auf dem Server (absolut oder relativ zum Home-Verzeichnis)") String path,
            @ToolParam(description = "Vollständiger Dateiinhalt") String content,
            @ToolParam(required = false, description = "true = anhängen statt überschreiben (Standard false)") Boolean append) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("'path' fehlt.");
        }
        SshConnection c = env.resolve(connection);
        byte[] data = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
        boolean add = Boolean.TRUE.equals(append);
        return env.sftp(c, sftp -> {
            sftp.put(new ByteArrayInputStream(data), path.trim(), add ? ChannelSftp.APPEND : ChannelSftp.OVERWRITE);
            return data.length + " Bytes " + (add ? "angehängt an " : "geschrieben nach ") + c.name() + ":" + path.trim();
        });
    }
}
