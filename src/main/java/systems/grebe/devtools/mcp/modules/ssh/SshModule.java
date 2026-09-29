package systems.grebe.devtools.mcp.modules.ssh;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import jakarta.annotation.PreDestroy;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.config.SettingsStore;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.ConnectionTestResult;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolModule;

/**
 * SSH-Zugriff auf hinterlegte Server (JSch): Befehle ausführen, Verzeichnisse und Dateien per SFTP lesen und schreiben.
 * Die Verbindungen (Name, Host, Port, Benutzer, Passwort/Schlüssel) werden in der App gepflegt und verschlüsselt
 * gespeichert; das LLM sieht nur die Namen.
 */
@Component
public class SshModule implements ToolModule {

    public static final String ID = "ssh";

    static final String CONNECTIONS = "connections";
    static final String HOST_KEY_POLICY = "hostKeyPolicy";
    static final String KNOWN_HOSTS = "knownHostsFile";
    static final String CONNECT_TIMEOUT = "connectTimeoutSeconds";
    static final String MAX_EXEC_SECONDS = "maxExecSeconds";
    static final String MAX_LINES = "maxOutputLines";
    static final String MAX_OUTPUT_KB = "maxOutputKb";
    static final String ALLOW_EXEC = "allowExec";
    static final String ALLOW_WRITE = "allowWrite";

    /** Über alle Konfigurationsänderungen hinweg dieselbe Instanz, damit alte Sitzungen geschlossen werden können. */
    private final SshSessions sessions = new SshSessions();
    private final Path defaultKnownHosts;

    @Autowired
    public SshModule(SettingsStore store) {
        this(store.file().toAbsolutePath().getParent().resolve("ssh_known_hosts"));
    }

    /** Für Tests: eigene known_hosts-Datei. */
    public SshModule(Path defaultKnownHosts) {
        this.defaultKnownHosts = defaultKnownHosts;
    }

    SshEnvironment environment(ModuleConfig config) {
        return new SshEnvironment(config, sessions, defaultKnownHosts);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "SSH";
    }

    @Override
    public String description() {
        return "Hinterlegte SSH-Server (Name, Host, Port, Benutzer, Passwort oder Schlüssel): Befehle ausführen, "
                + "Verzeichnisse und Dateien per SFTP lesen, optional schreiben. Zugangsdaten bleiben in der App.";
    }

    @Override
    public String instructions() {
        return """
                Für Server, die in der DevTools-App als SSH-Verbindung hinterlegt sind, diese Tools statt `ssh`, `scp` oder \
                `sftp` in der Shell verwenden – die Zugangsdaten kennt nur die App:
                - `ssh_connections`: welche Verbindungen es gibt (Name, Benutzer@Host, Beschreibung).
                - `ssh_exec`: Befehl ausführen (Exit-Code, stdout, stderr); kein Terminal, keine interaktiven Programme.
                - `ssh_list_dir`, `ssh_read_file`: Verzeichnisse und Textdateien per SFTP lesen.
                - `ssh_write_file` (nur wenn angeboten): Datei schreiben – nur auf ausdrückliche Anweisung des Nutzers.
                Verändernde Befehle (Neustarts, Löschen, Paketinstallation, Konfigurationsänderungen) nur auf ausdrückliche \
                Anweisung des Nutzers ausführen. Ist eine gewünschte Verbindung nicht konfiguriert, den Nutzer bitten, sie \
                in der App anzulegen – nicht nach Passwörtern fragen.""";
    }

    @Override
    public int order() {
        return 170;
    }

    @Override
    public List<ConfigField> configSchema() {
        return List.of(
                ConfigField.records(CONNECTIONS, "Verbindungen",
                                ConfigField.of(SshConnection.NAME, "Name", FieldType.STRING).asRequired()
                                        .withHelp("Eindeutiger Name, über den das LLM die Verbindung anspricht, z.B. prod-web."),
                                ConfigField.of(SshConnection.HOST, "Host", FieldType.STRING).asRequired()
                                        .withHelp("Hostname oder IP-Adresse"),
                                ConfigField.of(SshConnection.PORT, "Port", FieldType.INT).withDefault("22"),
                                ConfigField.of(SshConnection.USERNAME, "Benutzer", FieldType.STRING).asRequired(),
                                ConfigField.of(SshConnection.PASSWORD, "Passwort", FieldType.SECRET)
                                        .withHelp("Wird verschlüsselt gespeichert. Leer lassen bei Anmeldung per Schlüssel."),
                                ConfigField.of(SshConnection.PRIVATE_KEY, "Schlüsseldatei", FieldType.STRING)
                                        .withHelp("Optional: Pfad zu einem privaten Schlüssel (OpenSSH/PEM/PuTTY), z.B. ~/.ssh/id_ed25519"),
                                ConfigField.of(SshConnection.PASSPHRASE, "Passphrase", FieldType.SECRET)
                                        .withHelp("Nur für verschlüsselte Schlüsseldateien."),
                                ConfigField.of(SshConnection.DESCRIPTION, "Beschreibung", FieldType.STRING)
                                        .withHelp("Hinweis für das LLM, z.B. „Produktiv-Webserver, nginx + Spring Boot“."))
                        .withHelp("Name, Host, Port, Benutzer und Passwort bzw. Schlüssel je Server. Das LLM sieht nur Name, "
                                + "Benutzer@Host und Beschreibung, nie Passwörter."),
                ConfigField.of(HOST_KEY_POLICY, "Host-Key-Prüfung", FieldType.ENUM).withDefault("accept-new")
                        .withOptions("accept-new", "strict")
                        .withHelp("accept-new: Schlüssel unbekannter Hosts beim ersten Verbinden merken, geänderte ablehnen. "
                                + "strict: nur Hosts, die schon in der known_hosts-Datei stehen."),
                ConfigField.of(KNOWN_HOSTS, "known_hosts-Datei", FieldType.STRING)
                        .withHelp("Leer = " + defaultKnownHosts + ". Auch ~/.ssh/known_hosts ist möglich."),
                ConfigField.of(CONNECT_TIMEOUT, "Verbindungs-Timeout (Sekunden)", FieldType.INT).withDefault("15"),
                ConfigField.of(MAX_EXEC_SECONDS, "Max. Befehlsdauer (Sekunden)", FieldType.INT).withDefault("300")
                        .withHelp("Obergrenze für ssh_exec; danach wird der Befehl abgebrochen."),
                ConfigField.of(MAX_LINES, "Max. Ausgabezeilen", FieldType.INT).withDefault("400"),
                ConfigField.of(MAX_OUTPUT_KB, "Max. Ausgabe (KB)", FieldType.INT).withDefault("1024")
                        .withHelp("Je Befehl bzw. gelesener Datei; der Rest wird verworfen."),
                ConfigField.of(ALLOW_EXEC, "Befehle ausführen erlauben", FieldType.BOOLEAN).withDefault("true")
                        .withHelp("ssh_exec – beliebige Befehle mit den Rechten des hinterlegten Benutzers."),
                ConfigField.of(ALLOW_WRITE, "Dateien schreiben erlauben", FieldType.BOOLEAN).withDefault("false")
                        .withHelp("ssh_write_file (SFTP)."));
    }

    @Override
    public List<ToolCallback> createTools(ModuleConfig config) {
        sessions.closeAll(); // Verbindungsdaten können sich geändert haben
        SshEnvironment env = environment(config);
        List<Object> beans = new ArrayList<>(List.of(new SshTools(env)));
        if (config.getBoolean(ALLOW_EXEC)) {
            beans.add(new SshExecTools(env));
        }
        if (config.getBoolean(ALLOW_WRITE)) {
            beans.add(new SshWriteTools(env));
        }
        return List.of(ToolCallbacks.from(beans.toArray()));
    }

    @Override
    public ConnectionTestResult testConnection(ModuleConfig config) {
        List<String> errors = config.validate();
        if (!errors.isEmpty()) {
            return ConnectionTestResult.failed(String.join("\n", errors));
        }
        SshEnvironment env = environment(config);
        if (env.connections().isEmpty()) {
            return ConnectionTestResult.failed("Keine Verbindung angelegt.");
        }
        StringBuilder sb = new StringBuilder();
        boolean allOk = env.duplicates().isEmpty();
        if (!allOk) {
            sb.append("Mehrfach vergebene Namen: ").append(env.duplicates()).append('\n');
        }
        for (SshConnection c : env.connections()) {
            sb.append(c.name()).append(" (").append(c.target()).append("): ");
            Session s = null;
            try {
                s = env.open(c);
                sb.append("verbunden, ").append(s.getServerVersion()).append(", Host-Key ")
                        .append(s.getHostKey().getType()).append(' ').append(s.getHostKey().getFingerPrint(new JSch()));
            } catch (RuntimeException | JSchException e) {
                allOk = false;
                String msg = e instanceof JSchException je ? env.describe(c, je) : e.getMessage();
                sb.append("FEHLER – ").append(msg);
            } finally {
                if (s != null) {
                    s.disconnect();
                }
            }
            sb.append('\n');
        }
        String msg = sb.toString().strip();
        return allOk ? ConnectionTestResult.ok(msg) : ConnectionTestResult.failed(msg);
    }

    @PreDestroy
    void close() {
        sessions.closeAll();
    }
}
