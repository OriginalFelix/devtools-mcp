package systems.grebe.devtools.mcp.modules.http;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import systems.grebe.devtools.mcp.core.CommandRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** System-Proxy setzen und zurücksetzen – nur gegen eine nachgebildete Shell, nie gegen das echte System. */
class SystemProxyTest {

    @TempDir
    Path dir;

    /** Zeichnet Befehle auf und antwortet per Funktion (Exit-Code ≠ 0, wenn die Antwort mit "!" beginnt). */
    static final class FakeShell implements SystemProxy.Shell {
        final List<String> commands = new ArrayList<>();
        final Function<String, String> answer;

        FakeShell(Function<String, String> answer) {
            this.answer = answer;
        }

        @Override
        public CommandRunner.Result run(List<String> command) {
            String c = String.join(" ", command);
            commands.add(c);
            String out = answer.apply(c);
            return new CommandRunner.Result(command, out.startsWith("!") ? 1 : 0, false, out.replaceFirst("^!", ""));
        }
    }

    static String mac(String command) {
        if (command.endsWith("-listallnetworkservices")) {
            return "An asterisk (*) denotes that a network service is disabled.\nWi-Fi\n*Bluetooth PAN\nUSB LAN\n";
        }
        if (command.equals("networksetup -getsecurewebproxy USB LAN")) {
            return "Enabled: Yes\nServer: proxy.firma\nPort: 3128\nAuthenticated Proxy Enabled: 0\n";
        }
        if (command.startsWith("networksetup -get")) {
            return "Enabled: No\nServer: \nPort: 0\nAuthenticated Proxy Enabled: 0\n";
        }
        return "";
    }

    @Test
    void macSetsAllServicesAndRestoresPreviousValues() {
        var shell = new FakeShell(SystemProxyTest::mac);
        Path backup = dir.resolve("system-proxy.properties");
        var system = new SystemProxy(backup, shell, SystemProxy.Os.MAC);

        assertThat(system.enable(8888)).isEmpty();
        assertThat(backup).exists();
        assertThat(shell.commands).contains("networksetup -setwebproxy Wi-Fi 127.0.0.1 8888",
                "networksetup -setsecurewebproxy USB LAN 127.0.0.1 8888")
                .noneMatch(c -> c.contains("Bluetooth"));

        shell.commands.clear();
        assertThat(system.restore()).contains("wiederhergestellt");
        assertThat(shell.commands).containsExactly(
                "networksetup -setwebproxystate Wi-Fi off", "networksetup -setsecurewebproxystate Wi-Fi off",
                "networksetup -setwebproxystate USB LAN off", "networksetup -setsecurewebproxy USB LAN proxy.firma 3128");
        assertThat(backup).doesNotExist();
        assertThat(system.restore()).isEmpty();
    }

    @Test
    void macAsksForAdminRightsWhenNetworksetupRefuses() {
        var shell = new FakeShell(c -> c.startsWith("networksetup -set")
                ? "!** Error: Command requires admin privileges." : mac(c));
        new SystemProxy(dir.resolve("b.properties"), shell, SystemProxy.Os.MAC).enable(8888);
        assertThat(shell.commands.getLast()).startsWith("osascript -e do shell script \"'networksetup' "
                + "'-setwebproxy' 'Wi-Fi' '127.0.0.1' '8888' && ").endsWith("\" with administrator privileges");
    }

    @Test
    void restoresAfterFailedEnableAndAfterCrash() {
        Path backup = dir.resolve("b.properties");
        var failing = new FakeShell(c -> c.startsWith("networksetup -setsecurewebproxy") && c.contains("127.0.0.1")
                ? "!kaputt" : mac(c));
        assertThatThrownBy(() -> new SystemProxy(backup, failing, SystemProxy.Os.MAC).enable(8888))
                .hasMessageContaining("kaputt");
        assertThat(backup).doesNotExist();
        assertThat(failing.commands).contains("networksetup -setwebproxystate Wi-Fi off");

        // Absturz: Sicherung bleibt liegen, eine neue Instanz (nächster App-Start) setzt zurück
        new SystemProxy(backup, new FakeShell(SystemProxyTest::mac), SystemProxy.Os.MAC).enable(8888);
        var next = new SystemProxy(backup, new FakeShell(SystemProxyTest::mac), SystemProxy.Os.MAC);
        assertThat(next.active()).isTrue();
        next.restore();
        assertThat(backup).doesNotExist();
    }

    @Test
    void windowsUsesUserRegistryAndRestoresMissingValues() throws Exception {
        String key = SystemProxy.WIN_KEY;
        var shell = new FakeShell(c -> c.contains("/v ProxyEnable") && c.startsWith("reg query")
                ? "\r\n" + key + "\r\n    ProxyEnable    REG_DWORD    0x0\r\n" : c.startsWith("reg query") ? "!nicht da" : "");
        Path backup = dir.resolve("b.properties");
        var system = new SystemProxy(backup, shell, SystemProxy.Os.WINDOWS);
        system.enable(9999);
        assertThat(shell.commands).contains("reg add " + key + " /v ProxyEnable /t REG_DWORD /d 1 /f",
                "reg add " + key + " /v ProxyServer /t REG_SZ /d 127.0.0.1:9999 /f");
        assertThat(shell.commands).anyMatch(c -> c.startsWith("powershell"));
        assertThat(Files.readString(backup)).contains("ProxyEnable=0").doesNotContain("ProxyServer");

        shell.commands.clear();
        system.restore();
        assertThat(shell.commands).contains("reg delete " + key + " /v ProxyServer /f",
                "reg add " + key + " /v ProxyEnable /t REG_DWORD /d 0 /f");
    }

    @Test
    void trustsCaPerPlatform() {
        var shell = new FakeShell(c -> "");
        Path pem = dir.resolve("ca.pem");
        new SystemProxy(dir.resolve("b"), shell, SystemProxy.Os.MAC).trust(pem, false);
        new SystemProxy(dir.resolve("b"), shell, SystemProxy.Os.WINDOWS).trust(pem, true);
        assertThat(shell.commands.getFirst()).startsWith("security add-trusted-cert -r trustRoot -k ")
                .endsWith(pem.toString());
        assertThat(shell.commands.getLast()).isEqualTo("certutil -user -delstore Root " + CertificateAuthority.NAME);
        assertThatThrownBy(() -> new SystemProxy(dir.resolve("b"), shell, SystemProxy.Os.OTHER).enable(1))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
