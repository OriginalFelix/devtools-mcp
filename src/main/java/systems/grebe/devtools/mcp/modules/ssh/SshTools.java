package systems.grebe.devtools.mcp.modules.ssh;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.SftpATTRS;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.Text;
import systems.grebe.devtools.mcp.core.ToolHints;

/** Lesende SSH-Tools: Verbindungen, Verzeichnisse und Dateien (SFTP). */
@ToolHints(readOnly = true)
public class SshTools {

    static final String CONNECTION = "Name der Verbindung (siehe ssh_connections); leer = die einzige konfigurierte";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final SshEnvironment env;

    SshTools(SshEnvironment env) {
        this.env = env;
    }

    @Tool(name = "connections", description = "Listet die in der DevTools-App hinterlegten SSH-Verbindungen: Name, "
            + "Benutzer@Host:Port, Anmeldeverfahren, Beschreibung und ob gerade eine Sitzung offen ist. Zugangsdaten werden "
            + "nie ausgegeben." + ShellHints.SSH)
    public String connections() {
        List<SshConnection> all = env.connections();
        if (all.isEmpty()) {
            return "Keine SSH-Verbindung konfiguriert – der Nutzer legt sie in der DevTools-App unter Module → SSH an.";
        }
        StringBuilder sb = new StringBuilder();
        for (SshConnection c : all) {
            sb.append(c.name()).append("  ").append(c.target()).append("  [").append(c.auth())
                    .append(env.isOpen(c) ? ", verbunden" : "").append(']');
            if (!c.description().isEmpty()) {
                sb.append("  – ").append(c.description());
            }
            sb.append('\n');
        }
        for (SshShells.Shell s : env.shells().list()) {
            sb.append("Offene Shell ").append(s.id).append(" auf ").append(s.connection.name())
                    .append(s.pty ? " (PTY)" : "").append(s.running != null ? ", Befehl läuft" : "").append('\n');
        }
        if (!env.duplicates().isEmpty()) {
            sb.append("Achtung: mehrfach vergebene Namen ").append(env.duplicates()).append(" – nur der erste Eintrag gilt.\n");
        }
        return sb.toString().strip();
    }

    @Tool(name = "disconnect", description = "Trennt die offene SSH-Sitzung einer Verbindung, die ssh_exec, SFTP und "
            + "die übrigen Tools zwischen Aufrufen wiederverwenden (sonst erst nach 10 Minuten ohne Nutzung). Der nächste "
            + "Aufruf verbindet neu. Interaktive Shells haben eigene Sitzungen und werden mit ssh_shell_close geschlossen."
            + ShellHints.SSH)
    @ToolHints(destructive = false, idempotent = true)
    public String disconnect(@ToolParam(required = false, description = CONNECTION) String connection) {
        SshConnection c = env.resolve(connection);
        StringBuilder sb = new StringBuilder(env.disconnect(c)
                ? "Verbindung " + c.name() + " (" + c.target() + ") getrennt."
                : "Verbindung " + c.name() + " war nicht offen.");
        List<String> shells = env.shells().list().stream()
                .filter(s -> s.connection.name().equals(c.name())).map(s -> s.id).toList();
        if (!shells.isEmpty()) {
            sb.append(" Noch offene Shells auf ").append(c.name()).append(": ").append(shells)
                    .append(" – mit ssh_shell_close schließen.");
        }
        return sb.toString();
    }

    @Tool(name = "list_dir", description = "Listet ein Verzeichnis auf dem Server per SFTP (Typ, Rechte, Größe, "
            + "Änderungszeit, Name; Verzeichnisse zuerst)." + ShellHints.SSH)
    public String listDir(
            @ToolParam(required = false, description = CONNECTION) String connection,
            @ToolParam(required = false, description = "Pfad auf dem Server; leer = Home-Verzeichnis") String path) {
        SshConnection c = env.resolve(connection);
        return env.sftp(c, sftp -> {
            String dir = path == null || path.isBlank() ? sftp.getHome() : path.trim();
            List<ChannelSftp.LsEntry> entries = new ArrayList<>();
            for (ChannelSftp.LsEntry e : sftp.ls(dir)) {
                if (!e.getFilename().equals(".") && !e.getFilename().equals("..")) {
                    entries.add(e);
                }
            }
            entries.sort(Comparator.comparing((ChannelSftp.LsEntry e) -> !e.getAttrs().isDir())
                    .thenComparing(ChannelSftp.LsEntry::getFilename));
            StringBuilder sb = new StringBuilder(sftp.realpath(dir)).append(" (").append(entries.size())
                    .append(" Einträge)\n");
            for (ChannelSftp.LsEntry e : entries) {
                SftpATTRS a = e.getAttrs();
                sb.append(a.getPermissionsString()).append(' ')
                        .append(String.format("%10d", a.getSize())).append(' ')
                        .append(TIME.format(Instant.ofEpochSecond(a.getMTime()))).append(' ')
                        .append(e.getFilename()).append(a.isDir() ? "/" : "").append('\n');
            }
            return Text.limitLines(sb.toString().strip(), env.maxLines());
        });
    }

    @Tool(name = "read_file", description = "Liest eine Textdatei vom Server per SFTP, mit Zeilennummern; optional nur "
            + "einen Zeilenbereich. Binärdateien werden abgelehnt." + ShellHints.SSH)
    public String readFile(
            @ToolParam(required = false, description = CONNECTION) String connection,
            @ToolParam(description = "Pfad der Datei auf dem Server (absolut oder relativ zum Home-Verzeichnis)") String path,
            @ToolParam(required = false, description = "Erste Zeile (Standard 1)") Integer fromLine,
            @ToolParam(required = false, description = "Letzte Zeile (Standard: bis zur Grenze der Ausgabezeilen)") Integer toLine) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("'path' fehlt.");
        }
        SshConnection c = env.resolve(connection);
        return env.sftp(c, sftp -> {
            SftpATTRS attrs = sftp.stat(path.trim());
            if (attrs.isDir()) {
                return path + " ist ein Verzeichnis – ssh_list_dir verwenden.";
            }
            byte[] data;
            boolean truncated;
            try (InputStream in = sftp.get(path.trim())) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[16 * 1024];
                int n;
                while (buf.size() < env.maxBytes() && (n = in.read(chunk)) > 0) {
                    buf.write(chunk, 0, n);
                }
                truncated = attrs.getSize() > buf.size();
                data = buf.toByteArray();
            }
            for (int i = 0; i < Math.min(data.length, 8192); i++) {
                if (data[i] == 0) {
                    return path + " ist eine Binärdatei (" + attrs.getSize() + " Bytes) – nicht als Text lesbar.";
                }
            }
            String[] lines = new String(data, StandardCharsets.UTF_8).split("\\R", -1);
            int start = fromLine == null ? 1 : Math.max(1, fromLine);
            int end = Math.min(lines.length, toLine == null ? start + env.maxLines() - 1 : Math.max(start, toLine));
            StringBuilder sb = new StringBuilder(path).append(" (").append(attrs.getSize()).append(" Bytes, ")
                    .append(lines.length).append(truncated ? "+" : "").append(" Zeilen)\n");
            for (int i = start; i <= end; i++) {
                sb.append(String.format("%5d | %s%n", i, lines[i - 1]));
            }
            if (end < lines.length || truncated) {
                sb.append("… [weitere Zeilen – mit fromLine/toLine nachladen");
                sb.append(truncated ? "; Datei größer als " + env.maxBytes() / 1024 + " KB, Rest nicht gelesen" : "");
                sb.append(']');
            }
            return Text.limitLines(sb.toString().strip(), env.maxLines() + 2);
        });
    }
}
