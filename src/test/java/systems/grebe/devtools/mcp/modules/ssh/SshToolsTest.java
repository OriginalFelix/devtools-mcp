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
import systems.grebe.devtools.mcp.core.ToolScope;

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
        ToolScope.LOCAL.close();
        server.stop(true);
    }

    private SshServer server(Path hostKey) throws IOException {
        SshServer s = SshServer.setUpDefaultServer();
        s.setHost("127.0.0.1");
        s.setPort(0);
        s.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(hostKey));
        s.setPasswordAuthenticator((user, password, session) -> "alice".equals(user) && "geheim".equals(password));
        s.setCommandFactory((channel, command) -> new EchoCommand(command));
        s.setShellFactory(channel -> new FakeShell());
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
    void disconnectClosesPooledSessionButNotShells() {
        SshEnvironment env = module.environment(config("geheim", Map.of()));
        SshTools tools = new SshTools(env);
        assertThat(tools.disconnect("prod")).isEqualTo("Verbindung prod war nicht offen.");
        new SshExecTools(env).exec("prod", "whoami", null, null, null);
        new SshShellTools(env).open("prod", false);
        assertThat(tools.disconnect(null)).startsWith("Verbindung prod (alice@127.0.0.1:")
                .contains("getrennt.", "Noch offene Shells auf prod: [sh1]");
        assertThat(env.isOpen(env.resolve("prod"))).isFalse();
        assertThat(tools.connections()).doesNotContain("verbunden").contains("Offene Shell sh1");
        // nächster Aufruf verbindet neu
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

    @Test
    void shellKeepsStateBetweenCommands() {
        SshShellTools shell = new SshShellTools(module.environment(config("geheim", Map.of())));
        assertThat(shell.open("prod", null)).startsWith("Shell sh1 geöffnet (prod,");
        assertThat(shell.exec(null, "cd /srv/app", null)).startsWith("Exit-Code 0 (sh1, /srv/app,");
        assertThat(shell.exec("sh1", "pwd", null)).contains("/srv/app").doesNotContain("DTMCP");
        assertThat(shell.exec("sh1", "fail", null)).startsWith("Exit-Code 2").contains("kaputt");
        assertThat(shell.close("sh1")).startsWith("Shell sh1 beendet und geschlossen");
        assertThatThrownBy(() -> shell.read("sh1", 0, null)).hasMessageContaining("nicht (mehr) offen");
    }

    @Test
    void longRunningCommandIsStreamedThenNextCommandRuns() {
        SshShellTools shell = new SshShellTools(module.environment(config("geheim", Map.of())));
        shell.open("prod", false);
        String first = shell.exec(null, "slow", 1);
        assertThat(first).startsWith("Läuft noch nach 1 s").contains("start").doesNotContain("ende");
        assertThatThrownBy(() -> shell.exec(null, "pwd", null)).hasMessageContaining("läuft noch ein Befehl");
        String rest = shell.read(null, 10, null);
        assertThat(rest).startsWith("Befehl beendet: Exit-Code 0 (sh1, /home/alice)").contains("ende")
                .doesNotContain("DTMCP");
        assertThat(shell.exec(null, "pwd", null)).startsWith("Exit-Code 0").contains("/home/alice");
    }

    @Test
    void interactivePromptIsAnsweredWithSend() {
        SshShellTools shell = new SshShellTools(module.environment(config("geheim", Map.of())));
        shell.open("prod", false);
        assertThat(shell.exec(null, "ask", 1)).startsWith("Läuft noch").contains("Weiter? [y/n]");
        assertThat(shell.send(null, "y", null, null, 5, null)).contains("Antwort: y", "Befehl beendet: Exit-Code 0");
        assertThat(shell.send(null, "exit", null, null, 5, null)).contains("[Shell sh1 beendet");
        assertThat(new SshTools(module.environment(config("geheim", Map.of()))).connections()).doesNotContain("Offene Shell");
    }

    @Test
    void closeStopsRunningCommandOrSaysItCannot() {
        SshShellTools shell = new SshShellTools(module.environment(config("geheim", Map.of())));
        shell.open("prod", false);
        shell.exec(null, "slow", 1);
        assertThat(shell.close(null)).startsWith("Shell sh1 beendet und geschlossen");

        shell.open("prod", false);
        shell.exec(null, "hang", 1);
        assertThat(shell.close(null)).contains("Ende aber nicht bestätigt");
    }

    @Test
    void toolsCarryMcpAnnotations() {
        Map<String, io.modelcontextprotocol.spec.McpSchema.ToolAnnotations> hints = new java.util.HashMap<>();
        module.createTools(config("geheim", Map.of(SshModule.ALLOW_WRITE, "true", SshModule.ALLOW_SUDO, "true")))
                .forEach(t -> hints.put(t.getToolDefinition().name(), systems.grebe.devtools.mcp.core.ToolBeans.annotations(t)));
        assertThat(hints.get("connections").readOnlyHint()).isTrue();
        assertThat(hints.get("read_file").readOnlyHint()).isTrue();
        assertThat(hints.get("shell_read").readOnlyHint()).isTrue();
        assertThat(hints.get("shell_open").destructiveHint()).isFalse();
        assertThat(hints.get("disconnect").readOnlyHint()).isFalse();
        assertThat(hints.get("disconnect").destructiveHint()).isFalse();
        assertThat(hints.get("disconnect").idempotentHint()).isTrue();
        assertThat(hints.get("exec").readOnlyHint()).isFalse();
        assertThat(hints.get("exec").destructiveHint()).isTrue();
        assertThat(hints.get("sudo").destructiveHint()).isTrue();
        assertThat(hints.get("write_file").destructiveHint()).isTrue();
    }

    @Test
    void sudoSendsPasswordViaStdinAndMasksIt() {
        String connections = ModuleConfig.formatRecords(List.of(Map.of(
                "name", "prod", "host", "127.0.0.1", "port", String.valueOf(server.getPort()),
                "username", "alice", "password", "geheim", "sudoPassword", "rootpw")));
        SshEnvironment env = module.environment(ModuleConfig.of(module.configSchema(),
                Map.of(SshModule.CONNECTIONS, connections, SshModule.ALLOW_SUDO, "true")));
        String out = new SshSudoTools(env).sudo(null, "systemctl restart nginx", "/srv", null, "daten", null);
        // EchoCommand gibt Befehl und stdin aus: Passwort steht nur maskiert drin, nie in der Befehlszeile
        assertThat(out).contains("cmd=sudo -S -p '' -- sh -c 'cd '\\''/srv'\\'' && systemctl restart nginx'")
                .contains("stdin=********\ndaten").doesNotContain("rootpw");
    }

    @Test
    void transfersFilesOnlyWithinSharedLocalDirectories() throws IOException {
        Path local = Files.createDirectories(tmp.resolve("local"));
        byte[] binary = {0, 1, 2, (byte) 0xff, 0, 42};
        Files.write(local.resolve("a.bin"), binary);
        SshEnvironment env = module.environment(config("geheim",
                Map.of(SshModule.ALLOW_TRANSFER, "true", SshModule.LOCAL_DIRS, local.toString())));
        SshTransferTools transfer = new SshTransferTools(env);

        assertThat(transfer.upload("prod", "a.bin", "/logs", null)).contains("6 B hochgeladen");
        assertThat(tmp.resolve("root/logs/a.bin")).hasBinaryContent(binary);
        assertThatThrownBy(() -> transfer.upload("prod", "a.bin", "/logs", null)).hasMessageContaining("existiert bereits");
        assertThat(transfer.upload("prod", "a.bin", "/logs/a.bin", true)).contains("hochgeladen");

        assertThat(transfer.download("prod", "/logs/app.log", "neu/app.log", null)).contains("heruntergeladen");
        assertThat(local.resolve("neu/app.log")).hasContent("eins\nzwei\ndrei\n");
        assertThat(transfer.download("prod", "/logs/app.log", "neu", true)).contains(local.resolve("neu/app.log").toString());

        assertThatThrownBy(() -> transfer.download("prod", "/logs/app.log", "../raus.log", null))
                .hasMessageContaining("nicht freigegeben");
        assertThatThrownBy(() -> transfer.upload("prod", tmp.resolve("hostkey1.ser").toString(), "/x", null))
                .hasMessageContaining("nicht freigegeben");
    }

    @Test
    void cleansTerminalControlSequences() {
        assertThat(SshShells.clean("\u001B[1;32mgrün\u001B[0m\r\n\u001B]0;titel\u0007ok")).isEqualTo("grün\nok");
    }

    /**
     * Zeilenweise Shell: {@code { befehl} + {@code }; echo "__DTMCP_x_$?__"} wie von ssh_shell_exec; kennt cd, pwd, fail
     * (Exit 2), slow (1,5 s), ask (liest eine Antwort von stdin) und exit.
     */
    private static final class FakeShell implements Command {
        private InputStream in;
        private OutputStream out;
        private OutputStream err;
        private ExitCallback exit;

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
                var r = new java.io.BufferedReader(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
                String cwd = "/home/alice";
                try {
                    String line;
                    while ((line = r.readLine()) != null) {
                        var block = java.util.regex.Pattern.compile("\\{ printf '%s%s\\\\n' 'DTMCP_B_' '([0-9a-f]+)'; (.*)")
                                .matcher(line);
                        String nonce = null;
                        String cmd = line;
                        if (block.matches()) {
                            nonce = block.group(1);
                            cmd = block.group(2);
                            r.readLine(); // "}; printf … DTMCP_E_ …" – die Shell liest den Block ganz, bevor er läuft
                            print(out, "DTMCP_B_" + nonce + "\n");
                        }
                        int code = 0;
                        switch (cmd.split(" ")[0]) {
                            case "cd" -> cwd = cmd.substring(3);
                            case "pwd" -> print(out, cwd + "\n");
                            case "fail" -> {
                                print(err, "kaputt\n");
                                code = 2;
                            }
                            case "slow" -> {
                                print(out, "start\n");
                                Thread.sleep(1500);
                                print(out, "ende\n");
                            }
                            case "hang" -> Thread.sleep(60_000); // ignoriert exit und Signale
                            case "ask" -> {
                                print(out, "Weiter? [y/n] ");
                                print(out, "Antwort: " + r.readLine() + "\n");
                            }
                            case "exit" -> {
                                exit.onExit(0);
                                return;
                            }
                            default -> print(out, "ran: " + cmd + "\n");
                        }
                        if (nonce != null) {
                            print(out, "DTMCP_E_" + nonce + "__" + code + "__" + cwd + "\n");
                        }
                    }
                    exit.onExit(0);
                } catch (IOException | InterruptedException e) {
                    exit.onExit(1);
                }
            });
        }

        private static void print(OutputStream o, String s) throws IOException {
            o.write(s.getBytes(StandardCharsets.UTF_8));
            o.flush();
        }

        @Override
        public void destroy(ChannelSession channel) {
        }
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
