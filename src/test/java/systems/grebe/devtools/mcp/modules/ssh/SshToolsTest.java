package systems.grebe.devtools.mcp.modules.ssh;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.ModuleConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SshToolsTest {

    @TempDir
    Path tmp;

    private SshServer server;
    private SshModule module;

    @BeforeEach
    void start() throws IOException {
        server = server(tmp.resolve("hostkey1.ser"));
        Files.createDirectories(tmp.resolve("root/logs"));
        Files.writeString(tmp.resolve("root/logs/app.log"), "eins\nzwei\ndrei\n");
        module = new SshModule(tmp.resolve("known_hosts"));
    }

    @AfterEach
    void stop() throws IOException {
        module.close();
        server.stop(true);
    }

    private SshServer server(Path hostKey) throws IOException {
        SshServer s = SshServer.setUpDefaultServer();
        s.setHost("127.0.0.1");
        s.setPort(0);
        s.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(hostKey));
        s.setPasswordAuthenticator((user, password, session) -> "alice".equals(user) && "geheim".equals(password));
        s.setCommandFactory((channel, command) -> new EchoCommand(command));
        s.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        s.setFileSystemFactory(new VirtualFileSystemFactory(Files.createDirectories(tmp.resolve("root"))));
        s.start();
        return s;
    }

    private ModuleConfig config(String password, Map<String, String> extra) {
        String connections = ModuleConfig.formatRecords(List.of(Map.of(
                "name", "prod", "host", "127.0.0.1", "port", String.valueOf(server.getPort()),
                "username", "alice", "password", password, "description", "Testserver")));
        Map<String, String> values = new java.util.HashMap<>(extra);
        values.put(SshModule.CONNECTIONS, connections);
        return ModuleConfig.of(module.configSchema(), values);
    }

    @Test
    void connectionsSecretFieldIsEncrypted() {
        ConfigField connections = module.configSchema().getFirst();
        assertThat(connections.secret()).isTrue();
        ModuleConfig cfg = config("geheim", Map.of());
        assertThat(cfg.validate()).isEmpty();
        assertThat(cfg.getRecords(SshModule.CONNECTIONS)).singleElement()
                .satisfies(r -> assertThat(r).containsEntry("name", "prod").containsEntry("password", "geheim"));
    }

    @Test
    void validatesRecords() {
        String broken = ModuleConfig.formatRecords(List.of(Map.of("name", "x", "port", "abc")));
        List<String> errors = ModuleConfig.of(module.configSchema(), Map.of(SshModule.CONNECTIONS, broken)).validate();
        assertThat(errors).anyMatch(e -> e.contains("Eintrag 1") && e.contains("Host"))
                .anyMatch(e -> e.contains("Eintrag 1") && e.contains("ganze Zahl"));
    }

    @Test
    void listsConnectionsWithoutPasswords() {
        SshEnvironment env = module.environment(config("geheim", Map.of()));
        String out = new SshTools(env).connections();
        assertThat(out).contains("prod", "alice@127.0.0.1:" + server.getPort(), "Passwort", "Testserver")
                .doesNotContain("geheim");
    }

    @Test
    void execReturnsExitCodeOutputAndStdin() {
        SshEnvironment env = module.environment(config("geheim", Map.of()));
        String out = new SshExecTools(env).exec(null, "uptime", "/srv/app", "hallo", null);
        assertThat(out).startsWith("Exit-Code 3 (prod,")
                .contains("cmd=cd '/srv/app' && uptime", "stdin=hallo", "--- stderr ---", "warnung");
        // zweiter Aufruf nutzt die offene Sitzung
        assertThat(env.isOpen(env.resolve("PROD"))).isTrue();
        assertThat(new SshExecTools(env).exec("prod", "whoami", null, null, null)).contains("cmd=whoami");
    }

    @Test
    void execTimesOut() {
        SshEnvironment env = module.environment(config("geheim", Map.of()));
        String out = new SshExecTools(env).exec("prod", "sleep", null, null, 1);
        assertThat(out).startsWith("Zeitüberschreitung nach 1 s");
    }

    @Test
    void sftpListReadAndWrite() {
        SshEnvironment env = module.environment(config("geheim", Map.of(SshModule.ALLOW_WRITE, "true")));
        SshTools tools = new SshTools(env);
        assertThat(tools.listDir("prod", "/")).contains("logs/");
        assertThat(tools.readFile("prod", "/logs/app.log", 2, 2)).contains("    2 | zwei").doesNotContain("eins");
        assertThat(new SshWriteTools(env).writeFile("prod", "/logs/neu.txt", "inhalt", false))
                .contains("6 Bytes geschrieben");
        assertThat(tmp.resolve("root/logs/neu.txt")).hasContent("inhalt");
        new SshWriteTools(env).writeFile("prod", "/logs/neu.txt", "+mehr", true);
        assertThat(tmp.resolve("root/logs/neu.txt")).hasContent("inhalt+mehr");
        assertThatThrownBy(() -> tools.readFile("prod", "/fehlt.txt", null, null)).hasMessageContaining("nicht gefunden");
    }

    @Test
    void toolsDependOnSwitches() {
        List<String> names = module.createTools(config("geheim", Map.of())).stream()
                .map(t -> t.getToolDefinition().name()).toList();
        assertThat(names).contains("connections", "list_dir", "read_file", "exec").doesNotContain("write_file");
    }

    @Test
    void wrongPasswordIsReported() {
        SshEnvironment env = module.environment(config("falsch", Map.of()));
        assertThatThrownBy(() -> new SshExecTools(env).exec(null, "ls", null, null, null))
                .hasMessageContaining("Anmeldung fehlgeschlagen").hasMessageNotContaining("falsch");
    }

    @Test
    void hostKeyIsTrustedOnFirstUseAndChangedKeyRejected() throws Exception {
        assertThat(module.testConnection(config("geheim", Map.of())).success()).isTrue();
        String known = Files.readString(tmp.resolve("known_hosts"));
        assertThat(known).contains("[127.0.0.1]:" + server.getPort());

        // Anderer Server (anderer Schlüssel) unter derselben Adresse: known_hosts auf ihn umschreiben
        SshServer other = server(tmp.resolve("hostkey2.ser"));
        try {
            SshModule probe = new SshModule(tmp.resolve("known_hosts2"));
            Map<String, String> r = Map.of("name", "o", "host", "127.0.0.1", "port", String.valueOf(other.getPort()),
                    "username", "alice", "password", "geheim");
            probe.testConnection(ModuleConfig.of(probe.configSchema(),
                    Map.of(SshModule.CONNECTIONS, ModuleConfig.formatRecords(List.of(r)))));
            String otherKey = Files.readString(tmp.resolve("known_hosts2"))
                    .replace("]:" + other.getPort(), "]:" + server.getPort());
            Files.writeString(tmp.resolve("known_hosts"), otherKey);
        } finally {
            other.stop(true);
        }
        var result = module.testConnection(config("geheim", Map.of()));
        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("Host-Key hat sich geändert");
    }

    @Test
    void strictPolicyRejectsUnknownHost() {
        var result = module.testConnection(config("geheim", Map.of(SshModule.HOST_KEY_POLICY, "strict")));
        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("Host-Key unbekannt");
    }

    /** Liest stdin, gibt Befehl und Eingabe aus, schreibt nach stderr und endet mit Exit-Code 3; "sleep" hängt. */
    private static final class EchoCommand implements Command {
        private final String command;
        private InputStream in;
        private OutputStream out;
        private OutputStream err;
        private ExitCallback exit;

        EchoCommand(String command) {
            this.command = command;
        }

        @Override
        public void setInputStream(InputStream in) {
            this.in = in;
        }

        @Override
        public void setOutputStream(OutputStream out) {
            this.out = out;
        }

        @Override
        public void setErrorStream(OutputStream err) {
            this.err = err;
        }

        @Override
        public void setExitCallback(ExitCallback callback) {
            this.exit = callback;
        }

        @Override
        public void start(ChannelSession channel, Environment env) {
            Thread.ofVirtual().start(() -> {
                try {
                    if (command.equals("sleep")) {
                        Thread.sleep(30_000);
                    }
                    ByteArrayOutputStream stdin = new ByteArrayOutputStream();
                    in.transferTo(stdin);
                    out.write(("cmd=" + command + "\nstdin=" + stdin.toString(StandardCharsets.UTF_8) + "\n")
                            .getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    err.write("warnung\n".getBytes(StandardCharsets.UTF_8));
                    err.flush();
                    exit.onExit(3);
                } catch (InterruptedException | IOException e) {
                    exit.onExit(1);
                }
            });
        }

        @Override
        public void destroy(ChannelSession channel) {
        }
    }
}
