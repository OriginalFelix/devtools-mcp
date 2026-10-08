package systems.grebe.devtools.mcp.modules.ssh;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.config.SettingsStore;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.ConnectionTestResult;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolBeans;
import systems.grebe.devtools.mcp.core.ToolModule;
import systems.grebe.devtools.mcp.core.ToolScope;
import systems.grebe.devtools.mcp.core.ConfigChange;

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
    static final String MAX_SHELLS = "maxShells";
    static final String SHELL_IDLE_MINUTES = "shellIdleMinutes";
    static final String ALLOW_EXEC = "allowExec";
    static final String ALLOW_WRITE = "allowWrite";
    static final String ALLOW_TRANSFER = "allowTransfer";
    static final String LOCAL_DIRS = "localDirectories";
    static final String ALLOW_SUDO = "allowSudo";

    private static final String STATE = ID + ".state";

    private final Path defaultKnownHosts;
    /** Ein Store je known_hosts-Datei für alle Umgebungen (Tools, Scopes, Verbindungstest). */
    private final Map<Path, KnownHostsStore> knownHosts = new HashMap<>();

    @Autowired
    public SshModule(SettingsStore store) {
        this(store.file().toAbsolutePath().getParent().resolve("ssh_known_hosts"));
    }

    /** Für Tests: eigene known_hosts-Datei. */
    public SshModule(Path defaultKnownHosts) {
        this.defaultKnownHosts = defaultKnownHosts;
    }

    /** Umgebung mit den Sitzungen des laufenden Scopes (außerhalb eines Tool-Aufrufs: lokal). */
    SshEnvironment environment(ModuleConfig config) {
        return environment(config, state(ToolScope.current()));
    }

    private SshEnvironment environment(ModuleConfig config, ScopeState state) {
        return new SshEnvironment(config, state.sessions, state.shells, defaultKnownHosts, this::knownHosts);
    }

    /** Der gemeinsame Store zur Datei; wird beim ersten Zugriff angelegt. */
    KnownHostsStore knownHosts(Path file) throws JSchException {
        Path key = file.toAbsolutePath().normalize();
        synchronized (knownHosts) {
            KnownHostsStore store = knownHosts.get(key);
            if (store == null) {
                store = new KnownHostsStore(key);
                knownHosts.put(key, store);
            }
            return store;
        }
    }

    /**
     * Sitzungen und Shells je Benutzer/Profil, über Konfigurationsänderungen hinweg dieselbe Instanz, damit alte
     * Sitzungen geschlossen werden können.
     */
    private static ScopeState state(ToolScope scope) {
        return scope.state(STATE, ScopeState::new);
    }

    private static final class ScopeState implements AutoCloseable {
        final SshSessions sessions = new SshSessions();
        final SshShells shells = new SshShells();
        final ConfigChange config = new ConfigChange();

        @Override
        public void close() {
            sessions.closeAll();
            shells.closeAll();
        }
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
                - `ssh_exec`: einzelner Befehl (Exit-Code, stdout, stderr); kein Terminal, kein Zustand zwischen Aufrufen.
                - Mehrere Befehle nacheinander, lang laufende Befehle mitlesen, Rückfragen beantworten: `ssh_shell_open` → \
                `ssh_shell_exec` (Befehl, wartet auf Exit-Code oder liefert Teilausgabe) → `ssh_shell_read` (neue Ausgabe \
                seit dem letzten Lesen) / `ssh_shell_send` (Eingabe, ctrl=c) → nächster `ssh_shell_exec` → `ssh_shell_close`.
                - `ssh_disconnect`: offene Sitzung einer Verbindung trennen, wenn die Arbeit dort erledigt ist.
                - `ssh_list_dir`, `ssh_read_file`: Verzeichnisse und Textdateien per SFTP lesen.
                - `ssh_write_file` (nur wenn angeboten): Textdatei schreiben – nur auf ausdrückliche Anweisung des Nutzers.
                - `ssh_upload`, `ssh_download` (nur wenn angeboten): Dateien zwischen diesem Rechner und dem Server, auch \
                binär und groß – statt `scp` und statt Inhalte über read_file/write_file zu kopieren.
                - `ssh_sudo` (nur wenn angeboten): Befehl mit Root-Rechten; das Passwort hat die App – nie danach fragen, \
                kein `sudo` in `ssh_exec`.
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
                                ConfigField.of(SshConnection.SUDO_PASSWORD, "sudo-Passwort", FieldType.SECRET)
                                        .withHelp("Für ssh_sudo. Leer = Login-Passwort."),
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
                ConfigField.of(MAX_SHELLS, "Max. offene Shells", FieldType.INT).withDefault("5")
                        .withHelp("Interaktive Shells (ssh_shell_open) über alle Verbindungen, je eine eigene SSH-Sitzung."),
                ConfigField.of(SHELL_IDLE_MINUTES, "Shells schließen nach (Minuten ohne Nutzung)", FieldType.INT)
                        .withDefault("30"),
                ConfigField.of(ALLOW_EXEC, "Befehle ausführen erlauben", FieldType.BOOLEAN).withDefault("true")
                        .withHelp("ssh_exec und die interaktiven ssh_shell_*-Tools – beliebige Befehle mit den Rechten "
                                + "des hinterlegten Benutzers."),
                ConfigField.of(ALLOW_WRITE, "Dateien schreiben erlauben", FieldType.BOOLEAN).withDefault("false")
                        .withHelp("ssh_write_file (SFTP)."),
                ConfigField.of(ALLOW_TRANSFER, "Dateien übertragen erlauben", FieldType.BOOLEAN).withDefault("false")
                        .withHelp("ssh_upload, ssh_download: Dateien zwischen diesem Rechner und dem Server (SFTP), "
                                + "lokal nur in den folgenden Verzeichnissen."),
                ConfigField.of(LOCAL_DIRS, "Lokale Verzeichnisse für Übertragungen", FieldType.DIRECTORY_LIST)
                        .withHelp("Nur hieraus wird hochgeladen und nur hierhin heruntergeladen (inkl. Unterverzeichnissen)."),
                ConfigField.of(ALLOW_SUDO, "sudo erlauben", FieldType.BOOLEAN).withDefault("false")
                        .withHelp("ssh_sudo: Befehle mit Root-Rechten; das Passwort geht per stdin an sudo, nie zum LLM."));
    }

    @Override
    public List<ToolCallback> createTools(ModuleConfig config) {
        return createTools(config, ToolScope.LOCAL);
    }

    @Override
    public List<ToolCallback> createTools(ModuleConfig config, ToolScope scope) {
        ScopeState state = state(scope);
        // Neu aufgebaut wird auch beim An-/Abschalten einzelner Tools – offene Shells nur bei geänderten Werten schließen
        if (state.config.changed(config)) {
            state.close();
        }
        SshEnvironment env = environment(config, state);
        List<Object> beans = new ArrayList<>(List.of(new SshTools(env)));
        if (config.getBoolean(ALLOW_EXEC)) {
            beans.add(new SshExecTools(env));
            beans.add(new SshShellTools(env));
        }
        if (config.getBoolean(ALLOW_WRITE)) {
            beans.add(new SshWriteTools(env));
        }
        if (config.getBoolean(ALLOW_TRANSFER)) {
            beans.add(new SshTransferTools(env));
        }
        if (config.getBoolean(ALLOW_SUDO)) {
            beans.add(new SshSudoTools(env));
        }
        return ToolBeans.callbacks(beans.toArray());
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
}
