package systems.grebe.devtools.mcp.modules.http;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import systems.grebe.devtools.mcp.core.CommandRunner;

/**
 * Trägt einen Proxy als System-Proxy ein (wie Fiddler) und stellt die vorherigen Einstellungen wieder her. Die alten
 * Werte liegen bis dahin in einer Datei, damit sie auch nach einem Absturz zurückkommen – sonst bliebe der Rechner
 * ohne Internet, sobald der Proxy nicht mehr läuft.
 */
final class SystemProxy {

    /** Führt einen Befehl aus; austauschbar, damit Tests das echte System nicht anfassen. */
    interface Shell {
        CommandRunner.Result run(List<String> command);
    }

    enum Os {
        MAC, WINDOWS, OTHER;

        static Os current() {
            String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
            return os.contains("mac") ? MAC : os.contains("win") ? WINDOWS : OTHER;
        }
    }

    static final Shell LOCAL = c -> CommandRunner.run(c, Duration.ofMinutes(2));
    static final String WIN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings";

    private final Path backup;
    private final Shell shell;
    private final Os os;
    private Thread hook;

    SystemProxy(Path backup, Shell shell, Os os) {
        this.backup = backup;
        this.shell = shell;
        this.os = os;
    }

    /** Ob ein eingetragener System-Proxy noch zurückgesetzt werden muss (auch nach einem Absturz). */
    synchronized boolean active() {
        return Files.exists(backup);
    }

    /** Trägt {@code 127.0.0.1:port} als System-Proxy für HTTP und HTTPS ein; liefert Hinweise (oder ""). */
    synchronized String enable(int port) {
        if (active()) {
            restore(); // Rest eines Absturzes: erst den echten alten Stand zurück, sonst sicherten wir den eigenen
        }
        Properties saved = new Properties();
        List<String> warnings = new ArrayList<>();
        List<List<String>> set = switch (os) {
            case MAC -> macSave(saved, warnings, port);
            case WINDOWS -> winSave(saved, warnings, port);
            case OTHER -> throw new UnsupportedOperationException("Systemweiter Proxy nur unter macOS und Windows; "
                    + "unter Linux den Prozess mit HTTP_PROXY/HTTPS_PROXY starten.");
        };
        write(saved); // vor dem Ändern: auch ein Absturz mittendrin lässt sich so zurückdrehen
        try {
            run(set);
        } catch (RuntimeException e) {
            try {
                restore();
            } catch (RuntimeException r) {
                e.addSuppressed(r);
            }
            throw e;
        }
        if (os == Os.WINDOWS) {
            notifyWindows();
        }
        if (hook == null) {
            hook = new Thread(this::restoreQuietly, "http-proxy-restore");
            Runtime.getRuntime().addShutdownHook(hook);
        }
        return String.join("\n", warnings);
    }

    /** Stellt die gesicherten Einstellungen wieder her; ohne Sicherung nichts. */
    synchronized String restore() {
        if (!active()) {
            return "";
        }
        Properties saved = read();
        List<List<String>> commands = new ArrayList<>();
        if ("mac".equals(saved.getProperty("os"))) {
            for (int i = 0; saved.containsKey("mac." + i + ".service"); i++) {
                String svc = saved.getProperty("mac." + i + ".service");
                for (String kind : List.of("web", "secure")) {
                    String prefix = "mac." + i + "." + kind;
                    String flag = "secure".equals(kind) ? "securewebproxy" : "webproxy";
                    commands.add("Yes".equals(saved.getProperty(prefix + ".enabled"))
                            ? List.of("networksetup", "-set" + flag, svc, saved.getProperty(prefix + ".server"),
                                    saved.getProperty(prefix + ".port"))
                            : List.of("networksetup", "-set" + flag + "state", svc, "off"));
                }
            }
        } else if ("windows".equals(saved.getProperty("os"))) {
            commands.add(List.of("reg", "add", WIN_KEY, "/v", "ProxyEnable", "/t", "REG_DWORD", "/d",
                    saved.getProperty("ProxyEnable", "0"), "/f"));
            String server = saved.getProperty("ProxyServer");
            if (server != null) {
                commands.add(List.of("reg", "add", WIN_KEY, "/v", "ProxyServer", "/t", "REG_SZ", "/d", server, "/f"));
            } else {
                // scheitert, wenn der Wert schon fehlt – das ist kein Fehler
                shell.run(List.of("reg", "delete", WIN_KEY, "/v", "ProxyServer", "/f"));
            }
        }
        run(commands);
        if ("windows".equals(saved.getProperty("os"))) {
            notifyWindows();
        }
        try {
            Files.delete(backup);
        } catch (IOException e) {
            throw new UncheckedIOException("System-Proxy zurückgesetzt, Sicherung " + backup + " nicht gelöscht", e);
        }
        if (hook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException e) {
                // läuft gerade selbst als Shutdown-Hook
            }
            hook = null;
        }
        return "Vorherige Proxy-Einstellungen des Systems wiederhergestellt.";
    }

    /**
     * Trägt die CA ({@code pem}) in den Vertrauensspeicher des Benutzers ein oder aus; das System fragt dabei selbst
     * nach Bestätigung bzw. Passwort.
     */
    String trust(Path pem, boolean remove) {
        String keychain = Path.of(System.getProperty("user.home"), "Library", "Keychains", "login.keychain-db")
                .toString();
        List<String> command = switch (os) {
            case MAC -> remove ? List.of("security", "delete-certificate", "-t", "-c", CertificateAuthority.NAME,
                    keychain) : List.of("security", "add-trusted-cert", "-r", "trustRoot", "-k", keychain, pem.toString());
            case WINDOWS -> remove ? List.of("certutil", "-user", "-delstore", "Root", CertificateAuthority.NAME)
                    : List.of("certutil", "-user", "-addstore", "Root", pem.toString());
            case OTHER -> throw new UnsupportedOperationException("Nur unter macOS und Windows; unter Linux " + pem
                    + " z.B. nach /usr/local/share/ca-certificates kopieren und update-ca-certificates ausführen.");
        };
        shell.run(command).orThrow(String.join(" ", command));
        return remove ? "CA „" + CertificateAuthority.NAME + "“ aus dem Vertrauensspeicher entfernt."
                : "CA " + pem + " ist jetzt im Vertrauensspeicher des Benutzers.";
    }

    private void restoreQuietly() {
        try {
            restore();
        } catch (RuntimeException e) {
            System.err.println("System-Proxy nicht zurückgesetzt – Sicherung in " + backup + ": " + e.getMessage());
        }
    }

    // --- macOS: networksetup je Netzwerkdienst ---

    private List<List<String>> macSave(Properties saved, List<String> warnings, int port) {
        saved.setProperty("os", "mac");
        List<String> services = shell.run(List.of("networksetup", "-listallnetworkservices"))
                .orThrow("networksetup -listallnetworkservices").output().lines()
                .filter(l -> !l.isBlank() && !l.startsWith("*") && !l.startsWith("An asterisk")).toList();
        List<List<String>> set = new ArrayList<>();
        for (int i = 0; i < services.size(); i++) {
            String svc = services.get(i);
            saved.setProperty("mac." + i + ".service", svc);
            for (String kind : List.of("web", "secure")) {
                String flag = "secure".equals(kind) ? "securewebproxy" : "webproxy";
                String out = shell.run(List.of("networksetup", "-get" + flag, svc)).orThrow("networksetup -get" + flag)
                        .output();
                saved.setProperty("mac." + i + "." + kind + ".enabled", field(out, "Enabled"));
                saved.setProperty("mac." + i + "." + kind + ".server", field(out, "Server"));
                saved.setProperty("mac." + i + "." + kind + ".port", field(out, "Port"));
                set.add(List.of("networksetup", "-set" + flag, svc, "127.0.0.1", String.valueOf(port)));
            }
            if ("Yes".equals(field(shell.run(List.of("networksetup", "-getautoproxyurl", svc)).output(), "Enabled"))) {
                warnings.add("„" + svc + "“ nutzt eine automatische Proxy-Konfiguration (PAC); Programme, die sie "
                        + "bevorzugen, gehen am Proxy vorbei.");
            }
        }
        if (saved.getProperty("mac.0.service") == null) {
            throw new IllegalStateException("Keine aktiven Netzwerkdienste gefunden.");
        }
        return set;
    }

    private static String field(String out, String name) {
        Matcher m = Pattern.compile("(?m)^" + name + ":[ \\t]*(.*)$").matcher(out);
        return m.find() ? m.group(1).strip() : "";
    }

    // --- Windows: WinINET-Einstellungen des Benutzers (ohne Administratorrechte) ---

    private List<List<String>> winSave(Properties saved, List<String> warnings, int port) {
        saved.setProperty("os", "windows");
        String enable = winValue("ProxyEnable");
        saved.setProperty("ProxyEnable", enable == null ? "0" : String.valueOf(Long.decode(enable)));
        String server = winValue("ProxyServer");
        if (server != null) {
            saved.setProperty("ProxyServer", server);
        }
        if (winValue("AutoConfigURL") != null) {
            warnings.add("Windows nutzt eine automatische Proxy-Konfiguration (AutoConfigURL); Programme, die sie "
                    + "bevorzugen, gehen am Proxy vorbei.");
        }
        return List.of(
                List.of("reg", "add", WIN_KEY, "/v", "ProxyEnable", "/t", "REG_DWORD", "/d", "1", "/f"),
                List.of("reg", "add", WIN_KEY, "/v", "ProxyServer", "/t", "REG_SZ", "/d", "127.0.0.1:" + port, "/f"));
    }

    private String winValue(String name) {
        CommandRunner.Result r = shell.run(List.of("reg", "query", WIN_KEY, "/v", name));
        if (!r.ok()) {
            return null; // Wert nicht vorhanden
        }
        Matcher m = Pattern.compile("(?m)^\\s*" + name + "\\s+REG_\\w+\\s*(.*)$").matcher(r.output());
        return m.find() ? m.group(1).strip() : null;
    }

    /** Laufende Programme lesen die Einstellungen sonst erst beim nächsten Start neu. */
    private void notifyWindows() {
        String script = "Add-Type -Namespace W -Name I -MemberDefinition '[DllImport(\"wininet.dll\")] public "
                + "static extern bool InternetSetOption(System.IntPtr h, int o, System.IntPtr b, int l);'; "
                + "[W.I]::InternetSetOption(0, 39, 0, 0) | Out-Null; [W.I]::InternetSetOption(0, 37, 0, 0) | Out-Null";
        shell.run(List.of("powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand",
                Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE))));
    }

    // --- gemeinsam ---

    /** Führt die Befehle aus; verweigert macOS sie ohne Administratorrechte, alle zusammen mit einer Passwortabfrage. */
    private void run(List<List<String>> commands) {
        if (commands.isEmpty()) {
            return;
        }
        CommandRunner.Result first = shell.run(commands.getFirst());
        if (first.ok()) {
            commands.subList(1, commands.size()).forEach(c -> shell.run(c).orThrow(String.join(" ", c)));
            return;
        }
        if (os != Os.MAC || !first.output().toLowerCase(Locale.ROOT).contains("admin")) {
            first.orThrow(String.join(" ", commands.getFirst()));
        }
        String script = commands.stream().map(c -> c.stream().map(SystemProxy::sh).collect(Collectors.joining(" ")))
                .collect(Collectors.joining(" && "));
        shell.run(List.of("osascript", "-e", "do shell script \"" + script.replace("\\", "\\\\").replace("\"", "\\\"")
                + "\" with administrator privileges")).orThrow("networksetup mit Administratorrechten");
    }

    private static String sh(String arg) {
        return "'" + arg.replace("'", "'\\''") + "'";
    }

    private void write(Properties saved) {
        try {
            Files.createDirectories(backup.getParent());
            try (Writer w = Files.newBufferedWriter(backup)) {
                saved.store(w, "Proxy-Einstellungen vor http_proxy_start(system=true)");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Sicherung " + backup + " nicht geschrieben – System-Proxy nicht geändert",
                    e);
        }
    }

    private Properties read() {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(backup)) {
            p.load(r);
        } catch (IOException e) {
            throw new UncheckedIOException("Sicherung " + backup + " nicht lesbar", e);
        }
        return p;
    }
}
